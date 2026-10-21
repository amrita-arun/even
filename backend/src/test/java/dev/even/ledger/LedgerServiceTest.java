package dev.even.ledger;

import dev.even.support.LedgerTestFixture;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServiceTest extends PostgresIntegrationTest {

  @Autowired
  LedgerService ledger;

  @Autowired
  JdbcTemplate jdbc;

  LedgerTestFixture fixture;
  UUID userId;
  UUID accountId;
  UUID transactionId;

  @BeforeEach
  void setUp() {
    fixture = new LedgerTestFixture(jdbc);
    userId = fixture.insertUser("owner@example.com");
    accountId = fixture.insertCreditAccount(userId, "Capital One");
    transactionId = fixture.insertTransaction(
        userId, accountId, 12_000L, "posted", "purchase", "Dinner");
  }

  @Test
  void balancedPairCommitsAndSumsToZero() {
    UUID groupId = ledger.post(
        userId,
        transactionId,
        LedgerPurpose.capture,
        List.of(
            new LedgerLine(LedgerService.EXPENSES_UNCATEGORIZED, 12_000L),
            new LedgerLine(LedgerService.liabilityPath(accountId), -12_000L)));

    Long sum = jdbc.queryForObject(
        "select coalesce(sum(amount_cents), 0) from ledger_entries where txn_group_id = ?",
        Long.class,
        groupId);
    assertThat(sum).isZero();
    assertThat(jdbc.queryForObject("select count(*) from ledger_entries", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void oneSidedPostRollsBackAndLeavesNoRows() {
    assertThatThrownBy(() -> ledger.post(
            userId,
            transactionId,
            LedgerPurpose.capture,
            List.of(
                new LedgerLine(LedgerService.EXPENSES_UNCATEGORIZED, 12_000L),
                new LedgerLine(LedgerService.liabilityPath(accountId), -11_999L))))
        .isInstanceOf(LedgerException.class)
        .hasMessageContaining("sums to");

    assertThat(jdbc.queryForObject("select count(*) from ledger_postings", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("select count(*) from ledger_entries", Integer.class))
        .isZero();
  }

  @Test
  void duplicatePurposeIsRejected() {
    ledger.post(
        userId,
        transactionId,
        LedgerPurpose.capture,
        List.of(
            new LedgerLine(LedgerService.EXPENSES_UNCATEGORIZED, 12_000L),
            new LedgerLine(LedgerService.liabilityPath(accountId), -12_000L)));

    assertThatThrownBy(() -> ledger.post(
            userId,
            transactionId,
            LedgerPurpose.capture,
            List.of(
                new LedgerLine(LedgerService.EXPENSES_UNCATEGORIZED, 12_000L),
                new LedgerLine(LedgerService.liabilityPath(accountId), -12_000L))))
        .isInstanceOf(LedgerException.class)
        .hasMessageContaining("already exists");
  }

  @Test
  void entriesAreAppendOnly() {
    UUID groupId = ledger.post(
        userId,
        transactionId,
        LedgerPurpose.capture,
        List.of(
            new LedgerLine(LedgerService.EXPENSES_UNCATEGORIZED, 12_000L),
            new LedgerLine(LedgerService.liabilityPath(accountId), -12_000L)));

    assertThatThrownBy(() -> jdbc.update(
            "update ledger_entries set amount_cents = 1 where txn_group_id = ?",
            groupId))
        .hasMessageContaining("append-only");
  }

  @Test
  void purchaseCapturePostsExpenseAndLiability() {
    ledger.postForTransaction(userId, transactionId);

    assertThat(ledger.sumPathPrefix(userId, "Expenses:")).isEqualTo(12_000L);
    assertThat(ledger.sumPathPrefix(userId, "Liabilities:")).isEqualTo(-12_000L);
    assertThat(ledger.postingExists(transactionId, LedgerPurpose.capture)).isTrue();
  }

  @Test
  void pendingTransactionPostsNothing() {
    UUID pendingId = fixture.insertTransaction(
        userId, accountId, 5_000L, "pending", "purchase", "Pending coffee");

    assertThat(ledger.postForTransaction(userId, pendingId)).isEmpty();
    assertThat(jdbc.queryForObject("select count(*) from ledger_postings", Integer.class))
        .isZero();
  }

  @Test
  void removedAfterCaptureAppendsReversal() {
    ledger.postForTransaction(userId, transactionId);
    fixture.markRemoved(transactionId);

    assertThat(ledger.reverseForRemoved(userId, transactionId)).isPresent();
    assertThat(ledger.sumPathPrefix(userId, "Expenses:")).isZero();
    assertThat(ledger.sumPathPrefix(userId, "Liabilities:")).isZero();
    assertThat(ledger.postingExists(transactionId, LedgerPurpose.reversal)).isTrue();
  }

  @Test
  void secondUserCannotLoadForeignTransaction() {
    UUID other = fixture.insertUser("other@example.com");
    assertThatThrownBy(() -> ledger.postForTransaction(other, transactionId))
        .isInstanceOf(LedgerException.class)
        .hasMessageContaining("does not belong");
  }

  @Test
  void refundReversesExpenseOntoLiability() {
    UUID refundId = fixture.insertTransaction(
        userId, accountId, -3_000L, "posted", "refund", "Partial refund");
    ledger.postForTransaction(userId, refundId);

    assertThat(ledger.sumPathPrefix(userId, "Expenses:")).isEqualTo(-3_000L);
    assertThat(ledger.sumPathPrefix(userId, "Liabilities:")).isEqualTo(3_000L);
    assertThat(ledger.postingExists(refundId, LedgerPurpose.refund)).isTrue();
  }
}
