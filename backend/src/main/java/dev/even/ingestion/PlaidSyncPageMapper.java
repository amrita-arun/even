package dev.even.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Maps a Plaid {@code /transactions/sync} page JSON fixture into a {@link SyncPage}.
 * Does not open a network connection.
 */
@Component
public class PlaidSyncPageMapper {

  private final ObjectMapper mapper;

  public PlaidSyncPageMapper(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public SyncPage fromJson(InputStream json, String accountType) {
    try {
      JsonNode root = mapper.readTree(json);
      List<ProviderTransaction> added = mapList(root.path("added"), accountType);
      List<ProviderTransaction> modified = mapList(root.path("modified"), accountType);
      List<String> removed = new ArrayList<>();
      for (JsonNode node : root.path("removed")) {
        if (node.isTextual()) {
          removed.add(node.asText());
        } else if (node.has("transaction_id")) {
          removed.add(node.get("transaction_id").asText());
        }
      }
      String cursor = root.path("next_cursor").asText("");
      return new SyncPage(added, modified, removed, cursor);
    } catch (IOException e) {
      throw new IllegalArgumentException("invalid Plaid sync page JSON", e);
    }
  }

  private List<ProviderTransaction> mapList(JsonNode array, String accountType) {
    List<ProviderTransaction> out = new ArrayList<>();
    if (!array.isArray()) {
      return out;
    }
    for (JsonNode node : array) {
      out.add(mapOne(node, accountType));
    }
    return out;
  }

  private ProviderTransaction mapOne(JsonNode node, String accountType) {
    String externalId = node.path("transaction_id").asText();
    Optional<String> pendingId = Optional.empty();
    if (node.hasNonNull("pending_transaction_id")
        && !node.get("pending_transaction_id").asText().isBlank()) {
      pendingId = Optional.of(node.get("pending_transaction_id").asText());
    }

    // Plaid amounts are dollars; positive means money left the account.
    long amountCents = MoneyParser.dollarsToCents(node.path("amount").asText());
    String currency = node.path("iso_currency_code").asText("USD");
    LocalDate date = LocalDate.parse(node.path("date").asText());
    boolean pending = node.path("pending").asBoolean(false);
    String status = pending ? "pending" : "posted";
    String description = node.path("name").asText();
    String categoryPrimary = node.path("personal_finance_category").path("primary").asText("");
    String kind = inferKind(amountCents, accountType, categoryPrimary);

    String rawJson;
    try {
      rawJson = mapper.writeValueAsString(node);
    } catch (IOException e) {
      rawJson = "{}";
    }

    return new ProviderTransaction(
        externalId,
        pendingId,
        amountCents,
        currency,
        date,
        status,
        description,
        kind,
        rawJson);
  }

  static String inferKind(long amountCents, String accountType, String categoryPrimary) {
    if (amountCents > 0) {
      return "purchase";
    }
    if ("credit".equals(accountType) && "LOAN_PAYMENTS".equals(categoryPrimary)) {
      return "payment";
    }
    return "refund";
  }
}
