package dev.even.transactions;

import dev.even.ingestion.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class TransactionService {

  private final JdbcTemplate jdbc;

  public TransactionService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<TransactionRow> list(
      UUID userId, LocalDate from, LocalDate to, UUID accountId, String query, String state) {
    String filter = normalizeState(state);
    StringBuilder sql = new StringBuilder(
        """
        select t.id, t.account_id, t.amount_cents, t.currency, t.posted_on, t.status,
               t.description, t.kind, t.category,
               exists (
                 select 1 from ledger_postings p where p.source_transaction_id = t.id
               ) as has_journal,
               exists (
                 select 1 from splits s where s.transaction_id = t.id
               ) as has_split,
               coalesce((
                 select sum(ss.share_cents)
                 from split_shares ss
                 join splits s on s.id = ss.split_id
                 where s.transaction_id = t.id and ss.person_id is not null
               ), 0) as friends_cents,
               (
                 select r.name
                 from flags f
                 join rules r on r.id = f.rule_id
                 where f.transaction_id = t.id and f.status = 'open'
                 order by f.created_at
                 limit 1
               ) as flag_name
        from transactions t
        where t.user_id = ?
        """);
    java.util.ArrayList<Object> args = new java.util.ArrayList<>();
    args.add(userId);
    if (from != null) {
      sql.append(" and t.posted_on >= ?");
      args.add(from);
    }
    if (to != null) {
      sql.append(" and t.posted_on <= ?");
      args.add(to);
    }
    if (accountId != null) {
      sql.append(" and t.account_id = ?");
      args.add(accountId);
    }
    if (query != null && !query.isBlank()) {
      sql.append(" and t.description ilike ? escape '\\'");
      args.add(likeContains(query.trim()));
    }
    sql.append(" order by t.posted_on desc, t.created_at desc");

    List<TransactionRow> rows = jdbc.query(
        sql.toString(),
        (rs, rowNum) -> mapRow(rs),
        args.toArray());
    if ("all".equals(filter)) {
      return rows;
    }
    return rows.stream().filter(row -> filter.equals(row.splitState())).toList();
  }

  private static TransactionRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    boolean hasSplit = rs.getBoolean("has_split");
    String flagName = rs.getString("flag_name");
    String splitState = hasSplit ? "split" : (flagName != null ? "flagged" : "unsplit");
    long amount = rs.getLong("amount_cents");
    long friends = rs.getLong("friends_cents");
    Long yourShare = null;
    if ("purchase".equals(rs.getString("kind")) && amount > 0 && !"flagged".equals(splitState)) {
      yourShare = amount - Math.min(friends, amount);
    }
    return new TransactionRow(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("account_id")),
        amount,
        rs.getString("currency"),
        rs.getDate("posted_on").toLocalDate(),
        rs.getString("status"),
        rs.getString("description"),
        rs.getString("kind"),
        rs.getBoolean("has_journal"),
        rs.getString("category"),
        yourShare,
        splitState,
        flagName);
  }

  private static String normalizeState(String state) {
    if (state == null || state.isBlank() || "all".equalsIgnoreCase(state)) {
      return "all";
    }
    String normalized = state.toLowerCase(Locale.ROOT);
    if (!STATES.contains(normalized)) {
      throw new IllegalArgumentException("state must be all, split, unsplit, or flagged");
    }
    return normalized;
  }

  private static String likeContains(String query) {
    return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }

  private static final Set<String> STATES = Set.of("all", "split", "unsplit", "flagged");

  public List<LedgerEntryRow> entries(UUID userId, UUID transactionId) {
    requireOwned(userId, transactionId);
    return jdbc.query(
        """
        select e.id, e.txn_group_id, a.path, e.amount_cents, p.purpose
        from ledger_entries e
        join ledger_accounts a on a.id = e.ledger_account_id
        join ledger_postings p on p.txn_group_id = e.txn_group_id
        where e.source_transaction_id = ?
          and p.user_id = ?
        order by e.created_at, e.id
        """,
        (rs, rowNum) -> new LedgerEntryRow(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("txn_group_id")),
            rs.getString("path"),
            rs.getLong("amount_cents"),
            rs.getString("purpose")),
        transactionId,
        userId);
  }

  private void requireOwned(UUID userId, UUID transactionId) {
    Integer count = jdbc.queryForObject(
        "select count(*) from transactions where id = ? and user_id = ?",
        Integer.class,
        transactionId,
        userId);
    if (count == null || count == 0) {
      throw new NotFoundException("transaction not found");
    }
  }

  public record TransactionRow(
      UUID id,
      UUID accountId,
      long amountCents,
      String currency,
      LocalDate postedOn,
      String status,
      String description,
      String kind,
      boolean hasJournal,
      String category,
      Long yourShareCents,
      String splitState,
      String flagName) {}

  public record LedgerEntryRow(
      UUID id, UUID txnGroupId, String path, long amountCents, String purpose) {}
}
