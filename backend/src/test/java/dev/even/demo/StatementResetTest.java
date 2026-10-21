package dev.even.demo;

import dev.even.accounts.AccountService;
import dev.even.ingestion.IngestService;
import dev.even.ingestion.ProviderTransaction;
import dev.even.ingestion.SyncPage;
import dev.even.ledger.LedgerService;
import dev.even.people.PeopleService;
import dev.even.reports.ReportService;
import dev.even.splits.SplitMethod;
import dev.even.splits.SplitService;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatementResetTest extends PostgresIntegrationTest {

  @Autowired
  DemoSeedService seed;

  @Autowired
  StatementResetService reset;

  @Autowired
  AccountService accounts;

  @Autowired
  PeopleService people;

  @Autowired
  IngestService ingest;

  @Autowired
  SplitService splits;

  @Autowired
  LedgerService ledger;

  @Autowired
  ReportService reports;

  @Autowired
  JdbcTemplate jdbc;

  @Test
  void resetDropsPeopleSplitsAndDemoAndKeepsTheStatement() {
    UUID userId = UUID.randomUUID();
    UUID cardId = accounts.create(userId, "owner@example.com", "Capital One", "credit", "USD").id();
    ingest.apply(cardId, userId, SyncPage.ofAdded(List.of(new ProviderTransaction(
        "stmt-lyft",
        Optional.empty(),
        5_092L,
        "USD",
        LocalDate.of(2026, 10, 14),
        "posted",
        "Lyft",
        "purchase",
        "{}"))));
    UUID sam = people.create(userId, "Sam", "Lee", null, null, null, "friend").id();
    UUID lyftId = jdbc.queryForObject(
        """
        select transaction_id from transaction_external_ids
        where account_id = ? and external_id = 'stmt-lyft'
        """,
        UUID.class,
        cardId);
    splits.split(
        userId,
        lyftId,
        new SplitService.SplitRequest(
            SplitMethod.equal,
            0L,
            0L,
            List.of(
                new SplitService.ShareRequest(null, null, null, null),
                new SplitService.ShareRequest(sam, null, null, null))));
    seed.seed(userId, "owner@example.com");

    reset.reset(userId);

    assertThat(people.list(userId)).isEmpty();
    assertThat(reports.receivables(userId)).isEmpty();
    assertThat(accounts.list(userId)).extracting(AccountService.AccountRecord::name)
        .containsExactly("Capital One");
    assertThat(jdbc.queryForObject(
            "select count(*) from transactions where user_id = ?", Integer.class, userId))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "select description from transactions where user_id = ?", String.class, userId))
        .isEqualTo("Lyft");
    assertThat(ledger.sumPathPrefix(userId, "Expenses:")).isEqualTo(5_092L);
    assertThat(jdbc.queryForObject(
            "select count(*) from splits where user_id = ?", Integer.class, userId))
        .isEqualTo(0);
  }
}
