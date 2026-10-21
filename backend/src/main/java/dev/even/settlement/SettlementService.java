package dev.even.settlement;

import dev.even.ingestion.NotFoundException;
import dev.even.ledger.LedgerLine;
import dev.even.ledger.LedgerPurpose;
import dev.even.ledger.LedgerService;
import dev.even.people.PeopleService;
import dev.even.reports.ReportService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class SettlementService {

  static final int MATCH_WINDOW_DAYS = 30;

  private final JdbcTemplate jdbc;
  private final PeopleService people;
  private final ReportService reports;
  private final LedgerService ledger;

  public SettlementService(
      JdbcTemplate jdbc,
      PeopleService people,
      ReportService reports,
      LedgerService ledger) {
    this.jdbc = jdbc;
    this.people = people;
    this.reports = reports;
    this.ledger = ledger;
  }

  public SettlementDrafts.Draft draft(UUID userId, UUID personId, String channel, String note) {
    PeopleService.PersonRecord person = people.requireOwned(userId, personId);
    long amount = openReceivableCents(userId, personId);
    if (amount <= 0) {
      throw new IllegalArgumentException("no open receivable for " + person.displayName());
    }
    Handles handles = loadHandles(personId);
    return switch (channel.toLowerCase(Locale.ROOT)) {
      case "venmo" -> SettlementDrafts.venmo(handles.venmo(), person.displayName(), amount, note);
      case "zelle" -> SettlementDrafts.zelle(handles.zelle(), person.displayName(), amount, note);
      default -> throw new IllegalArgumentException("channel must be venmo or zelle");
    };
  }

  /**
   * Scans checking inflows against open receivables and upserts {@code suggested} rows.
   * Does not clear the receivable until {@link #confirm}.
   */
  @Transactional
  public List<SettlementRecord> refreshSuggestions(UUID userId) {
    List<ReportService.ReceivableRow> open = reports.receivables(userId);
    List<DepositCandidate> deposits = loadUnmatchedDeposits(userId);
    List<SettlementRecord> created = new ArrayList<>();
    for (ReportService.ReceivableRow receivable : open) {
      if (receivable.amountCents() <= 0) {
        continue;
      }
      Optional<DepositCandidate> match = bestMatch(receivable, deposits);
      if (match.isEmpty()) {
        continue;
      }
      DepositCandidate deposit = match.get();
      deposits.remove(deposit);
      created.add(upsertSuggested(userId, receivable.personId(), deposit));
    }
    return list(userId, "suggested");
  }

  public List<SettlementRecord> list(UUID userId, String status) {
    if (status == null || status.isBlank()) {
      return jdbc.query(
          """
          select s.id, s.person_id, p.display_name, s.amount_cents, s.status,
                 s.source_transaction_id, s.channel, s.draft_text
          from settlements s
          join people p on p.id = s.person_id
          where s.user_id = ?
          order by s.created_at desc
          """,
          this::mapSettlement,
          userId);
    }
    return jdbc.query(
        """
        select s.id, s.person_id, p.display_name, s.amount_cents, s.status,
               s.source_transaction_id, s.channel, s.draft_text
        from settlements s
        join people p on p.id = s.person_id
        where s.user_id = ? and s.status = ?
        order by s.created_at desc
        """,
        this::mapSettlement,
        userId,
        status);
  }

  @Transactional
  public SettlementRecord confirm(UUID userId, UUID settlementId) {
    SettlementRecord row = requireOwned(userId, settlementId);
    if ("confirmed".equals(row.status())) {
      return row;
    }
    if (!"suggested".equals(row.status()) && !"rejected".equals(row.status())) {
      throw new IllegalArgumentException("settlement cannot be confirmed from status " + row.status());
    }
    if (row.sourceTransactionId() == null) {
      throw new IllegalArgumentException("settlement has no source transaction");
    }
    clearReceivable(userId, row.personId(), row.amountCents(), row.sourceTransactionId());
    jdbc.update(
        """
        update settlements
        set status = 'confirmed', confirmed_at = now()
        where id = ?
        """,
        settlementId);
    return requireOwned(userId, settlementId);
  }

  @Transactional
  public SettlementRecord reject(UUID userId, UUID settlementId) {
    SettlementRecord row = requireOwned(userId, settlementId);
    if ("confirmed".equals(row.status())) {
      throw new IllegalArgumentException("confirmed settlement cannot be rejected");
    }
    jdbc.update("update settlements set status = 'rejected' where id = ?", settlementId);
    return requireOwned(userId, settlementId);
  }

  /**
   * Manually marks a receivable paid (no deposit match). Creates a synthetic source
   * transaction and posts the settlement journal group.
   */
  @Transactional
  public SettlementRecord markPaid(UUID userId, UUID personId, Long amountCentsOrNull) {
    people.requireOwned(userId, personId);
    long open = openReceivableCents(userId, personId);
    if (open <= 0) {
      throw new IllegalArgumentException("no open receivable");
    }
    long amount = amountCentsOrNull == null ? open : amountCentsOrNull;
    if (amount <= 0 || amount > open) {
      throw new IllegalArgumentException("amount must be between 1 and open receivable");
    }
    UUID txnId = insertManualSettlementTxn(userId, personId, amount);
    clearReceivable(userId, personId, amount, txnId);
    UUID settlementId = UUID.randomUUID();
    jdbc.update(
        """
        insert into settlements
          (id, user_id, person_id, amount_cents, status, source_transaction_id, channel, draft_text, confirmed_at)
        values (?, ?, ?, ?, 'confirmed', ?, 'manual', ?, now())
        """,
        settlementId,
        userId,
        personId,
        amount,
        txnId,
        "Manual mark paid");
    return requireOwned(userId, settlementId);
  }

  private void clearReceivable(UUID userId, UUID personId, long amountCents, UUID sourceTxnId) {
    if (ledger.postingExists(sourceTxnId, LedgerPurpose.settlement)) {
      return;
    }
    long open = openReceivableCents(userId, personId);
    if (amountCents > open) {
      throw new IllegalArgumentException("settlement amount exceeds open receivable");
    }
    ledger.post(
        userId,
        sourceTxnId,
        LedgerPurpose.settlement,
        List.of(
            new LedgerLine(LedgerService.ASSETS_CHECKING, amountCents),
            new LedgerLine(LedgerService.receivablePath(personId), -amountCents)));
  }

  private SettlementRecord upsertSuggested(UUID userId, UUID personId, DepositCandidate deposit) {
    List<UUID> existing = jdbc.query(
        "select id from settlements where source_transaction_id = ?",
        (rs, n) -> UUID.fromString(rs.getString("id")),
        deposit.transactionId());
    if (!existing.isEmpty()) {
      return requireOwned(userId, existing.getFirst());
    }
    UUID id = UUID.randomUUID();
    String draft = "Suggested match: " + deposit.description()
        + " (" + SettlementDrafts.dollars(deposit.amountCents()) + ")";
    jdbc.update(
        """
        insert into settlements
          (id, user_id, person_id, amount_cents, status, source_transaction_id, channel, draft_text)
        values (?, ?, ?, ?, 'suggested', ?, 'deposit_match', ?)
        """,
        id,
        userId,
        personId,
        deposit.amountCents(),
        deposit.transactionId(),
        draft);
    return requireOwned(userId, id);
  }

  private Optional<DepositCandidate> bestMatch(
      ReportService.ReceivableRow receivable, List<DepositCandidate> deposits) {
    String name = receivable.displayName().toLowerCase(Locale.ROOT);
    return deposits.stream()
        .filter(d -> d.amountCents() == receivable.amountCents())
        .filter(d -> {
          String desc = d.description().toLowerCase(Locale.ROOT);
          return desc.contains(name) || nameTokensHit(name, desc);
        })
        .filter(d -> !d.postedOn().isBefore(LocalDate.now().minusDays(MATCH_WINDOW_DAYS)))
        .min(Comparator.comparing(DepositCandidate::postedOn).reversed());
  }

  private static boolean nameTokensHit(String name, String description) {
    for (String token : name.split("\\s+")) {
      if (token.length() >= 3 && description.contains(token)) {
        return true;
      }
    }
    return false;
  }

  private List<DepositCandidate> loadUnmatchedDeposits(UUID userId) {
    return jdbc.query(
        """
        select t.id, t.amount_cents, t.posted_on, t.description
        from transactions t
        join accounts a on a.id = t.account_id
        where t.user_id = ?
          and a.account_type = 'depository'
          and t.status = 'posted'
          and t.amount_cents < 0
          and not exists (
            select 1 from settlements s
            where s.source_transaction_id = t.id
              and s.status in ('suggested', 'confirmed')
          )
        order by t.posted_on desc
        """,
        (rs, n) -> new DepositCandidate(
            UUID.fromString(rs.getString("id")),
            Math.abs(rs.getLong("amount_cents")),
            rs.getDate("posted_on").toLocalDate(),
            rs.getString("description")),
        userId);
  }

  private UUID insertManualSettlementTxn(UUID userId, UUID personId, long amountCents) {
    UUID accountId = findOrCreateSettlementAccount(userId);
    UUID txnId = UUID.randomUUID();
    PeopleService.PersonRecord person = people.requireOwned(userId, personId);
    jdbc.update(
        """
        insert into transactions
          (id, account_id, user_id, amount_cents, currency, posted_on, status, description, kind, raw_payload)
        values (?, ?, ?, ?, 'USD', current_date, 'posted', ?, 'refund', '{}'::jsonb)
        """,
        txnId,
        accountId,
        userId,
        -amountCents,
        "Settlement from " + person.displayName());
    return txnId;
  }

  private UUID findOrCreateSettlementAccount(UUID userId) {
    List<UUID> existing = jdbc.query(
        """
        select id from accounts
        where user_id = ? and account_type = 'depository'
        order by created_at
        limit 1
        """,
        (rs, n) -> UUID.fromString(rs.getString("id")),
        userId);
    if (!existing.isEmpty()) {
      return existing.getFirst();
    }
    UUID institutionId = jdbc.query(
        """
        select id from institutions
        where user_id = ? and provider = 'manual' and name = 'Manual'
        limit 1
        """,
        (rs, n) -> UUID.fromString(rs.getString("id")),
        userId)
        .stream()
        .findFirst()
        .orElseGet(() -> {
          UUID id = UUID.randomUUID();
          jdbc.update(
              "insert into institutions (id, user_id, name, provider) values (?, ?, 'Manual', 'manual')",
              id,
              userId);
          return id;
        });
    UUID accountId = UUID.randomUUID();
    jdbc.update(
        """
        insert into accounts
          (id, user_id, institution_id, name, account_type, currency, provider)
        values (?, ?, ?, 'Checking', 'depository', 'USD', 'manual')
        """,
        accountId,
        userId,
        institutionId);
    return accountId;
  }

  private long openReceivableCents(UUID userId, UUID personId) {
    return reports.receivables(userId).stream()
        .filter(r -> r.personId().equals(personId))
        .mapToLong(ReportService.ReceivableRow::amountCents)
        .findFirst()
        .orElse(0L);
  }

  private Handles loadHandles(UUID personId) {
    return jdbc.query(
        "select venmo_handle, zelle_handle from people where id = ?",
        (rs, n) -> new Handles(rs.getString("venmo_handle"), rs.getString("zelle_handle")),
        personId)
        .stream()
        .findFirst()
        .orElse(new Handles(null, null));
  }

  private SettlementRecord requireOwned(UUID userId, UUID settlementId) {
    List<SettlementRecord> rows = jdbc.query(
        """
        select s.id, s.person_id, p.display_name, s.amount_cents, s.status,
               s.source_transaction_id, s.channel, s.draft_text
        from settlements s
        join people p on p.id = s.person_id
        where s.id = ?
        """,
        this::mapSettlement,
        settlementId);
    if (rows.isEmpty()) {
      throw new NotFoundException("settlement not found");
    }
    SettlementRecord row = rows.getFirst();
    Integer owned = jdbc.queryForObject(
        "select count(*) from settlements where id = ? and user_id = ?",
        Integer.class,
        settlementId,
        userId);
    if (owned == null || owned == 0) {
      throw new NotFoundException("settlement not found");
    }
    return row;
  }

  private SettlementRecord mapSettlement(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    String txn = rs.getString("source_transaction_id");
    return new SettlementRecord(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("person_id")),
        rs.getString("display_name"),
        rs.getLong("amount_cents"),
        rs.getString("status"),
        txn == null ? null : UUID.fromString(txn),
        rs.getString("channel"),
        rs.getString("draft_text"));
  }

  private record Handles(String venmo, String zelle) {}

  private record DepositCandidate(
      UUID transactionId, long amountCents, LocalDate postedOn, String description) {}

  public record SettlementRecord(
      UUID id,
      UUID personId,
      String displayName,
      long amountCents,
      String status,
      UUID sourceTransactionId,
      String channel,
      String draftText) {}
}
