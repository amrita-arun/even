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

class CapitalOnePdfImportTest extends PostgresIntegrationTest {

  @Autowired
  CapitalOnePdfConnector connector;

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
  void importingPdfTwiceIsIdempotentAndPostsLedger() throws Exception {
    try (InputStream in = new ClassPathResource(
        "fixtures/SeptemberStatementCapitalOne.pdf").getInputStream()) {
      ingest.apply(accountId, userId, connector.fetch(accountId, in));
    }
    try (InputStream in = new ClassPathResource(
        "fixtures/SeptemberStatementCapitalOne.pdf").getInputStream()) {
      ingest.apply(accountId, userId, connector.fetch(accountId, in));
    }

    assertThat(jdbc.queryForObject(
            "select count(*) from transactions where account_id = ?", Integer.class, accountId))
        .isEqualTo(48);
    assertThat(jdbc.queryForObject(
            """
            select count(*) from ledger_postings
            where user_id = ? and purpose = 'capture'
            """,
            Integer.class,
            userId))
        .isEqualTo(45);
    assertThat(jdbc.queryForObject(
            """
            select count(*) from ledger_postings
            where user_id = ? and purpose = 'card_payment'
            """,
            Integer.class,
            userId))
        .isEqualTo(3);
  }
}
