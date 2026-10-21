package dev.even.flags;

import dev.even.auth.CurrentUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/flags")
public class FlagController {

  private final FlagService flags;

  public FlagController(FlagService flags) {
    this.flags = flags;
  }

  @GetMapping
  public List<FlagService.FlagRecord> list(
      JwtAuthenticationToken authentication,
      @RequestParam(required = false) String status) {
    CurrentUser user = CurrentUser.from(authentication);
    return flags.list(user.id(), status);
  }

  @PatchMapping("/{id}")
  public FlagService.FlagRecord update(
      JwtAuthenticationToken authentication,
      @PathVariable UUID id,
      @RequestBody UpdateFlagRequest body) {
    CurrentUser user = CurrentUser.from(authentication);
    return flags.updateStatus(user.id(), id, body.status());
  }

  public record UpdateFlagRequest(String status) {}
}
