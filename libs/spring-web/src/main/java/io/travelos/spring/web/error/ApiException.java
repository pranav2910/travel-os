package io.travelos.spring.web.error;

import org.springframework.http.HttpStatus;

/**
 * A failure the caller can act on, rendered as RFC 9457 problem detail with a stable machine {@code
 * code}. Subclasses exist for the common cases; anything else is a 500 and is logged.
 */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  /**
   * 404 for anything the caller may not see. Deliberately also used for cross-tenant and cross-user
   * access: a 403 would confirm that the resource exists.
   */
  public static final class NotFound extends ApiException {
    public NotFound(String what, String id) {
      super(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " not found");
    }
  }

  public static final class Forbidden extends ApiException {
    public Forbidden(String code, String message) {
      super(HttpStatus.FORBIDDEN, code, message);
    }
  }

  public static final class Conflict extends ApiException {
    public Conflict(String code, String message) {
      super(HttpStatus.CONFLICT, code, message);
    }
  }

  public static final class Unprocessable extends ApiException {
    public Unprocessable(String code, String message) {
      super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
  }
}
