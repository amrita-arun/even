package dev.even.settlement;

import dev.even.auth.CurrentUser;
import dev.even.conversations.ConversationService;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

  private final SettlementService settlements;
  private final ConversationService conversations;

  public SettlementController(SettlementService settlements, ConversationService conversations) {
    this.settlements = settlements;
    this.conversations = conversations;
  }

  @GetMapping("/draft")
  public DraftResponse draft(
      JwtAuthenticationToken authentication,
      @RequestParam UUID personId,
      @RequestParam String channel,
      @RequestParam(required = false) String note) {
    CurrentUser user = CurrentUser.from(authentication);
    SettlementDrafts.Draft draft = settlements.draft(user.id(), personId, channel, note);
    return new DraftResponse(draft.channel(), draft.url(), draft.text());
  }

  @GetMapping
  public List<SettlementResponse> list(
      JwtAuthenticationToken authentication,
      @RequestParam(required = false) String status) {
    CurrentUser user = CurrentUser.from(authentication);
    return settlements.list(user.id(), status).stream().map(SettlementResponse::from).toList();
  }

  @PostMapping("/suggestions/refresh")
  public List<SettlementResponse> refreshSuggestions(JwtAuthenticationToken authentication) {
    CurrentUser user = CurrentUser.from(authentication);
    return settlements.refreshSuggestions(user.id()).stream()
        .map(SettlementResponse::from)
        .toList();
  }

  @PostMapping("/{id}/confirm")
  public SettlementResponse confirm(
      JwtAuthenticationToken authentication,
      @PathVariable UUID id) {
    CurrentUser user = CurrentUser.from(authentication);
    SettlementResponse response = SettlementResponse.from(settlements.confirm(user.id(), id));
    conversations.onSettlementDecided(user.id(), id, true);
    return response;
  }

  @PostMapping("/{id}/reject")
  public SettlementResponse reject(
      JwtAuthenticationToken authentication,
      @PathVariable UUID id) {
    CurrentUser user = CurrentUser.from(authentication);
    SettlementResponse response = SettlementResponse.from(settlements.reject(user.id(), id));
    conversations.onSettlementDecided(user.id(), id, false);
    return response;
  }

  @PostMapping("/mark-paid")
  public SettlementResponse markPaid(
      JwtAuthenticationToken authentication,
      @RequestBody MarkPaidRequest body) {
    CurrentUser user = CurrentUser.from(authentication);
    if (body.personId() == null) {
      throw new IllegalArgumentException("personId is required");
    }
    return SettlementResponse.from(
        settlements.markPaid(user.id(), body.personId(), body.amountCents()));
  }

  public record DraftResponse(String channel, String url, String text) {}

  public record MarkPaidRequest(UUID personId, Long amountCents) {}

  public record SettlementResponse(
      UUID id,
      UUID personId,
      String displayName,
      long amountCents,
      String status,
      UUID sourceTransactionId,
      String channel,
      String draftText) {
    static SettlementResponse from(SettlementService.SettlementRecord row) {
      return new SettlementResponse(
          row.id(),
          row.personId(),
          row.displayName(),
          row.amountCents(),
          row.status(),
          row.sourceTransactionId(),
          row.channel(),
          row.draftText());
    }
  }
}
