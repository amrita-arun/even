package dev.even.reports;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ReportService {

  private final JdbcTemplate jdbc;

  public ReportService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long trueSpendCents(UUID userId) {
    Long sum = jdbc.queryForObject(
        """
        select coalesce(sum(e.amount_cents), 0)
        from ledger_entries e
        join ledger_accounts a on a.id = e.ledger_account_id
        where a.user_id = ?
          and a.path like 'Expenses:%'
        """,
        Long.class,
        userId);
    return sum == null ? 0L : sum;
  }

  public List<ReceivableRow> receivables(UUID userId) {
    return jdbc.query(
        """
        select p.id as person_id,
               p.display_name,
               p.kind,
               coalesce(sum(e.amount_cents), 0) as amount_cents
        from people p
        left join ledger_accounts a
          on a.user_id = p.user_id
         and a.path = 'Receivable:' || p.id::text
        left join ledger_entries e on e.ledger_account_id = a.id
        where p.user_id = ?
        group by p.id, p.display_name, p.kind
        having coalesce(sum(e.amount_cents), 0) <> 0
        order by p.display_name
        """,
        (rs, n) -> new ReceivableRow(
            UUID.fromString(rs.getString("person_id")),
            rs.getString("display_name"),
            rs.getString("kind"),
            rs.getLong("amount_cents")),
        userId);
  }

  /**
   * Card balance is the current amount owed on liability accounts.
   * True spend and category rows are the owner's share of purchases in the range.
   */
  public MonthReport month(UUID userId, LocalDate from, LocalDate to) {
    if (from == null || to == null) {
      throw new IllegalArgumentException("from and to are required");
    }
    if (to.isBefore(from)) {
      throw new IllegalArgumentException("to must be on or after from");
    }
    List<PurchaseShare> purchases = jdbc.query(
        """
        select t.category, t.amount_cents,
               coalesce((
                 select sum(ss.share_cents)
                 from split_shares ss
                 join splits s on s.id = ss.split_id
                 where s.transaction_id = t.id and ss.person_id is not null
               ), 0) as friends_cents
        from transactions t
        where t.user_id = ?
          and t.kind = 'purchase'
          and t.status = 'posted'
          and t.posted_on >= ?
          and t.posted_on <= ?
          and t.amount_cents > 0
        """,
        (rs, n) -> new PurchaseShare(
            rs.getString("category"),
            rs.getLong("amount_cents"),
            rs.getLong("friends_cents")),
        userId,
        from,
        to);
    long friends = 0L;
    long owner = 0L;
    Map<String, Long> byCategory = new LinkedHashMap<>();
    for (String bucket : CATEGORY_BUCKETS) {
      byCategory.put(bucket, 0L);
    }
    for (PurchaseShare purchase : purchases) {
      long theirs = Math.min(purchase.friendsCents(), purchase.amountCents());
      long mine = purchase.amountCents() - theirs;
      friends += theirs;
      owner += mine;
      String bucket = displayCategory(purchase.category());
      byCategory.merge(bucket, mine, Long::sum);
    }
    Long refunds = jdbc.queryForObject(
        """
        select coalesce(sum(amount_cents), 0)
        from transactions
        where user_id = ?
          and kind = 'refund'
          and status = 'posted'
          and posted_on >= ?
          and posted_on <= ?
          and amount_cents > 0
        """,
        Long.class,
        userId,
        from,
        to);
    long refundCents = refunds == null ? 0L : refunds;
    List<CategoryShare> categories = new ArrayList<>();
    for (String bucket : CATEGORY_BUCKETS) {
      long cents = byCategory.getOrDefault(bucket, 0L);
      if (cents > 0) {
        categories.add(new CategoryShare(bucket, cents));
      }
    }
    return new MonthReport(cardBalanceCents(userId), owner - refundCents, friends, categories);
  }

  public BalancesReport balances(UUID userId) {
    Map<UUID, Long> remaining = new LinkedHashMap<>();
    long owedToYou = 0L;
    for (ReceivableRow row : receivables(userId)) {
      if (row.amountCents() > 0) {
        remaining.put(row.personId(), row.amountCents());
        owedToYou += row.amountCents();
      }
    }
    List<ShareLine> shares = jdbc.query(
        """
        select p.id as person_id, p.display_name, t.id as transaction_id,
               t.description, t.posted_on, ss.share_cents
        from split_shares ss
        join splits s on s.id = ss.split_id
        join transactions t on t.id = s.transaction_id
        join people p on p.id = ss.person_id
        where s.user_id = ?
          and ss.person_id is not null
          and ss.share_cents > 0
        order by t.posted_on desc, t.id
        """,
        (rs, n) -> new ShareLine(
            UUID.fromString(rs.getString("person_id")),
            rs.getString("display_name"),
            UUID.fromString(rs.getString("transaction_id")),
            rs.getString("description"),
            rs.getDate("posted_on").toLocalDate(),
            rs.getLong("share_cents")),
        userId);
    LocalDate today = LocalDate.now();
    List<OpenBalance> open = new ArrayList<>();
    for (ShareLine share : shares) {
      long left = remaining.getOrDefault(share.personId(), 0L);
      if (left <= 0) {
        continue;
      }
      long take = Math.min(left, share.shareCents());
      remaining.put(share.personId(), left - take);
      long age = Math.max(0, ChronoUnit.DAYS.between(share.postedOn(), today));
      open.add(new OpenBalance(
          share.personId(),
          share.displayName(),
          share.transactionId(),
          share.description(),
          share.postedOn(),
          age,
          take));
    }
    return new BalancesReport(owedToYou, 0L, open, List.of());
  }

  private long cardBalanceCents(UUID userId) {
    Long sum = jdbc.queryForObject(
        """
        select coalesce(-sum(e.amount_cents), 0)
        from ledger_entries e
        join ledger_accounts a on a.id = e.ledger_account_id
        where a.user_id = ?
          and a.path like 'Liabilities:%'
        """,
        Long.class,
        userId);
    return sum == null ? 0L : sum;
  }

  static String displayCategory(String category) {
    String normalized = category == null ? "" : category.toLowerCase(Locale.ROOT);
    if (normalized.contains("dining") || normalized.contains("food") || normalized.contains("coffee")) {
      return "Food & coffee";
    }
    if (normalized.contains("grocery")) {
      return "Groceries";
    }
    if (normalized.contains("rideshare")
        || normalized.contains("transport")
        || normalized.contains("gas")) {
      return "Transport";
    }
    if (normalized.contains("merchandise") || normalized.contains("shopping")) {
      return "Shopping";
    }
    return "Everything else";
  }

  private static final List<String> CATEGORY_BUCKETS = List.of(
      "Food & coffee", "Shopping", "Groceries", "Transport", "Everything else");

  public record ReceivableRow(
      UUID personId, String displayName, String kind, long amountCents) {}

  public record MonthReport(
      long cardBalanceCents,
      long trueSpendCents,
      long friendsShareCents,
      List<CategoryShare> categories) {}

  public record CategoryShare(String category, long amountCents) {}

  public record BalancesReport(
      long owedToYouCents,
      long youOweCents,
      List<OpenBalance> owedToYou,
      List<OpenBalance> youOwe) {}

  public record OpenBalance(
      UUID personId,
      String displayName,
      UUID transactionId,
      String description,
      LocalDate postedOn,
      long ageDays,
      long amountCents) {}

  private record PurchaseShare(String category, long amountCents, long friendsCents) {}

  private record ShareLine(
      UUID personId,
      String displayName,
      UUID transactionId,
      String description,
      LocalDate postedOn,
      long shareCents) {}
}
