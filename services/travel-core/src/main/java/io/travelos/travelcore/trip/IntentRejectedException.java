package io.travelos.travelcore.trip;

/**
 * A request the platform refuses before any planning, with a stable machine-readable code the API
 * returns as the problem's {@code code} (never an exception class name). Extends {@link
 * IllegalArgumentException} so every caller that already treats a malformed intent as 422 keeps
 * working.
 */
public class IntentRejectedException extends IllegalArgumentException {
  private final String code;

  public IntentRejectedException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
