package dev.even.splits;

import dev.even.ingestion.NotFoundException;
import dev.even.ledger.LedgerLine;
import dev.even.ledger.LedgerPurpose;
import dev.even.ledger.LedgerService;
import dev.even.people.PeopleService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SplitService {

  private final JdbcTemplate jdbc;
  private final SplitEngine engine;
  private final PeopleService people;
  private final LedgerService ledger;

  public SplitService(
      JdbcTemplate jdbc,
      SplitEngine engine,
      PeopleService people,
      LedgerService ledger) {
    this.jdbc = jdbc;
    this.engine = engine;
    this.people = people;
    this.ledger = ledger;
  }

  @Transactional
  public SplitResult split(UUID userId, UUID transactionId, SplitRequest request) {
    TxnRow txn = loadOwnedPurchase(userId, transactionId);
    if (ledger.postingExists(transactionId, LedgerPurpose.split)) {
      throw new IllegalArgumentException("transaction already has a split posting");
    }
    if (!ledger.postingExists(transactionId, LedgerPurpose.capture)) {
      throw new IllegalArgumentException("transaction has no capture posting yet");
    }

    long total = Math.abs(txn.amountCents());
    List<SplitEngine.ShareInput> inputs = request.shares().stream()
        .map(s -> {
          if (s.personId() != null) {
            people.requireOwned(userId, s.personId());
          }
          return new SplitEngine.ShareInput(
              s.personId(), s.shareCents(), s.basisPoints(), s.itemizedCents());
        })
        .toList();

    List<SplitEngine.ComputedShare> computed = engine.compute(
        total,
        request.method(),
        inputs,
        request.taxCents(),
        request.tipCents());

    UUID splitId = UUID.randomUUID();
    jdbc.update(
        """
        insert into splits (id, user_id, transaction_id, method, tax_cents, tip_cents)
        values (?, ?, ?, ?, ?, ?)
        """,
        splitId,
        userId,
        transactionId,
        request.method().name(),
        request.taxCents(),
        request.tipCents());

    long friendsTotal = 0L;
    List<LedgerLine> lines = new ArrayList<>();
    for (SplitEngine.ComputedShare share : computed) {
      jdbc.update(
          """
          insert into split_shares (id, split_id, person_id, share_cents)
          values (?, ?, ?, ?)
          """,
          UUID.randomUUID(),
          splitId,
          share.personId(),
          share.shareCents());
      if (share.personId() != null && share.shareCents() > 0) {
        friendsTotal += share.shareCents();
        lines.add(new LedgerLine(LedgerService.receivablePath(share.personId()), share.shareCents()));
      }
    }

    if (friendsTotal > 0) {
      lines.add(new LedgerLine(LedgerService.EXPENSES_UNCATEGORIZED, -friendsTotal));
      ledger.post(userId, transactionId, LedgerPurpose.split, lines);
    }

    return new SplitResult(splitId, transactionId, computed);
  }

  /**
   * Someone else owes the full charge. Owner share is zero, so true spend drops
   * by the purchase and the open receivable is that reimbursement.
   */
  @Transactional
  public SplitResult reimburse(UUID userId, UUID transactionId, UUID personId) {
    PeopleService.PersonRecord owedBy = people.requireOwned(userId, personId);
    if (!"reimbursement".equals(owedBy.kind())) {
      throw new IllegalArgumentException("full payback must be a reimbursement, not a friend");
    }
    TxnRow txn = loadOwnedPurchase(userId, transactionId);
    long total = Math.abs(txn.amountCents());
    return split(
        userId,
        transactionId,
        new SplitRequest(
            SplitMethod.fixed,
            0L,
            0L,
            List.of(
                new ShareRequest(null, 0L, null, null),
                new ShareRequest(personId, total, null, null))));
  }

  private TxnRow loadOwnedPurchase(UUID userId, UUID transactionId) {
    List<TxnRow> rows = jdbc.query(
        """
        select id, user_id, amount_cents, status, kind
        from transactions
        where id = ?
        """,
        (rs, n) -> new TxnRow(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getLong("amount_cents"),
            rs.getString("status"),
            rs.getString("kind")),
        transactionId);
    if (rows.isEmpty() || !rows.getFirst().userId().equals(userId)) {
      throw new NotFoundException("transaction not found");
    }
    TxnRow txn = rows.getFirst();
    if (!"posted".equals(txn.status()) || !"purchase".equals(txn.kind())) {
      throw new IllegalArgumentException("only posted purchases can be split");
    }
    if (txn.amountCents() <= 0) {
      throw new IllegalArgumentException("purchase amount must be positive");
    }
    return txn;
  }

  public record ShareRequest(
      UUID personId,
      Long shareCents,
      Integer basisPoints,
      Long itemizedCents) {}

  public record SplitRequest(
      SplitMethod method,
      long taxCents,
      long tipCents,
      List<ShareRequest> shares) {
    public SplitRequest {
      if (shares == null) {
        shares = List.of();
      }
    }
  }

  public record SplitResult(
      UUID splitId, UUID transactionId, List<SplitEngine.ComputedShare> shares) {}

  private record TxnRow(
      UUID id, UUID userId, long amountCents, String status, String kind) {}
}
