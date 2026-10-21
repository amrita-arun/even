package dev.even.splits;

import dev.even.auth.CurrentUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
public class SplitController {

  private final SplitService splits;

  public SplitController(SplitService splits) {
    this.splits = splits;
  }

  @PostMapping("/{id}/split")
  public SplitResponse split(
      JwtAuthenticationToken authentication,
      @PathVariable UUID id,
      @RequestBody SplitService.SplitRequest body) {
    CurrentUser user = CurrentUser.from(authentication);
    SplitService.SplitResult result = splits.split(user.id(), id, body);
    return new SplitResponse(
        result.splitId(),
        result.transactionId(),
        result.shares().stream()
            .map(s -> new ShareResponse(s.personId(), s.shareCents()))
            .toList());
  }

  @PostMapping("/{id}/reimburse")
  public SplitResponse reimburse(
      JwtAuthenticationToken authentication,
      @PathVariable UUID id,
      @RequestBody ReimburseRequest body) {
    CurrentUser user = CurrentUser.from(authentication);
    if (body.personId() == null) {
      throw new IllegalArgumentException("personId is required");
    }
    SplitService.SplitResult result = splits.reimburse(user.id(), id, body.personId());
    return new SplitResponse(
        result.splitId(),
        result.transactionId(),
        result.shares().stream()
            .map(s -> new ShareResponse(s.personId(), s.shareCents()))
            .toList());
  }

  public record ReimburseRequest(UUID personId) {}

  public record ShareResponse(UUID personId, long shareCents) {}

  public record SplitResponse(UUID splitId, UUID transactionId, List<ShareResponse> shares) {}
}
