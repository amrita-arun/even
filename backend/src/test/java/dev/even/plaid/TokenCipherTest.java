package dev.even.plaid;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCipherTest {

  @Test
  void roundTripsAccessToken() {
    TokenCipher cipher = new TokenCipher("unit-test-key");
    String encrypted = cipher.encrypt("access-sandbox-abc");
    assertThat(encrypted).isNotEqualTo("access-sandbox-abc");
    assertThat(cipher.decrypt(encrypted)).isEqualTo("access-sandbox-abc");
  }

  @Test
  void differentIvsProduceDifferentCiphertext() {
    TokenCipher cipher = new TokenCipher("unit-test-key");
    String a = cipher.encrypt("same");
    String b = cipher.encrypt("same");
    assertThat(a).isNotEqualTo(b);
    assertThat(cipher.decrypt(a)).isEqualTo(cipher.decrypt(b));
  }
}
