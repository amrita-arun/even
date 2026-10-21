package dev.even.plaid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves the checked-in sync-page fixtures when {@code even.plaid.live=false}
 * (default). Tracks cursors in memory per access token for multi-page sync.
 */
@Component
@ConditionalOnProperty(name = "even.plaid.live", havingValue = "false", matchIfMissing = true)
public class FixturePlaidApiClient implements PlaidApiClient {

  private final ObjectMapper mapper;
  private final Map<String, AtomicInteger> pageByToken = new ConcurrentHashMap<>();

  public FixturePlaidApiClient(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public LinkTokenResult createLinkToken(String userId, String webhookUrl) {
    return new LinkTokenResult("fixture-link-token-" + userId, true);
  }

  @Override
  public ExchangeResult exchangePublicToken(String publicToken) {
    return new ExchangeResult(
        "fixture-item-" + Math.abs(publicToken.hashCode()),
        "access-sandbox-fixture-" + Math.abs(publicToken.hashCode()),
        "Fixture Bank");
  }

  @Override
  public SyncResult syncTransactions(String accessToken, Optional<String> cursor) {
    try {
      if (cursor.isPresent() && cursor.get().contains("after-posted")) {
        return emptyPage(cursor.get());
      }
      int page = resolvePage(accessToken, cursor);
      String resource = page <= 1
          ? "fixtures/plaid-sync-page-1.json"
          : "fixtures/plaid-sync-page-2.json";
      if (page > 2) {
        return emptyPage("cursor-after-posted");
      }
      try (InputStream in = new ClassPathResource(resource).getInputStream()) {
        JsonNode root = mapper.readTree(in);
        String next = root.path("next_cursor").asText("");
        boolean hasMore = page < 2;
        pageByToken.computeIfAbsent(accessToken, t -> new AtomicInteger(0)).set(page);
        return new SyncResult(root, next, hasMore);
      }
    } catch (Exception e) {
      throw new IllegalStateException("fixture sync failed", e);
    }
  }

  private int resolvePage(String accessToken, Optional<String> cursor) {
    if (cursor.isPresent() && cursor.get().contains("after-pending")) {
      return 2;
    }
    if (cursor.isEmpty() || cursor.get().isBlank()) {
      return 1;
    }
    return pageByToken.getOrDefault(accessToken, new AtomicInteger(0)).get() + 1;
  }

  @Override
  public boolean isLive() {
    return false;
  }

  public void reset(String accessToken) {
    pageByToken.remove(accessToken);
  }

  private SyncResult emptyPage(String cursor) {
    ObjectNode root = mapper.createObjectNode();
    root.putArray("added");
    root.putArray("modified");
    root.putArray("removed");
    root.put("next_cursor", cursor);
    return new SyncResult(root, cursor, false);
  }
}
