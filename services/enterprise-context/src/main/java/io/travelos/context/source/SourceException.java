package io.travelos.context.source;

/** A source could not answer. Retryable means "ask again later" (outage, rate limit). */
public class SourceException extends RuntimeException {
  private final String code;
  private final boolean retryable;

  public SourceException(String code, String message, boolean retryable) {
    super(message);
    this.code = code;
    this.retryable = retryable;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}
