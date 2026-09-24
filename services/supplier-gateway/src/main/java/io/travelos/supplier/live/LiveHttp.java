package io.travelos.supplier.live;

import io.travelos.supplier.AirSupplier.SupplierException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What every live client shares: JSON in and out, and the same shape of failure. */
public final class LiveHttp {
  public static final JsonMapper JSON = JsonMapper.builder().build();

  private LiveHttp() {}

  public static JsonNode parse(String body) {
    if (body == null || body.isBlank()) {
      return JSON.createObjectNode();
    }
    return JSON.readTree(body);
  }

  /**
   * HTTP trouble as a supplier failure: 5xx and transport errors are retryable (the answer may have
   * been lost, which the ledger records as UNKNOWN); 4xx are final, with the supplier's own code
   * when it gives one.
   */
  public static SupplierException failure(String provider, RuntimeException e) {
    if (e instanceof RestClientResponseException r) {
      int status = r.getStatusCode().value();
      String body = r.getResponseBodyAsString();
      String code = "HTTP_" + status;
      String message = provider + " answered " + status;
      try {
        JsonNode errors = parse(body).path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
          JsonNode first = errors.get(0);
          if (first.hasNonNull("code")) {
            code = first.get("code").asString();
          }
          if (first.hasNonNull("message")) {
            message = provider + ": " + first.get("message").asString();
          } else if (first.hasNonNull("title")) {
            message = provider + ": " + first.get("title").asString();
          }
        } else {
          JsonNode error = parse(body).path("error");
          if (error.hasNonNull("code")) {
            code = error.get("code").asString();
          }
          if (error.hasNonNull("message")) {
            message = provider + ": " + error.get("message").asString();
          }
        }
      } catch (RuntimeException ignored) {
        // a non-JSON error page; the status code is what we know
      }
      if (status == 429) {
        return new SupplierException("RATE_LIMITED", message, true);
      }
      if (status >= 500) {
        return new SupplierException("UPSTREAM_" + status, message, true);
      }
      if (status == 401 || status == 403) {
        return new SupplierException("CREDENTIALS_REJECTED", message, false);
      }
      return new SupplierException(code.toUpperCase(), message, false);
    }
    if (e instanceof ResourceAccessException) {
      return new SupplierException("TRANSPORT", provider + ": " + e.getMessage(), true);
    }
    if (e instanceof SupplierException s) {
      return s;
    }
    return new SupplierException("UPSTREAM_ERROR", provider + ": " + e.getMessage(), true);
  }

  public static String money(long minor) {
    return (minor / 100) + "." + String.format("%02d", Math.abs(minor % 100));
  }

  /** "475.00" → 47500; supplier amounts are decimal strings, never floats in our code. */
  public static long minor(String amount) {
    java.math.BigDecimal d = new java.math.BigDecimal(amount.trim());
    return d.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
  }
}
