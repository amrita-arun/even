package dev.even.plaid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaidAccountPageFilterTest {

  @Test
  void keepsOnlyTheMatchingAccount() throws Exception {
    JsonNode page = new ObjectMapper().readTree("""
        {
          "added": [
            {"transaction_id": "a", "account_id": "acc-1"},
            {"transaction_id": "b", "account_id": "acc-2"}
          ],
          "modified": [],
          "removed": [{"transaction_id": "c", "account_id": "acc-1"}],
          "next_cursor": "n"
        }
        """);

    JsonNode filtered = PlaidAccountPageFilter.forAccount(page, "acc-1");

    assertEquals(1, filtered.path("added").size());
    assertEquals("a", filtered.path("added").get(0).path("transaction_id").asText());
    assertEquals(0, filtered.path("modified").size());
    assertEquals(1, filtered.path("removed").size());
    assertEquals("n", filtered.path("next_cursor").asText());
  }
}
