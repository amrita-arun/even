package dev.even.plaid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;

/**
 * Live Plaid HTTP client. Enabled with {@code even.plaid.live=true} plus
 * {@code PLAID_CLIENT_ID} / {@code PLAID_SECRET}.
 */
@Component
@ConditionalOnProperty(name = "even.plaid.live", havingValue = "true")
public class HttpPlaidApiClient implements PlaidApiClient {

  private final RestClient client;
  private final ObjectMapper mapper;
  private final String clientId;
  private final String secret;
  private final String products;
  private final String redirectUri;

  public HttpPlaidApiClient(
      ObjectMapper mapper,
      @Value("${even.plaid.client-id}") String clientId,
      @Value("${even.plaid.secret}") String secret,
      @Value("${even.plaid.env:sandbox}") String env,
      @Value("${even.plaid.products:transactions}") String products,
      @Value("${even.plaid.redirect-uri:}") String redirectUri) {
    this.mapper = mapper;
    this.clientId = clientId;
    this.secret = secret;
    this.products = products;
    this.redirectUri = redirectUri;
    String base = switch (env.toLowerCase()) {
      case "production" -> "https://production.plaid.com";
      case "development" -> "https://development.plaid.com";
      default -> "https://sandbox.plaid.com";
    };
    this.client = RestClient.builder().baseUrl(base).build();
  }

  @Override
  public LinkTokenResult createLinkToken(String userId, String webhookUrl) {
    ObjectNode body = authBody();
    body.put("client_name", "Even");
    body.put("language", "en");
    body.putArray("country_codes").add("US");
    var productList = body.putArray("products");
    boolean transactions = false;
    for (String product : products.split(",")) {
      String trimmed = product.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      productList.add(trimmed);
      if ("transactions".equals(trimmed)) {
        transactions = true;
      }
    }
    if (transactions) {
      body.putObject("transactions").put("days_requested", 730);
    }
    body.putObject("user").put("client_user_id", userId);
    if (webhookUrl != null && !webhookUrl.isBlank()) {
      body.put("webhook", webhookUrl);
    }
    if (redirectUri != null && !redirectUri.isBlank()) {
      body.put("redirect_uri", redirectUri);
    }
    JsonNode response = post("/link/token/create", body);
    String token = response.path("link_token").asText("");
    if (token.isBlank()) {
      throw new IllegalArgumentException("Plaid did not return a link token");
    }
    return new LinkTokenResult(token, false);
  }

  @Override
  public ExchangeResult exchangePublicToken(String publicToken) {
    ObjectNode body = authBody();
    body.put("public_token", publicToken);
    JsonNode response = post("/item/public_token/exchange", body);
    String accessToken = response.path("access_token").asText("");
    String itemId = response.path("item_id").asText("");
    if (accessToken.isBlank() || itemId.isBlank()) {
      throw new IllegalArgumentException("Plaid did not return an access token");
    }
    return new ExchangeResult(itemId, accessToken, institutionName(accessToken));
  }

  @Override
  public SyncResult syncTransactions(String accessToken, Optional<String> cursor) {
    ObjectNode body = authBody();
    body.put("access_token", accessToken);
    cursor.filter(c -> !c.isBlank()).ifPresent(c -> body.put("cursor", c));
    JsonNode response = post("/transactions/sync", body);
    return new SyncResult(
        response,
        response.path("next_cursor").asText(""),
        response.path("has_more").asBoolean(false));
  }

  @Override
  public boolean isLive() {
    return true;
  }

  private ObjectNode authBody() {
    ObjectNode body = mapper.createObjectNode();
    body.put("client_id", clientId);
    body.put("secret", secret);
    return body;
  }

  private String institutionName(String accessToken) {
    try {
      ObjectNode itemBody = authBody();
      itemBody.put("access_token", accessToken);
      JsonNode item = post("/item/get", itemBody);
      String institutionId = item.path("item").path("institution_id").asText("");
      if (institutionId.isBlank()) {
        return "Plaid";
      }
      ObjectNode lookup = authBody();
      lookup.put("institution_id", institutionId);
      lookup.putArray("country_codes").add("US");
      JsonNode institution = post("/institutions/get_by_id", lookup);
      String name = institution.path("institution").path("name").asText("");
      return name.isBlank() ? "Plaid" : name;
    } catch (RuntimeException ex) {
      return "Plaid";
    }
  }

  private JsonNode post(String path, ObjectNode body) {
    String raw;
    try {
      raw = client.post()
          .uri(path)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body.toString())
          .retrieve()
          .body(String.class);
    } catch (RestClientResponseException ex) {
      throw new IllegalArgumentException(plaidError(ex));
    }
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException("empty Plaid response from " + path);
    }
    try {
      return mapper.readTree(raw);
    } catch (Exception e) {
      throw new IllegalStateException("invalid Plaid response from " + path, e);
    }
  }

  private String plaidError(RestClientResponseException ex) {
    String raw = ex.getResponseBodyAsString();
    try {
      JsonNode node = mapper.readTree(raw);
      String message = node.path("error_message").asText("");
      if (!message.isBlank()) {
        return message;
      }
    } catch (Exception ignored) {
      // Plaid sometimes returns a non-JSON body.
    }
    return raw == null || raw.isBlank() ? "Plaid request failed" : raw;
  }
}
