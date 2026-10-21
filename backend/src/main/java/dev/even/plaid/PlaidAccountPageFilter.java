package dev.even.plaid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Keeps one Plaid account's rows from an item-level {@code /transactions/sync} page.
 */
public final class PlaidAccountPageFilter {

  private PlaidAccountPageFilter() {}

  public static JsonNode forAccount(JsonNode page, String externalAccountId) {
    if (!(page instanceof ObjectNode root)) {
      return page;
    }
    ObjectNode copy = root.deepCopy();
    copy.set("added", matching(root.path("added"), externalAccountId));
    copy.set("modified", matching(root.path("modified"), externalAccountId));
    copy.set("removed", matching(root.path("removed"), externalAccountId));
    return copy;
  }

  private static ArrayNode matching(JsonNode array, String externalAccountId) {
    ArrayNode kept = JsonNodeFactory.instance.arrayNode();
    if (!array.isArray()) {
      return kept;
    }
    for (JsonNode node : array) {
      if (node.isObject() && externalAccountId.equals(node.path("account_id").asText())) {
        kept.add(node);
      }
    }
    return kept;
  }
}
