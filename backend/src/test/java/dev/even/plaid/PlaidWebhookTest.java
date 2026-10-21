package dev.even.plaid;

import dev.even.accounts.AccountService;
import dev.even.auth.UserRepository;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PlaidWebhookTest extends PostgresIntegrationTest {

  @Autowired
  MockMvc mockMvc;

  @Autowired
  PlaidItemService items;

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
  String itemId;
  String accessToken;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    users.upsert(userId, "owner@example.com");
    accountId = accounts.create(userId, "owner@example.com", "Capital One", "credit", "USD").id();
    PlaidItemService.LinkedItem linked =
        items.exchangeAndStore(userId, "public-sandbox-webhook", accountId);
    itemId = linked.itemId();
    accessToken = items.findByAccount(userId, accountId).orElseThrow().accessToken();
    fixtureClient.reset(accessToken);
  }

  @Test
  void webhookWithoutAuthTriggersFixtureSync() throws Exception {
    mockMvc.perform(post("/api/plaid/webhooks")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"webhook_type":"TRANSACTIONS","webhook_code":"SYNC_UPDATES_AVAILABLE","item_id":"%s"}
                """.formatted(itemId)))
        .andExpect(status().isOk());

    assertThat(jdbc.queryForObject(
            "select count(*) from transactions where account_id = ?", Integer.class, accountId))
        .isEqualTo(1);
  }
}
