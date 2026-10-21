package dev.even.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.even.ingestion.NotFoundException;
import dev.even.ledger.LedgerPurpose;
import dev.even.ledger.LedgerService;
import dev.even.people.PeopleService;
import dev.even.proposals.ProposalService;
import dev.even.reports.ReportService;
import dev.even.settlement.SettlementDrafts;
import dev.even.splits.SplitEngine;
import dev.even.splits.SplitService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class LanguageSplitService {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final PeopleService people;
  private final ProposalService proposals;
  private final SplitEngine engine;
  private final LedgerService ledger;
  private final ReportService reports;
  private final HeuristicSplitModel model;

  public LanguageSplitService(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      PeopleService people,
      ProposalService proposals,
      SplitEngine engine,
      LedgerService ledger,
      ReportService reports,
      HeuristicSplitModel model) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.people = people;
    this.proposals = proposals;
    this.engine = engine;
    this.ledger = ledger;
    this.reports = reports;
    this.model = model;
  }

  @Transactional(noRollbackFor = IllegalArgumentException.class)
  public ParseOutcome parse(UUID userId, UUID transactionId, String text) {
    return interpret(userId, transactionId, text, false);
  }

  @Transactional(noRollbackFor = IllegalArgumentException.class)
  public ParseOutcome receipt(UUID userId, UUID transactionId, String text) {
    return interpret(userId, transactionId, text, true);
  }

  @Transactional
  public ReminderOutcome remind(UUID userId, UUID personId, String draft) {
    PeopleService.PersonRecord person = people.requireOwned(userId, personId);
    ReportService.ReceivableRow owed = reports.receivables(userId).stream()
        .filter(row -> row.personId().equals(personId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no open receivable for " + person.displayName()));
    String template = "Hi " + person.displayName() + ", you owe $"
        + SettlementDrafts.dollars(owed.amountCents()) + ".";
    String candidate = draft == null || draft.isBlank() ? template : draft;
    ReminderChecker.Result checked = ReminderChecker.check(owed.amountCents(), candidate, template);
    audit(
        userId,
        null,
        "reminder",
        draft == null ? "" : draft,
        person.displayName(),
        checked.text(),
        !checked.usedTemplate(),
        null);
    return new ReminderOutcome(checked.text(), checked.usedTemplate(), owed.amountCents());
  }

  private ParseOutcome interpret(UUID userId, UUID transactionId, String text, boolean receipt) {
    Txn txn = loadTxn(userId, transactionId);
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException(receipt ? "receipt text is required" : "split text is required");
    }
    List<HeuristicSplitModel.KnownPerson> known = people.list(userId).stream()
        .map(person -> new HeuristicSplitModel.KnownPerson(
            person.id(),
            person.displayName(),
            person.firstName(),
            person.lastName(),
            person.kind()))
        .toList();
    long total = Math.abs(txn.amountCents());
    HeuristicSplitModel.ModelSplit parsed = receipt
        ? model.parseReceipt(text, known, total)
        : model.parseUtterance(text, known, total);

    boolean accepted = false;
    String message = parsed.rejection();
    UUID proposalId = null;
    try {
      if (parsed.parsed()) {
        if (!ledger.postingExists(transactionId, LedgerPurpose.capture)) {
          message = "transaction has no capture posting yet";
        } else {
          SplitService.SplitRequest request = toRequest(parsed);
          List<SplitEngine.ComputedShare> computed = engine.compute(
              total,
              request.method(),
              request.shares().stream()
                  .map(share -> new SplitEngine.ShareInput(
                      share.personId(), share.shareCents(), share.basisPoints(), share.itemizedCents()))
                  .toList(),
              request.taxCents(),
              request.tipCents());
          String reason = reason(receipt, computed, userId);
          ProposalService.ProposalRecord saved = proposals.savePending(
              userId,
              transactionId,
              reason,
              computed.stream()
                  .map(SplitEngine.ComputedShare::personId)
                  .filter(id -> id != null)
                  .toList(),
              request);
          accepted = true;
          proposalId = saved.id();
          message = reason + ". Nothing is posted until you approve.";
        }
      }
    } catch (IllegalArgumentException | IllegalStateException ex) {
      accepted = false;
      proposalId = null;
      message = ex.getMessage();
    }
    audit(
        userId,
        transactionId,
        receipt ? "receipt" : "parse",
        text,
        txn.description(),
        message,
        accepted,
        parsed.ignoredInstructionLines());
    return new ParseOutcome(accepted, message, proposalId);
  }

  private SplitService.SplitRequest toRequest(HeuristicSplitModel.ModelSplit parsed) {
    List<SplitService.ShareRequest> shares = new ArrayList<>();
    for (HeuristicSplitModel.ModelShare share : parsed.shares()) {
      shares.add(switch (parsed.method()) {
        case equal, percentage -> new SplitService.ShareRequest(share.personId(), null, null, null);
        case fixed -> new SplitService.ShareRequest(share.personId(), share.cents(), null, null);
        case itemized -> new SplitService.ShareRequest(share.personId(), null, null, share.cents());
      });
    }
    return new SplitService.SplitRequest(parsed.method(), parsed.taxCents(), parsed.tipCents(), shares);
  }

  private String reason(boolean receipt, List<SplitEngine.ComputedShare> computed, UUID userId) {
    StringBuilder out = new StringBuilder(receipt ? "Receipt" : "Text");
    out.append(": ");
    for (int i = 0; i < computed.size(); i++) {
      SplitEngine.ComputedShare share = computed.get(i);
      String name = share.personId() == null
          ? "you"
          : people.requireOwned(userId, share.personId()).displayName();
      if (i > 0) {
        out.append(", ");
      }
      out.append(name).append(" $").append(SettlementDrafts.dollars(share.shareCents()));
    }
    return out.toString();
  }

  private void audit(
      UUID userId,
      UUID transactionId,
      String kind,
      String untrustedText,
      String untrustedDescription,
      String message,
      boolean accepted,
      Object extra) {
    ObjectNode request = mapper.createObjectNode();
    request.put("kind", kind);
    request.put("untrustedText", untrustedText == null ? "" : untrustedText);
    request.put("untrustedDescription", untrustedDescription == null ? "" : untrustedDescription);
    ObjectNode response = mapper.createObjectNode();
    response.put("accepted", accepted);
    response.put("message", message == null ? "" : message);
    if (extra instanceof Integer ignored) {
      response.put("ignoredInstructionLines", ignored);
    }
    jdbc.update(
        """
        insert into ai_audit_log (id, user_id, transaction_id, model, request, response)
        values (?, ?, ?, ?, ?::jsonb, ?::jsonb)
        """,
        UUID.randomUUID(),
        userId,
        transactionId,
        HeuristicSplitModel.MODEL,
        request.toString(),
        response.toString());
  }

  private Txn loadTxn(UUID userId, UUID transactionId) {
    List<Txn> rows = jdbc.query(
        """
        select id, user_id, amount_cents, status, kind, description
        from transactions
        where id = ?
        """,
        (rs, n) -> new Txn(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getLong("amount_cents"),
            rs.getString("status"),
            rs.getString("kind"),
            rs.getString("description")),
        transactionId);
    if (rows.isEmpty() || !rows.getFirst().userId().equals(userId)) {
      throw new NotFoundException("transaction not found");
    }
    Txn txn = rows.getFirst();
    if (!"posted".equals(txn.status()) || !"purchase".equals(txn.kind()) || txn.amountCents() <= 0) {
      throw new IllegalArgumentException("only posted purchases can be split");
    }
    return txn;
  }

  public record ParseOutcome(boolean accepted, String message, UUID proposalId) {}

  public record ReminderOutcome(String text, boolean usedTemplate, long amountCents) {}

  private record Txn(
      UUID id, UUID userId, long amountCents, String status, String kind, String description) {}
}
