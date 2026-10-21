package dev.even.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.Map;

@TestConfiguration
public class TestJwtDecoderConfig {

  @Bean
  @org.springframework.context.annotation.Primary
  JwtDecoder testJwtDecoder() {
    return token -> Jwt.withTokenValue(token)
        .header("alg", "none")
        .subject("00000000-0000-0000-0000-000000000000")
        .claims(claims -> claims.putAll(Map.of("email", "unused@example.com")))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .build();
  }
}
