package dev.even.plaid;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Narrow Plaid surface used by Even. Implementations may call the real API
 * or serve classpath fixtures when credentials are unset.
 */
public interface PlaidApiClient {

  record LinkTokenResult(String linkToken, boolean fixtureMode) {}

  record ExchangeResult(String itemId, String accessToken, String institutionName) {}

  record SyncResult(JsonNode pageJson, String nextCursor, boolean hasMore) {}

  LinkTokenResult createLinkToken(String userId, String webhookUrl);

  ExchangeResult exchangePublicToken(String publicToken);

  SyncResult syncTransactions(String accessToken, Optional<String> cursor);

  boolean isLive();
}
