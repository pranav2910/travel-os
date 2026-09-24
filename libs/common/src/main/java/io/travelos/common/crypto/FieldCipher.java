package io.travelos.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Field-level encryption for the few values the platform must hold but must never show or log by
 * accident (travel-document numbers, dates of birth, phone numbers, loyalty numbers, emergency
 * contacts). AES-256-GCM with a random 96-bit nonce per value; the ciphertext is stored as {@code
 * v1:<base64(nonce || ciphertext || tag)>}, so a later key or algorithm can coexist with old rows.
 *
 * <p>The key comes from the established secrets mechanism (an environment variable resolved from
 * the platform's secret store; never from a file in the repository). Rotation: decrypt with the old
 * key, re-encrypt with the new, one row at a time; the version prefix tells which rows are done.
 */
public final class FieldCipher {

  private static final String PREFIX = "v1:";
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKey key;

  private FieldCipher(SecretKey key) {
    this.key = key;
  }

  /** From a base64-encoded 32-byte key. */
  public static FieldCipher fromBase64Key(String base64) {
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(base64.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("field key is not base64", e);
    }
    if (raw.length != 32) {
      throw new IllegalArgumentException("field key must be 32 bytes (AES-256), got " + raw.length);
    }
    return new FieldCipher(new SecretKeySpec(raw, "AES"));
  }

  public @Nullable String encrypt(@Nullable String plaintext) {
    if (plaintext == null) {
      return null;
    }
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[nonce.length + ct.length];
      System.arraycopy(nonce, 0, out, 0, nonce.length);
      System.arraycopy(ct, 0, out, nonce.length, ct.length);
      return PREFIX + Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("field encryption failed", e);
    }
  }

  public @Nullable String decrypt(@Nullable String stored) {
    if (stored == null) {
      return null;
    }
    if (!stored.startsWith(PREFIX)) {
      throw new IllegalArgumentException("not a v1 field ciphertext");
    }
    try {
      byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, NONCE_BYTES));
      byte[] pt = cipher.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES);
      return new String(pt, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("field decryption failed (wrong key or damaged value)", e);
    }
  }

  /** What may be shown or logged of a secret value: its last four characters. */
  public static @Nullable String last4(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String v = value.strip();
    return v.length() <= 4 ? "****" : "****" + v.substring(v.length() - 4);
  }
}
