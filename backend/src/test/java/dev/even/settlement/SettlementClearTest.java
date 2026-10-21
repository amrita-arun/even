package dev.even.settlement;

import dev.even.accounts.AccountService;
import dev.even.ledger.LedgerPurpose;
import dev.even.ledger.LedgerService;
import dev.even.people.PeopleService;
import dev.even.reports.ReportService;
import dev.even.splits.SplitMethod;
import dev.even.splits.SplitService;
import dev.even.support.LedgerTestFixture;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementClearTest extends PostgresIntegrationTest {

  @Autowired
  SettlementService settlements;

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

  @BeforeEach
  void setUp() {
    LedgerTestFixture fixture = new LedgerTestFixture(jdbc);
    userId = fixture.insertUser("owner@example.com");
    accountId = fixture.insertCreditAccount(userId, "Capital One");
    dinnerId = fixture.insertTransaction(
        userId, accountId, 12_000L, "posted", "purchase", "Dinner");
    ledger.postForTransaction(userId, dinnerId);
    alex = people.create(userId, "Alex", "alex-venmo", "alex@bank.com").id();
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
  }

  @Test
  void markPaidClearsReceivableAndPostsSettlement() {
    assertThat(reports.receivables(userId)).hasSize(1);
    assertThat(reports.receivables(userId).getFirst().amountCents()).isEqualTo(6_000L);

    SettlementService.SettlementRecord paid = settlements.markPaid(userId, alex, null);

    assertThat(paid.status()).isEqualTo("confirmed");
    assertThat(reports.receivables(userId)).isEmpty();
    assertThat(ledger.sumPathPrefix(userId, "Receivable:")).isEqualTo(0L);
    assertThat(ledger.sumPathPrefix(userId, "Assets:Checking")).isEqualTo(6_000L);
    assertThat(ledger.postingExists(paid.sourceTransactionId(), LedgerPurpose.settlement)).isTrue();
  }

  @Test
  void depositMatchStaysSuggestedUntilConfirm() {
    UUID checking = accounts.create(userId, "owner@example.com", "Checking", "depository", "USD").id();
    UUID depositId = new LedgerTestFixture(jdbc).insertTransaction(
        userId, checking, -6_000L, "posted", "refund", "Venmo from Alex");
    jdbc.update("update transactions set posted_on = current_date where id = ?", depositId);

    List<SettlementService.SettlementRecord> suggested = settlements.refreshSuggestions(userId);
    assertThat(suggested).hasSize(1);
    assertThat(suggested.getFirst().status()).isEqualTo("suggested");
    assertThat(suggested.getFirst().sourceTransactionId()).isEqualTo(depositId);
    assertThat(reports.receivables(userId)).hasSize(1);

    settlements.confirm(userId, suggested.getFirst().id());

    assertThat(reports.receivables(userId)).isEmpty();
    assertThat(ledger.postingExists(depositId, LedgerPurpose.settlement)).isTrue();
  }

  @Test
  void venmoDraftUsesOpenReceivable() {
    SettlementDrafts.Draft draft = settlements.draft(userId, alex, "venmo", "Dinner");
    assertThat(draft.url()).contains("amount=60.00");
    assertThat(draft.url()).contains("recipients=alex-venmo");
  }
}
