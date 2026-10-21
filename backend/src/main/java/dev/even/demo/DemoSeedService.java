package dev.even.demo;

import dev.even.accounts.AccountService;
import dev.even.ingestion.IngestService;
import dev.even.ingestion.ProviderTransaction;
import dev.even.ingestion.SyncPage;
import dev.even.people.PeopleService;
import dev.even.splits.SplitMethod;
import dev.even.splits.SplitService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adds a small sandbox for the signed-in user: a card, a checking deposit,
 * one friend split, and one full reimbursement. Re-running does nothing
 * once the demo card exists.
 */
@Service
public class DemoSeedService {

  static final String CARD_NAME = "Demo Card";

  private final JdbcTemplate jdbc;
  private final AccountService accounts;
  private final PeopleService people;
  private final IngestService ingest;
  private final SplitService splits;

  public DemoSeedService(
      JdbcTemplate jdbc,
      AccountService accounts,
      PeopleService people,
      IngestService ingest,
      SplitService splits) {
    this.jdbc = jdbc;
    this.accounts = accounts;
    this.people = people;
    this.ingest = ingest;
    this.splits = splits;
  }

  @Transactional
  public SeedResult seed(UUID userId, String email) {
    boolean exists = accounts.list(userId).stream().anyMatch(a -> CARD_NAME.equals(a.name()));
    if (exists) {
      return new SeedResult(true, CARD_NAME);
    }

    UUID cardId = accounts.create(userId, email, CARD_NAME, "credit", "USD").id();
    UUID alex = people.create(userId, "Alex", "Kim", null, "alex-kim", null, "friend").id();
    UUID work = people.create(userId, null, null, "Work trip", null, null, "reimbursement").id();

    LocalDate day = LocalDate.now().minusDays(1);
    ingest.apply(cardId, userId, SyncPage.ofAdded(List.of(
        txn("demo-dinner", 12_000L, day, "Dinner with Alex", "purchase"),
        txn("demo-uber", 5_500L, day, "Uber", "purchase"),
        txn("demo-supplies", 4_200L, day, "Office supplies", "purchase"))));

    UUID dinnerId = requireTxn(userId, "demo-dinner");
    UUID suppliesId = requireTxn(userId, "demo-supplies");
    splits.split(
        userId,
        dinnerId,
        new SplitService.SplitRequest(
            SplitMethod.equal,
            0L,
            0L,
            List.of(
                new SplitService.ShareRequest(null, null, null, null),
                new SplitService.ShareRequest(alex, null, null, null))));
    splits.reimburse(userId, suppliesId, work);
    return new SeedResult(false, CARD_NAME);
  }

  private UUID requireTxn(UUID userId, String externalId) {
    List<UUID> ids = jdbc.query(
        """
        select e.transaction_id
        from transaction_external_ids e
        join transactions t on t.id = e.transaction_id
        where e.external_id = ? and t.user_id = ?
        """,
        (rs, n) -> UUID.fromString(rs.getString("transaction_id")),
        externalId,
        userId);
    return ids.stream().findFirst().orElseThrow();
  }

  private static ProviderTransaction txn(
      String externalId, long amountCents, LocalDate postedOn, String description, String kind) {
    return new ProviderTransaction(
        externalId,
        Optional.empty(),
        amountCents,
        "USD",
        postedOn,
        "posted",
        description,
        kind,
        "{}");
  }

  public record SeedResult(boolean alreadySeeded, String accountName) {}
}
