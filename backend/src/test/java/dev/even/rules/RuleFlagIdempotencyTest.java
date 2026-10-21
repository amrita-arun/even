package dev.even.rules;

import dev.even.enrichment.EnrichmentService;
import dev.even.jobs.TransactionProcessor;
import dev.even.ledger.LedgerService;
import dev.even.support.LedgerTestFixture;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleFlagIdempotencyTest extends PostgresIntegrationTest {

  @Autowired
  TransactionProcessor processor;

  @Autowired
  EnrichmentService enrichment;

  @Autowired
  RuleService rules;

  @Autowired
  LedgerService ledger;

  @Autowired
  JdbcTemplate jdbc;

  UUID userId;
  UUID accountId;
  UUID dinnerId;
  UUID uberId;

  @BeforeEach
  void setUp() {
    LedgerTestFixture fixture = new LedgerTestFixture(jdbc);
    userId = fixture.insertUser("owner@example.com");
    accountId = fixture.insertCreditAccount(userId, "Capital One");
    dinnerId = fixture.insertTransaction(
        userId, accountId, 7_500L, "posted", "purchase", "Toast Restaurant");
    uberId = fixture.insertTransaction(
        userId, accountId, 5_500L, "posted", "purchase", "Uber Technologies");
    ledger.postForTransaction(userId, dinnerId);
    ledger.postForTransaction(userId, uberId);
  }

  @Test
  void reEvaluatingRulesDoesNotDuplicateFlags() {
    processor.process(userId, dinnerId);
    processor.process(userId, uberId);
    processor.process(userId, dinnerId);
    processor.process(userId, uberId);

    assertThat(jdbc.queryForObject(
            "select count(*) from flags where user_id = ?", Integer.class, userId))
        .isEqualTo(2);
    assertThat(jdbc.queryForObject(
            """
            select count(*) from flags f
            join rules r on r.id = f.rule_id
            where f.transaction_id = ? and r.name = 'Restaurant over $60'
            """,
            Integer.class,
            dinnerId))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            """
            select count(*) from flags f
            join rules r on r.id = f.rule_id
            where f.transaction_id = ? and r.name = 'Rideshare over $40'
            """,
            Integer.class,
            uberId))
        .isEqualTo(1);

    assertThat(jdbc.queryForObject(
            "select category from transactions where id = ?", String.class, dinnerId))
        .isEqualTo("Dining");
    assertThat(jdbc.queryForObject(
            "select category from transactions where id = ?", String.class, uberId))
        .isEqualTo("Rideshare");
  }
}
