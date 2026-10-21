package dev.even.plaid;

import dev.even.accounts.AccountService;
import dev.even.auth.UserRepository;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlaidSyncReplayTest extends PostgresIntegrationTest {

  @Autowired
  PlaidItemService items;

  @Autowired
  PlaidSyncService sync;

  @Autowired
  FixturePlaidApiClient fixtureClient;

  @Autowired
  AccountService accounts;

  @Autowired
  UserRepository users;

  @Autowired
  JdbcTemplate jdbc;

  UUID userId;
  UUID accountId;
  String accessToken;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    users.upsert(userId, "owner@example.com");
    accountId = accounts.create(userId, "owner@example.com", "Capital One", "credit", "USD").id();
    PlaidItemService.LinkedItem linked =
        items.exchangeAndStore(userId, "public-sandbox-replay", accountId);
    accessToken = items.findByAccount(userId, accountId).orElseThrow().accessToken();
    fixtureClient.reset(accessToken);
    assertThat(linked.live()).isFalse();
  }

  @Test
  void syncingThreeTimesStaysOneTransactionAndOneCapture() {
    sync.syncAccount(userId, accountId);
    fixtureClient.reset(accessToken);
    jdbc.update("update plaid_items set cursor = null");
    sync.syncAccount(userId, accountId);
    fixtureClient.reset(accessToken);
    jdbc.update("update plaid_items set cursor = null");
    sync.syncAccount(userId, accountId);

    assertThat(jdbc.queryForObject(
            "select count(*) from transactions where account_id = ?", Integer.class, accountId))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "select count(*) from ledger_postings where purpose = 'capture'", Integer.class))
        .isEqualTo(1);

    Map<String, Object> row = jdbc.queryForMap(
        "select amount_cents, status from transactions where account_id = ?", accountId);
    assertThat(row.get("amount_cents")).isEqualTo(4550L);
    assertThat(row.get("status")).isEqualTo("posted");
  }

  @Test
  void outOfOrderPageTwoThenPageOneStillOneRow() {
    // Force page 2 first via cursor, then full replay from the start.
    jdbc.update(
        "update plaid_items set cursor = ?",
        "cursor-after-pending");
    sync.syncAccount(userId, accountId);

    fixtureClient.reset(accessToken);
    jdbc.update("update plaid_items set cursor = null");
    sync.syncAccount(userId, accountId);

    assertThat(jdbc.queryForObject(
            "select count(*) from transactions where account_id = ?", Integer.class, accountId))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "select count(*) from ledger_postings where purpose = 'capture'", Integer.class))
        .isEqualTo(1);
  }
}
