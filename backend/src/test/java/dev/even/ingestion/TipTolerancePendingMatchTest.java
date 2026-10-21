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

class TipTolerancePendingMatchTest extends PostgresIntegrationTest {

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
  void postedWithoutPendingIdMatchesSameDayPendingWithinTipTolerance() {
    String pending = """
        posted_on,description,amount,external_id,status,kind
        2026-09-10,Restaurant Pending,42.00,pending-tip-1,pending,purchase
        """;
    // No pending_external_id — tip-tolerant fallback must still collapse the rows.
    String posted = """
        posted_on,description,amount,external_id,status,kind
        2026-09-10,Restaurant With Tip,45.50,posted-tip-1,posted,purchase
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

    List<String> ids = jdbc.query(
        """
        select external_id from transaction_external_ids
        where account_id = ? order by external_id
        """,
        (rs, n) -> rs.getString("external_id"),
        accountId);
    assertThat(ids).containsExactly("pending-tip-1", "posted-tip-1");

    assertThat(jdbc.queryForObject(
            "select count(*) from ledger_postings where purpose = 'capture'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void amountOutsideTipToleranceDoesNotMatch() {
    String pending = """
        posted_on,description,amount,external_id,status,kind
        2026-09-10,Restaurant,42.00,pending-far-1,pending,purchase
        """;
    // $30 delta > $25 tip tolerance.
    String posted = """
        posted_on,description,amount,external_id,status,kind
        2026-09-10,Restaurant,72.00,posted-far-1,posted,purchase
        """;

    ingest.apply(accountId, userId, csvConnector.fetch(
        accountId, new ByteArrayInputStream(pending.getBytes(StandardCharsets.UTF_8))));
    ingest.apply(accountId, userId, csvConnector.fetch(
        accountId, new ByteArrayInputStream(posted.getBytes(StandardCharsets.UTF_8))));

    assertThat(jdbc.queryForObject(
            "select count(*) from transactions where account_id = ?", Integer.class, accountId))
        .isEqualTo(2);
  }
}
