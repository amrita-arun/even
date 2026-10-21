package dev.even.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class JwtConfig {

  private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

  /**
   * Prefer JWKS. Supabase's newer asymmetric signing keys use ES256, which
   * Spring does not enable by default on {@code withJwkSetUri} (RS256 only).
   * Fall back to the legacy HS256 JWT secret only when JWKS URL is unset.
   */
  @Bean
  @ConditionalOnMissingBean(JwtDecoder.class)
  JwtDecoder jwtDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
      @Value("${even.supabase.issuer}") String issuer,
      @Value("${even.supabase.jwt-secret:}") String jwtSecret) {
    NimbusJwtDecoder decoder;
    if (StringUtils.hasText(jwkSetUri)) {
      log.info("JWT verification: JWKS at {} (ES256/RS256)", jwkSetUri);
      decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
          .jwsAlgorithms(algs -> {
            algs.add(SignatureAlgorithm.ES256);
            algs.add(SignatureAlgorithm.ES384);
            algs.add(SignatureAlgorithm.ES512);
            algs.add(SignatureAlgorithm.RS256);
          })
          .build();
    } else if (StringUtils.hasText(jwtSecret)) {
      log.info("JWT verification: legacy HS256 JWT secret");
      SecretKeySpec key = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
      decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    } else {
      throw new IllegalStateException(
          "Set SUPABASE_JWKS_URL (preferred) or SUPABASE_JWT_SECRET (legacy HS256)");
    }

    OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuer);
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer));
    return decoder;
  }
}
