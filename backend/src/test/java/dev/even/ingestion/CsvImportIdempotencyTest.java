package dev.even.ingestion;

import dev.even.accounts.AccountService;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CsvImportIdempotencyTest extends PostgresIntegrationTest {

  @Autowired
  CsvConnector csvConnector;

  @Autowired
  IngestService ingest;

  @Autowired
  AccountService accounts;

  @Autowired
  JdbcTemplate jdbc;

  UUID userId;
  UUID accountId;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    accountId = accounts.create(userId, "owner@example.com", "Capital One", "credit", "USD").id();
  }

  @Test
  void importingStatementTwiceYieldsOneRowAndOnePostingEach() throws Exception {
    try (InputStream in = new ClassPathResource("fixtures/statement.csv").getInputStream()) {
      ingest.apply(accountId, userId, csvConnector.fetch(accountId, in));
    }
    try (InputStream in = new ClassPathResource("fixtures/statement.csv").getInputStream()) {
      ingest.apply(accountId, userId, csvConnector.fetch(accountId, in));
    }

    assertThat(jdbc.queryForObject(
            "select count(*) from transactions where account_id = ?", Integer.class, accountId))
        .isEqualTo(3);
    assertThat(jdbc.queryForObject(
            "select count(*) from ledger_postings where user_id = ?", Integer.class, userId))
        .isEqualTo(3);
    assertThat(jdbc.queryForObject(
            """
            select count(*) from ledger_postings
            where source_transaction_id in (
              select id from transactions where account_id = ?
            ) and purpose = 'capture'
            """,
            Integer.class,
            accountId))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            """
            select count(*) from ledger_postings
            where source_transaction_id in (
              select id from transactions where account_id = ?
            ) and purpose = 'card_payment'
            """,
            Integer.class,
            accountId))
        .isEqualTo(1);
  }
}
