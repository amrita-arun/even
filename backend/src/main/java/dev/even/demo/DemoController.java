package dev.even.demo;

import dev.even.auth.CurrentUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

  private final DemoSeedService seed;
  private final StatementResetService reset;

  public DemoController(DemoSeedService seed, StatementResetService reset) {
    this.seed = seed;
    this.reset = reset;
  }

  @PostMapping("/seed")
  public SeedResponse seed(JwtAuthenticationToken authentication) {
    CurrentUser user = CurrentUser.from(authentication);
    DemoSeedService.SeedResult result = seed.seed(user.id(), user.email());
    return new SeedResponse(result.alreadySeeded(), result.accountName());
  }

  @PostMapping("/reset")
  public ResetResponse reset(JwtAuthenticationToken authentication) {
    CurrentUser user = CurrentUser.from(authentication);
    reset.reset(user.id());
    return new ResetResponse(true);
  }

  public record SeedResponse(boolean alreadySeeded, String accountName) {}

  public record ResetResponse(boolean reset) {}
}
