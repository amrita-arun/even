package dev.even.reports;

import dev.even.accounts.AccountService;
import dev.even.ingestion.IngestService;
import dev.even.ingestion.ProviderTransaction;
import dev.even.ingestion.SyncPage;
import dev.even.jobs.TransactionProcessor;
import dev.even.ledger.LedgerPurpose;
import dev.even.ledger.LedgerService;
import dev.even.people.PeopleService;
import dev.even.plaid.PlaidItemService;
import dev.even.proposals.ProposalService;
import dev.even.rules.RuleService;
import dev.even.splits.SplitMethod;
import dev.even.splits.SplitRuleService;
import dev.even.splits.SplitService;
import dev.even.support.PostgresIntegrationTest;
import dev.even.transactions.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RouteCoverageTest extends PostgresIntegrationTest {

  @Autowired
  AccountService accounts;

  @Autowired
  PeopleService people;

  @Autowired
  IngestService ingest;

  @Autowired
  TransactionProcessor processor;

  @Autowired
  RuleService rules;

  @Autowired
  SplitService splits;

  @Autowired
  SplitRuleService splitRules;

  @Autowired
  ProposalService proposals;

  @Autowired
  ReportService reports;

  @Autowired
  TransactionService transactions;

  @Autowired
  LedgerService ledger;

  @Autowired
  PlaidItemService plaidItems;

  @Autowired
  JdbcTemplate jdbc;

  UUID userId;
  UUID accountId;
  UUID alex;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    accountId = accounts.create(userId, "owner@example.com", "Capital One", "credit", "USD").id();
    alex = people.create(userId, "Alex", "Kim", null, null, null, "friend").id();
    rules.ensureDefaults(userId);
  }

  @Test
  void monthReportUsesTheOwnersShare() {
    UUID dinner = importPurchase("dinner", 5_000L, "Dinner restaurant");
    processor.process(userId, dinner);
    splits.split(
        userId,
        dinner,
        new SplitService.SplitRequest(
            SplitMethod.equal,
            0L,
            0L,
            List.of(
                new SplitService.ShareRequest(null, null, null, null),
                new SplitService.ShareRequest(alex, null, null, null))));

    ReportService.MonthReport month = reports.month(
        userId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

    assertThat(month.trueSpendCents()).isEqualTo(2_500L);
    assertThat(month.friendsShareCents()).isEqualTo(2_500L);
    assertThat(month.cardBalanceCents()).isEqualTo(5_000L);
    assertThat(month.categories()).containsExactly(
        new ReportService.CategoryShare("Food & coffee", 2_500L));
  }

  @Test
  void transactionListFiltersShareAndFlagState() {
    UUID dinner = importPurchase("flagged-dinner", 8_000L, "Dinner restaurant");
    UUID coffee = importPurchase("coffee", 850L, "Blue Bottle coffee");
    processor.process(userId, dinner);
    processor.process(userId, coffee);

    List<TransactionService.TransactionRow> flagged =
        transactions.list(userId, null, null, null, null, "flagged");
    assertThat(flagged).extracting(TransactionService.TransactionRow::description)
        .containsExactly("Dinner restaurant");
    assertThat(flagged.getFirst().yourShareCents()).isNull();
    assertThat(flagged.getFirst().flagName()).isEqualTo("Restaurant over $60");

    List<TransactionService.TransactionRow> coffeeRows =
        transactions.list(userId, null, null, null, "Blue Bottle", "unsplit");
    assertThat(coffeeRows).hasSize(1);
    assertThat(coffeeRows.getFirst().yourShareCents()).isEqualTo(850L);
    assertThat(coffeeRows.getFirst().splitState()).isEqualTo("unsplit");
  }

  @Test
  void openBalanceListsTheUnsettledShare() {
    UUID dinner = importPurchase("owed", 4_000L, "Dinner restaurant");
    processor.process(userId, dinner);
    splits.split(
        userId,
        dinner,
        new SplitService.SplitRequest(
            SplitMethod.fixed,
            0L,
            0L,
            List.of(
                new SplitService.ShareRequest(null, 2_000L, null, null),
                new SplitService.ShareRequest(alex, 2_000L, null, null))));

    ReportService.BalancesReport balances = reports.balances(userId);
    assertThat(balances.youOweCents()).isZero();
    assertThat(balances.youOwe()).isEmpty();
    assertThat(balances.owedToYouCents()).isEqualTo(2_000L);
    assertThat(balances.owedToYou()).singleElement().satisfies(row -> {
      assertThat(row.personId()).isEqualTo(alex);
      assertThat(row.amountCents()).isEqualTo(2_000L);
      assertThat(row.description()).isEqualTo("Dinner restaurant");
    });
  }

  @Test
  void splitRuleCreatesAPendingProposalAndDoesNotPost() {
    splitRules.createRule(userId, "Waymo with Alex", "Waymo", "equal", alex, null, null);
    UUID ride = importPurchase("waymo", 4_000L, "Waymo ride");
    processor.process(userId, ride);

    assertThat(ledger.postingExists(ride, LedgerPurpose.split)).isFalse();
    assertThat(proposals.list(userId, "pending")).singleElement().satisfies(proposal -> {
      assertThat(proposal.reason()).isEqualTo("Rule Waymo with Alex");
      assertThat(proposal.personIds()).containsExactly(alex);
      assertThat(proposal.splitRequestJson()).isNotBlank();
    });
  }

  @Test
  void disconnectRemovesOnlyThatUsersPlaidItem() {
    plaidItems.exchangeAndStore(userId, "public-sandbox-coverage", accountId);
    UUID itemId = jdbc.queryForObject(
        "select id from plaid_items where user_id = ?", UUID.class, userId);

    plaidItems.disconnect(userId, itemId);

    Integer left = jdbc.queryForObject(
        "select count(*) from plaid_items where user_id = ?", Integer.class, userId);
    Integer accountsLeft = jdbc.queryForObject(
        "select count(*) from accounts where id = ?", Integer.class, accountId);
    assertThat(left).isZero();
    assertThat(accountsLeft).isEqualTo(1);
  }

  private UUID importPurchase(String externalId, long amountCents, String description) {
    ingest.apply(accountId, userId, SyncPage.ofAdded(List.of(new ProviderTransaction(
        externalId,
        Optional.empty(),
        amountCents,
        "USD",
        LocalDate.of(2026, 10, 14),
        "posted",
        description,
        "purchase",
        "{}"))));
    return jdbc.queryForObject(
        """
        select transaction_id from transaction_external_ids
        where account_id = ? and external_id = ?
        """,
        UUID.class,
        accountId,
        externalId);
  }
}
