package dev.even.ingestion;

import dev.even.accounts.AccountService;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PendingToPostedTest extends PostgresIntegrationTest {

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
  void pendingThenPostedStaysOneRowAndCapturesOnce() {
    String pending = """
        posted_on,description,amount,external_id,status,kind
        2026-09-10,Restaurant,42.00,pending-1,pending,purchase
        """;
    String posted = """
        posted_on,description,amount,external_id,pending_external_id,status,kind
        2026-09-10,Restaurant With Tip,45.50,posted-1,pending-1,posted,purchase
        """;

    ingest.apply(accountId, userId, csvConnector.fetch(
        accountId, new ByteArrayInputStream(pending.getBytes(StandardCharsets.UTF_8))));
    ingest.apply(accountId, userId, csvConnector.fetch(
        accountId, new ByteArrayInputStream(posted.getBytes(StandardCharsets.UTF_8))));

    assertThat(jdbc.queryForObject(
            "select count(*) from transactions where account_id = ?", Integer.class, accountId))
        .isEqualTo(1);

    Map<String, Object> row = jdbc.queryForMap(
        "select amount_cents, status from transactions where account_id = ?", accountId);
    assertThat(row.get("amount_cents")).isEqualTo(4550L);
    assertThat(row.get("status")).isEqualTo("posted");

    List<String> externalIds = jdbc.query(
        """
        select external_id from transaction_external_ids
        where account_id = ? order by external_id
        """,
        (rs, n) -> rs.getString("external_id"),
        accountId);
    assertThat(externalIds).containsExactly("pending-1", "posted-1");

    assertThat(jdbc.queryForObject(
            "select count(*) from ledger_postings where purpose = 'capture'", Integer.class))
        .isEqualTo(1);
  }
}
