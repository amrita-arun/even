package dev.even.splits;

import dev.even.accounts.AccountService;
import dev.even.ledger.LedgerPurpose;
import dev.even.ledger.LedgerService;
import dev.even.people.PeopleService;
import dev.even.reports.ReportService;
import dev.even.support.LedgerTestFixture;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DinnerSplitIntegrationTest extends PostgresIntegrationTest {

  @Autowired
  SplitService splits;

  @Autowired
  PeopleService people;

  @Autowired
  LedgerService ledger;

  @Autowired
  ReportService reports;

  @Autowired
  AccountService accounts;

  @Autowired
  JdbcTemplate jdbc;

  UUID userId;
  UUID accountId;
  UUID dinnerId;
  UUID alex;
  UUID sam;
  UUID jo;

  @BeforeEach
  void setUp() {
    LedgerTestFixture fixture = new LedgerTestFixture(jdbc);
    userId = fixture.insertUser("owner@example.com");
    accountId = fixture.insertCreditAccount(userId, "Capital One");
    dinnerId = fixture.insertTransaction(
        userId, accountId, 12_000L, "posted", "purchase", "Dinner");
    ledger.postForTransaction(userId, dinnerId);

    alex = people.create(userId, "Alex").id();
    sam = people.create(userId, "Sam").id();
    jo = people.create(userId, "Jo").id();
  }

  @Test
  void equalSplitLeavesOwnerShareOnExpenseAndFriendsOnReceivables() {
    splits.split(
        userId,
        dinnerId,
        new SplitService.SplitRequest(
            SplitMethod.equal,
            0L,
            0L,
            List.of(
                new SplitService.ShareRequest(null, null, null, null),
                new SplitService.ShareRequest(alex, null, null, null),
                new SplitService.ShareRequest(sam, null, null, null),
                new SplitService.ShareRequest(jo, null, null, null))));

    assertThat(ledger.sumPathPrefix(userId, "Expenses:")).isEqualTo(3_000L);
    assertThat(ledger.sumPathPrefix(userId, "Liabilities:")).isEqualTo(-12_000L);
    assertThat(ledger.sumPathPrefix(userId, "Receivable:")).isEqualTo(9_000L);
    assertThat(reports.trueSpendCents(userId)).isEqualTo(3_000L);
    assertThat(reports.receivables(userId))
        .extracting(ReportService.ReceivableRow::amountCents)
        .containsExactlyInAnyOrder(3_000L, 3_000L, 3_000L);
    assertThat(ledger.postingExists(dinnerId, LedgerPurpose.split)).isTrue();
  }

  @Test
  void cardPaymentDoesNotIncreaseTrueSpend() {
    UUID paymentId = new LedgerTestFixture(jdbc).insertTransaction(
        userId, accountId, -12_000L, "posted", "payment", "Payment");
    ledger.postForTransaction(userId, paymentId);

    assertThat(reports.trueSpendCents(userId)).isEqualTo(12_000L);
  }
}
