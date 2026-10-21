package dev.even.ledger;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class LedgerService {

  public static final String EXPENSES_UNCATEGORIZED = "Expenses:Uncategorized";
  public static final String ASSETS_CHECKING = "Assets:Checking";

  private final JdbcTemplate jdbc;

  public LedgerService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public static String liabilityPath(UUID accountId) {
    return "Liabilities:" + accountId;
  }

  public static String receivablePath(UUID personId) {
    return "Receivable:" + personId;
  }

  /**
   * Append a balanced journal group. Debit amounts are positive; credits are negative.
   * The deferred DB trigger rejects the commit when the group does not sum to 0.
   */
  @Transactional
  public UUID post(
      UUID userId,
      UUID sourceTransactionId,
      LedgerPurpose purpose,
      List<LedgerLine> lines) {
    if (userId == null || sourceTransactionId == null || purpose == null) {
      throw new IllegalArgumentException("userId, sourceTransactionId, and purpose are required");
    }
    if (lines == null || lines.size() < 2) {
      throw new IllegalArgumentException("a journal group needs at least two lines");
    }

    long sum = 0L;
    for (LedgerLine line : lines) {
      sum += line.amountCents();
    }
    if (sum != 0L) {
      // Also enforced by the deferred DB trigger at commit; fail fast here so a
      // multi-row ingest does not need SET CONSTRAINTS IMMEDIATE (which would
      // break later groups in the same transaction).
      throw new LedgerException("ledger group sums to " + sum);
    }

    UUID txnGroupId = UUID.randomUUID();
    try {
      jdbc.update(
          """
          insert into ledger_postings (id, user_id, txn_group_id, source_transaction_id, purpose)
          values (?, ?, ?, ?, ?)
          """,
          UUID.randomUUID(),
          userId,
          txnGroupId,
          sourceTransactionId,
          purpose.name());

      for (LedgerLine line : lines) {
        UUID ledgerAccountId = ensureAccount(userId, line.path());
        jdbc.update(
            """
            insert into ledger_entries
              (id, txn_group_id, ledger_account_id, amount_cents, source_transaction_id)
            values (?, ?, ?, ?, ?)
            """,
            UUID.randomUUID(),
            txnGroupId,
            ledgerAccountId,
            line.amountCents(),
            sourceTransactionId);
      }
    } catch (DataIntegrityViolationException e) {
      throw new LedgerException(
          "posting already exists for transaction " + sourceTransactionId + " purpose " + purpose,
          e);
    } catch (DataAccessException e) {
      Throwable root = e.getMostSpecificCause();
      String message = root.getMessage() == null ? e.getMessage() : root.getMessage();
      if (message != null && message.contains("sums to")) {
        throw new LedgerException(message, e);
      }
      throw e;
    }

    return txnGroupId;
  }

  /**
   * Posts capture / card_payment / refund for a posted transaction that has no
   * matching purpose yet. Pending and already-posted purposes are no-ops.
   */
  @Transactional
  public Optional<UUID> postForTransaction(UUID userId, UUID transactionId) {
    SourceTransaction txn = loadOwnedTransaction(userId, transactionId);
    if (!"posted".equals(txn.status())) {
      return Optional.empty();
    }

    long amount = Math.abs(txn.amountCents());
    if (amount == 0L) {
      throw new LedgerException("cannot post a zero-amount transaction");
    }

    LedgerPurpose purpose = switch (txn.kind()) {
      case "purchase" -> LedgerPurpose.capture;
      case "payment" -> LedgerPurpose.card_payment;
      case "refund" -> LedgerPurpose.refund;
      default -> throw new LedgerException("unknown transaction kind: " + txn.kind());
    };
    if (postingExists(transactionId, purpose)) {
      return Optional.empty();
    }

    List<LedgerLine> lines = switch (txn.kind()) {
      case "purchase" -> List.of(
          new LedgerLine(EXPENSES_UNCATEGORIZED, amount),
          new LedgerLine(liabilityPath(txn.accountId()), -amount));
      case "payment" -> List.of(
          new LedgerLine(liabilityPath(txn.accountId()), amount),
          new LedgerLine(ASSETS_CHECKING, -amount));
      case "refund" -> List.of(
          new LedgerLine(EXPENSES_UNCATEGORIZED, -amount),
          new LedgerLine(liabilityPath(txn.accountId()), amount));
      default -> throw new LedgerException("unknown transaction kind: " + txn.kind());
    };
    return Optional.of(post(userId, transactionId, purpose, lines));
  }

  /**
   * When a posted transaction is removed after a journal write, append a
   * reversing group that mirrors the original lines with opposite signs.
   */
  @Transactional
  public Optional<UUID> reverseForRemoved(UUID userId, UUID transactionId) {
    SourceTransaction txn = loadOwnedTransaction(userId, transactionId);
    if (!"removed".equals(txn.status())) {
      throw new LedgerException("reversal requires status=removed");
    }

    Optional<ExistingPosting> original = findReversiblePosting(transactionId);
    if (original.isEmpty()) {
      return Optional.empty();
    }

    if (postingExists(transactionId, LedgerPurpose.reversal)) {
      return Optional.empty();
    }

    List<LedgerLine> reverse = new ArrayList<>();
    for (LedgerLine line : original.get().lines()) {
      reverse.add(new LedgerLine(line.path(), -line.amountCents()));
    }
    return Optional.of(post(userId, transactionId, LedgerPurpose.reversal, reverse));
  }

  public long sumPathPrefix(UUID userId, String pathPrefix) {
    Long sum = jdbc.queryForObject(
        """
        select coalesce(sum(e.amount_cents), 0)
        from ledger_entries e
        join ledger_accounts a on a.id = e.ledger_account_id
        where a.user_id = ?
          and a.path like ?
        """,
        Long.class,
        userId,
        pathPrefix + "%");
    return sum == null ? 0L : sum;
  }

  public boolean postingExists(UUID sourceTransactionId, LedgerPurpose purpose) {
    Integer count = jdbc.queryForObject(
        """
        select count(*) from ledger_postings
        where source_transaction_id = ? and purpose = ?
        """,
        Integer.class,
        sourceTransactionId,
        purpose.name());
    return count != null && count > 0;
  }

  UUID ensureAccount(UUID userId, String path) {
    jdbc.update(
        """
        insert into ledger_accounts (id, user_id, path)
        values (?, ?, ?)
        on conflict (user_id, path) do nothing
        """,
        UUID.randomUUID(),
        userId,
        path);
    return jdbc.queryForObject(
        "select id from ledger_accounts where user_id = ? and path = ?",
        UUID.class,
        userId,
        path);
  }

  private SourceTransaction loadOwnedTransaction(UUID userId, UUID transactionId) {
    List<SourceTransaction> rows = jdbc.query(
        """
        select id, user_id, account_id, amount_cents, status, kind
        from transactions
        where id = ?
        """,
        (rs, rowNum) -> new SourceTransaction(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            UUID.fromString(rs.getString("account_id")),
            rs.getLong("amount_cents"),
            rs.getString("status"),
            rs.getString("kind")),
        transactionId);
    if (rows.isEmpty()) {
      throw new LedgerException("transaction not found: " + transactionId);
    }
    SourceTransaction txn = rows.getFirst();
    if (!txn.userId().equals(userId)) {
      throw new LedgerException("transaction does not belong to user");
    }
    return txn;
  }

  private Optional<ExistingPosting> findReversiblePosting(UUID transactionId) {
    for (LedgerPurpose purpose : List.of(
        LedgerPurpose.capture, LedgerPurpose.card_payment, LedgerPurpose.refund)) {
      List<UUID> groups = jdbc.query(
          """
          select txn_group_id from ledger_postings
          where source_transaction_id = ? and purpose = ?
          """,
          (rs, rowNum) -> UUID.fromString(rs.getString("txn_group_id")),
          transactionId,
          purpose.name());
      if (groups.isEmpty()) {
        continue;
      }
      UUID groupId = groups.getFirst();
      List<LedgerLine> lines = jdbc.query(
          """
          select a.path, e.amount_cents
          from ledger_entries e
          join ledger_accounts a on a.id = e.ledger_account_id
          where e.txn_group_id = ?
          order by e.created_at, e.id
          """,
          (rs, rowNum) -> new LedgerLine(rs.getString("path"), rs.getLong("amount_cents")),
          groupId);
      return Optional.of(new ExistingPosting(purpose, lines));
    }
    return Optional.empty();
  }

  private record SourceTransaction(
      UUID id,
      UUID userId,
      UUID accountId,
      long amountCents,
      String status,
      String kind) {}

  private record ExistingPosting(LedgerPurpose purpose, List<LedgerLine> lines) {}
}
