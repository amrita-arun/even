package dev.even.splits;

import dev.even.ledger.LedgerService;
import dev.even.people.PeopleService;
import dev.even.reports.ReportService;
import dev.even.support.LedgerTestFixture;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClubReimbursementTest extends PostgresIntegrationTest {

  @Autowired
  SplitService splits;

  @Autowired
  PeopleService people;

  @Autowired
  LedgerService ledger;

  @Autowired
  ReportService reports;

  @Autowired
  JdbcTemplate jdbc;

  UUID userId;
  UUID purchaseId;
  UUID clubId;
  UUID alex;

  @BeforeEach
  void setUp() {
    LedgerTestFixture fixture = new LedgerTestFixture(jdbc);
    userId = fixture.insertUser("owner@example.com");
    UUID accountId = fixture.insertCreditAccount(userId, "Capital One");
    purchaseId = fixture.insertTransaction(
        userId, accountId, 4_200L, "posted", "purchase", "Club supplies");
    ledger.postForTransaction(userId, purchaseId);
    clubId = people.create(userId, null, null, "Robotics", null, null, "reimbursement").id();
    alex = people.create(userId, "Alex").id();
  }

  @Test
  void clubReimbursementMovesTheWholeChargeOffTrueSpend() {
    splits.reimburse(userId, purchaseId, clubId);

    assertThat(reports.trueSpendCents(userId)).isEqualTo(0L);
    assertThat(reports.receivables(userId)).singleElement().satisfies(row -> {
      assertThat(row.displayName()).isEqualTo("Robotics");
      assertThat(row.kind()).isEqualTo("reimbursement");
      assertThat(row.amountCents()).isEqualTo(4_200L);
    });
  }

  @Test
  void friendCannotBeUsedAsAClubReimbursement() {
    assertThatThrownBy(() -> splits.reimburse(userId, purchaseId, alex))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reimbursement");
    assertThat(reports.trueSpendCents(userId)).isEqualTo(4_200L);
  }
}
