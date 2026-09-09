package io.travelos.common.idempotency;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Required on every transaction command. Format {@code <scope>:<COMMAND>:<n>}, e.g. {@code
 * trip_01J...:CREATE-ORDER:1}. Twenty retries of the same key must produce one airline order; the
 * owning table enforces this with {@code UNIQUE(idempotency_key)}.
 *
 * <p>{@code n} is the logical attempt: bump it only when the business genuinely wants a NEW
 * transaction (a re-plan after cancellation), never for network retries.
 */
public record IdempotencyKey(String value) {

  private static final Pattern FORMAT =
      Pattern.compile("^([A-Za-z0-9_-]{1,64}):([A-Z][A-Z0-9-]{0,63}):([0-9]{1,9})$");

  public IdempotencyKey {
    Objects.requireNonNull(value, "idempotency key");
    if (!FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "idempotency key must look like <scope>:<COMMAND>:<n> but got: " + value);
    }
  }

  public static IdempotencyKey of(String scope, String command, int attempt) {
    return new IdempotencyKey(scope + ":" + command + ":" + attempt);
  }

  public static IdempotencyKey parse(String value) {
    return new IdempotencyKey(value);
  }

  public String scope() {
    return group(1);
  }

  public String command() {
    return group(2);
  }

  public int attempt() {
    return Integer.parseInt(group(3));
  }

  private String group(int index) {
    Matcher matcher = FORMAT.matcher(value);
    if (!matcher.matches()) {
      throw new IllegalStateException("validated key no longer matches: " + value);
    }
    return matcher.group(index);
  }

  @Override
  public String toString() {
    return value;
  }
}
