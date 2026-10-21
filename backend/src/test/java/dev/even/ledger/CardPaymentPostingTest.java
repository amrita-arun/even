package dev.even.ledger;

import dev.even.support.LedgerTestFixture;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CardPaymentPostingTest extends PostgresIntegrationTest {

  @Autowired
  LedgerService ledger;

  @Autowired
  JdbcTemplate jdbc;

  LedgerTestFixture fixture;
  UUID userId;
  UUID accountId;

  @BeforeEach
  void setUp() {
    fixture = new LedgerTestFixture(jdbc);
    userId = fixture.insertUser("owner@example.com");
    accountId = fixture.insertCreditAccount(userId, "Capital One");
  }

  @Test
  void cardPaymentDoesNotChangeExpenseSum() {
    UUID purchaseId = fixture.insertTransaction(
        userId, accountId, 12_000L, "posted", "purchase", "Dinner");
    ledger.postForTransaction(userId, purchaseId);
    assertThat(ledger.sumPathPrefix(userId, "Expenses:")).isEqualTo(12_000L);

    // Negative amount: money arriving on the card (payment toward the balance).
    UUID paymentId = fixture.insertTransaction(
        userId, accountId, -12_000L, "posted", "payment", "Payment thank you");
    ledger.postForTransaction(userId, paymentId);

    assertThat(ledger.sumPathPrefix(userId, "Expenses:")).isEqualTo(12_000L);
    assertThat(ledger.sumPathPrefix(userId, "Liabilities:")).isZero();
    assertThat(ledger.sumPathPrefix(userId, "Assets:")).isEqualTo(-12_000L);
    assertThat(ledger.postingExists(paymentId, LedgerPurpose.card_payment)).isTrue();
  }
}
