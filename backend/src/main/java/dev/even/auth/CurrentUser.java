package dev.even.auth;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

public record CurrentUser(UUID id, String email) {

  public static CurrentUser from(JwtAuthenticationToken authentication) {
    Jwt jwt = authentication.getToken();
    UUID id = UUID.fromString(jwt.getSubject());
    String email = jwt.getClaimAsString("email");
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException("JWT is missing email claim");
    }
    return new CurrentUser(id, email);
  }
}
