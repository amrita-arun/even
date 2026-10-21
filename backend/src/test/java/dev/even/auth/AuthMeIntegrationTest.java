package dev.even.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(AuthMeIntegrationTest.TestJwtConfig.class)
class AuthMeIntegrationTest extends PostgresIntegrationTest {

  private static RSAKey rsaKey;

  @Autowired
  MockMvc mockMvc;

  @Autowired
  JdbcTemplate jdbc;

  @BeforeAll
  static void generateKey() throws Exception {
    rsaKey = new RSAKeyGenerator(2048).keyID("test").generate();
  }

  @Test
  void meUpsertsUserAndReturnsProfile() throws Exception {
    UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
    String token = signedToken(id, "owner@example.com");

    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.email").value("owner@example.com"));

    Integer count = jdbc.queryForObject(
        "select count(*) from users where id = ? and email = ?",
        Integer.class,
        id,
        "owner@example.com");
    assertThat(count).isEqualTo(1);

    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
  }

  @Test
  void meWithTamperedTokenIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer not-a-real-jwt"))
        .andExpect(status().isUnauthorized());
  }

  private static String signedToken(UUID subject, String email) throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject(subject.toString())
        .claim("email", email)
        .issueTime(new Date())
        .expirationTime(new Date(System.currentTimeMillis() + 60_000))
        .build();
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
        claims);
    jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
    return jwt.serialize();
  }

  @TestConfiguration
  static class TestJwtConfig {
    @Bean
    @Primary
    JwtDecoder testJwtDecoder() throws Exception {
      if (rsaKey == null) {
        rsaKey = new RSAKeyGenerator(2048).keyID("test").generate();
      }
      return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
    }
  }
}
