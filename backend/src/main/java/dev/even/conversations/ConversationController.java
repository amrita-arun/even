package dev.even.conversations;

import dev.even.auth.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

  private final ConversationService conversations;
  private final SimulatedMessageChannel simulated;
  private final String bridgeSecret;

  public ConversationController(
      ConversationService conversations,
      SimulatedMessageChannel simulated,
      @Value("${even.bridge.secret:}") String bridgeSecret) {
    this.conversations = conversations;
    this.simulated = simulated;
    this.bridgeSecret = bridgeSecret == null ? "" : bridgeSecret;
  }

  @GetMapping
  public ConversationService.ConversationView current(JwtAuthenticationToken authentication) {
    return conversations.current(CurrentUser.from(authentication).id());
  }

  @PostMapping("/messages")
  public ConversationService.Turn send(
      JwtAuthenticationToken authentication, @RequestBody TextRequest body) {
    return simulated.deliver(CurrentUser.from(authentication).id(), body.text(), null);
  }

  @PutMapping("/handle")
  public ConversationService.ConversationView handle(
      JwtAuthenticationToken authentication, @RequestBody HandleRequest body) {
    CurrentUser user = CurrentUser.from(authentication);
    conversations.setHandle(user.id(), body.handle());
    return conversations.current(user.id());
  }

  @PostMapping("/inbound")
  public InboundResponse inbound(
      @RequestHeader(value = "X-Even-Bridge-Secret", required = false) String secret,
      @RequestBody InboundRequest body) {
    requireBridge(secret);
    ConversationService.Turn turn = conversations.inbound(body.from(), body.text(), body.externalId());
    return new InboundResponse(turn.ignored(), turn.reply());
  }

  @GetMapping("/outbound")
  public List<ConversationService.Outbound> outbound(
      @RequestHeader(value = "X-Even-Bridge-Secret", required = false) String secret) {
    requireBridge(secret);
    return conversations.pendingOutbound();
  }

  @PostMapping("/outbound/{id}/sent")
  public void sent(
      @RequestHeader(value = "X-Even-Bridge-Secret", required = false) String secret,
      @PathVariable UUID id) {
    requireBridge(secret);
    conversations.markSent(id);
  }

  private void requireBridge(String provided) {
    byte[] expected = bridgeSecret.getBytes(StandardCharsets.UTF_8);
    byte[] actual = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
    if (bridgeSecret.isBlank() || !MessageDigest.isEqual(expected, actual)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  public record TextRequest(String text) {}

  public record HandleRequest(String handle) {}

  public record InboundRequest(String from, String text, String externalId) {}

  public record InboundResponse(boolean ignored, String reply) {}
}
