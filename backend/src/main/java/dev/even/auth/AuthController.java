package dev.even.auth;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final UserRepository users;

  public AuthController(UserRepository users) {
    this.users = users;
  }

  @GetMapping("/me")
  public MeResponse me(JwtAuthenticationToken authentication) {
    CurrentUser current = CurrentUser.from(authentication);
    UserRepository.UserRecord user = users.upsert(current.id(), current.email());
    return new MeResponse(user.id(), user.email());
  }

  public record MeResponse(java.util.UUID id, String email) {}
}
