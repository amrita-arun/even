package dev.even.ingestion;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.even.accounts.AccountService;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(UserIsolationTest.TestJwtConfig.class)
class UserIsolationTest extends PostgresIntegrationTest {

  private static RSAKey rsaKey;

  @Autowired
  MockMvc mockMvc;

  @Autowired
  AccountService accounts;

  @Autowired
  IngestService ingest;

  @Autowired
  CsvConnector csvConnector;

  @Autowired
  JdbcTemplate jdbc;

  UUID userA;
  UUID userB;
  UUID accountA;

  @BeforeAll
  static void generateKey() throws Exception {
    rsaKey = new RSAKeyGenerator(2048).keyID("iso").generate();
  }

  @BeforeEach
  void setUp() throws Exception {
    userA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    userB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    accountA = accounts.create(userA, "a@example.com", "A Card", "credit", "USD").id();
    try (var in = new ClassPathResource("fixtures/statement.csv").getInputStream()) {
      ingest.apply(accountA, userA, csvConnector.fetch(accountA, in));
    }
  }

  @Test
  void userBSeesEmptyTransactionList() throws Exception {
    mockMvc.perform(get("/api/transactions")
            .header("Authorization", "Bearer " + signedToken(userB, "b@example.com")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void userBCannotImportIntoUserAAccount() throws Exception {
    byte[] csv = new ClassPathResource("fixtures/statement.csv").getInputStream().readAllBytes();
    MockMultipartFile file = new MockMultipartFile(
        "file", "statement.csv", "text/csv", csv);

    mockMvc.perform(multipart("/api/imports/csv")
            .file(file)
            .param("account_id", accountA.toString())
            .header("Authorization", "Bearer " + signedToken(userB, "b@example.com"))
            .contentType(MediaType.MULTIPART_FORM_DATA))
        .andExpect(status().isNotFound());

    assertThat(jdbc.queryForObject(
            "select count(*) from transactions where account_id = ?", Integer.class, accountA))
        .isEqualTo(3);
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
        rsaKey = new RSAKeyGenerator(2048).keyID("iso").generate();
      }
      return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
    }
  }
}
