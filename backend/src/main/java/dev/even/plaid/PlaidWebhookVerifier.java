package dev.even.plaid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Checks the {@code Plaid-Verification} header against {@code even.plaid.webhook-secret}.
 * Fixture mode allows an empty secret (no header required). Live mode requires a match.
 */
@Component
public class PlaidWebhookVerifier {

  private final String secret;
  private final boolean live;

  public PlaidWebhookVerifier(
      @Value("${even.plaid.webhook-secret:}") String secret,
      @Value("${even.plaid.live:false}") boolean live) {
    this.secret = secret == null ? "" : secret;
    this.live = live;
  }

  public boolean accept(String verificationHeader) {
    if (secret.isBlank()) {
      return !live;
    }
    if (verificationHeader == null || verificationHeader.isBlank()) {
      return false;
    }
    return constantTimeEquals(secret, verificationHeader.trim());
  }

  private static boolean constantTimeEquals(String expected, String actual) {
    byte[] a = expected.getBytes(StandardCharsets.UTF_8);
    byte[] b = actual.getBytes(StandardCharsets.UTF_8);
    if (a.length != b.length) {
      return MessageDigest.isEqual(a, a) && false;
    }
    return MessageDigest.isEqual(a, b);
  }
}
