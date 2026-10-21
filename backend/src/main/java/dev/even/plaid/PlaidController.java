package dev.even.plaid;

import dev.even.accounts.AccountService;
import dev.even.auth.CurrentUser;
import dev.even.auth.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/plaid")
public class PlaidController {

  private final PlaidItemService items;
  private final PlaidSyncService sync;
  private final PlaidWebhookVerifier webhooks;
  private final AccountService accounts;
  private final UserRepository users;
  private final String webhookUrl;

  public PlaidController(
      PlaidItemService items,
      PlaidSyncService sync,
      PlaidWebhookVerifier webhooks,
      AccountService accounts,
      UserRepository users,
      @Value("${even.plaid.webhook-url:}") String webhookUrl) {
    this.items = items;
    this.sync = sync;
    this.webhooks = webhooks;
    this.accounts = accounts;
    this.users = users;
    this.webhookUrl = webhookUrl;
  }

  @GetMapping("/mode")
  public ModeResponse mode() {
    return new ModeResponse(!items.live());
  }

  @GetMapping("/items")
  public List<PlaidItemService.ItemStatus> items(JwtAuthenticationToken authentication) {
    CurrentUser user = CurrentUser.from(authentication);
    return items.listForUser(user.id());
  }

  @DeleteMapping("/items/{id}")
  public void disconnect(
      JwtAuthenticationToken authentication, @PathVariable UUID id) {
    CurrentUser user = CurrentUser.from(authentication);
    items.disconnect(user.id(), id);
  }

  @PostMapping("/link-token")
  public LinkTokenResponse linkToken(JwtAuthenticationToken authentication) {
    CurrentUser user = CurrentUser.from(authentication);
    users.upsert(user.id(), user.email());
    PlaidApiClient.LinkTokenResult result = items.createLinkToken(user.id(), webhookUrl);
    return new LinkTokenResponse(result.linkToken(), result.fixtureMode());
  }

  @PostMapping("/exchange")
  public ExchangeResponse exchange(
      JwtAuthenticationToken authentication,
      @RequestBody ExchangeRequest body) {
    CurrentUser user = CurrentUser.from(authentication);
    users.upsert(user.id(), user.email());
    if (body.publicToken() == null || body.publicToken().isBlank()) {
      throw new IllegalArgumentException("publicToken is required");
    }
    List<PlaidItemService.PlaidAccountLink> selected =
        body.accounts() == null ? List.of() : body.accounts();
    if (body.accountId() == null && selected.isEmpty()) {
      throw new IllegalArgumentException("accountId is required");
    }
    if (body.accountId() != null) {
      accounts.requireOwned(body.accountId(), user.id());
    }
    PlaidItemService.LinkedItem linked = items.exchangeAndStore(
        user.id(),
        user.email(),
        body.publicToken().trim(),
        body.accountId(),
        body.institutionName(),
        selected);
    return new ExchangeResponse(
        linked.id(),
        linked.itemId(),
        linked.institutionName(),
        linked.live(),
        linked.accountIds());
  }

  @PostMapping("/sync")
  public SyncResponse sync(
      JwtAuthenticationToken authentication,
      @RequestBody SyncRequest body) {
    CurrentUser user = CurrentUser.from(authentication);
    if (body.accountId() == null) {
      throw new IllegalArgumentException("accountId is required");
    }
    accounts.requireOwned(body.accountId(), user.id());
    PlaidSyncService.SyncSummary summary = sync.syncAccount(user.id(), body.accountId());
    return new SyncResponse(summary.pages(), summary.imported(), summary.cursor());
  }

  @PostMapping("/webhooks")
  public Map<String, Object> webhook(
      @RequestHeader(value = "Plaid-Verification", required = false) String verification,
      @RequestBody WebhookBody body) {
    if (!webhooks.accept(verification)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid webhook signature");
    }
    if (body.itemId() == null || body.itemId().isBlank()) {
      throw new IllegalArgumentException("item_id is required");
    }
    List<PlaidItemService.LinkedAccount> linked = items.findAccountsByItemId(body.itemId());
    List<SyncResponse> results = new ArrayList<>();
    for (PlaidItemService.LinkedAccount account : linked) {
      PlaidSyncService.SyncSummary summary =
          sync.syncAccount(account.userId(), account.accountId());
      results.add(new SyncResponse(summary.pages(), summary.imported(), summary.cursor()));
    }
    return Map.of(
        "ok", true,
        "webhookType", body.webhookType() == null ? "" : body.webhookType(),
        "synced", results.size());
  }

  public record ModeResponse(boolean fixtureMode) {}

  public record LinkTokenResponse(String linkToken, boolean fixtureMode) {}

  public record ExchangeRequest(
      UUID accountId,
      String publicToken,
      String institutionName,
      List<PlaidItemService.PlaidAccountLink> accounts) {}

  public record ExchangeResponse(
      UUID plaidItemId,
      String itemId,
      String institutionName,
      boolean live,
      List<UUID> accountIds) {}

  public record SyncRequest(UUID accountId) {}

  public record SyncResponse(int pages, int imported, String cursor) {}

  public record WebhookBody(
      String webhook_type,
      String webhook_code,
      String item_id) {
    String webhookType() {
      return webhook_type;
    }

    String itemId() {
      return item_id;
    }
  }
}
