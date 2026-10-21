package dev.even.demo;

import dev.even.reports.ReportService;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DemoSeedTest extends PostgresIntegrationTest {

  @Autowired
  DemoSeedService seed;

  @Autowired
  ReportService reports;

  @Autowired
  JdbcTemplate jdbc;

  @Test
  void seedIsIdempotentAndPostsSplitPlusReimbursement() {
    UUID userId = UUID.randomUUID();
    DemoSeedService.SeedResult first = seed.seed(userId, "demo@example.com");
    DemoSeedService.SeedResult second = seed.seed(userId, "demo@example.com");

    assertThat(first.alreadySeeded()).isFalse();
    assertThat(second.alreadySeeded()).isTrue();

    // Dinner owner share $60 + Uber $55. Supplies $42 sit on the reimbursement.
    assertThat(reports.trueSpendCents(userId)).isEqualTo(11_500L);
    assertThat(reports.receivables(userId))
        .extracting(ReportService.ReceivableRow::displayName, ReportService.ReceivableRow::amountCents)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("Alex Kim", 6_000L),
            org.assertj.core.groups.Tuple.tuple("Work trip", 4_200L));
    assertThat(jdbc.queryForObject(
            "select count(*) from transactions where user_id = ?", Integer.class, userId))
        .isEqualTo(3);
  }
}
