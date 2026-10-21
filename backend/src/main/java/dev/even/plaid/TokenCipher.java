package dev.even.plaid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM encryption for Plaid access tokens at rest.
 * Key material comes from {@code EVEN_TOKEN_ENCRYPTION_KEY} (any passphrase;
 * hashed to 32 bytes) or a deterministic test fallback.
 */
@Component
public class TokenCipher {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  public TokenCipher(
      @Value("${even.token-encryption-key:even-dev-token-key-change-me}") String keyMaterial) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(keyMaterial.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      this.key = new SecretKeySpec(digest, "AES");
    } catch (Exception e) {
      throw new IllegalStateException("failed to init token cipher", e);
    }
  }

  public String encrypt(String plaintext) {
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
      buffer.put(iv);
      buffer.put(ciphertext);
      return Base64.getEncoder().encodeToString(buffer.array());
    } catch (Exception e) {
      throw new IllegalStateException("encrypt failed", e);
    }
  }

  public String decrypt(String encoded) {
    try {
      byte[] all = Base64.getDecoder().decode(encoded);
      ByteBuffer buffer = ByteBuffer.wrap(all);
      byte[] iv = new byte[IV_BYTES];
      buffer.get(iv);
      byte[] ciphertext = new byte[buffer.remaining()];
      buffer.get(ciphertext);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("decrypt failed", e);
    }
  }
}
