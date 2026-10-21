package dev.even.ingestion;

import dev.even.accounts.AccountService;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlaidSyncPageMapperTest extends PostgresIntegrationTest {

  @Autowired
  PlaidSyncPageMapper mapper;

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
  void pendingThenPostedThroughTwoPages() throws Exception {
    try (InputStream page1 = new ClassPathResource("fixtures/plaid-sync-page-1.json").getInputStream()) {
      ingest.apply(accountId, userId, mapper.fromJson(page1, "credit"));
    }
    try (InputStream page2 = new ClassPathResource("fixtures/plaid-sync-page-2.json").getInputStream()) {
      ingest.apply(accountId, userId, mapper.fromJson(page2, "credit"));
    }

    assertThat(jdbc.queryForObject(
            "select count(*) from transactions where account_id = ?", Integer.class, accountId))
        .isEqualTo(1);

    Map<String, Object> row = jdbc.queryForMap(
        "select amount_cents, status, description from transactions where account_id = ?",
        accountId);
    assertThat(row.get("amount_cents")).isEqualTo(4550L);
    assertThat(row.get("status")).isEqualTo("posted");
    assertThat(row.get("description")).isEqualTo("Restaurant With Tip");

    List<String> ids = jdbc.query(
        """
        select external_id from transaction_external_ids
        where account_id = ? order by external_id
        """,
        (rs, n) -> rs.getString("external_id"),
        accountId);
    assertThat(ids).containsExactly("pending-dinner-1", "posted-dinner-1");

    assertThat(jdbc.queryForObject(
            "select count(*) from ledger_postings where purpose = 'capture'", Integer.class))
        .isEqualTo(1);
  }
}
