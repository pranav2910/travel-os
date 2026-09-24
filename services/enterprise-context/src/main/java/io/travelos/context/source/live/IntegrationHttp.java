package io.travelos.context.source.live;

import io.travelos.context.source.SourceException;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What every live enterprise adapter shares: JSON in and out, and the same shape of failure. A
 * 401/403 is a revoked or expired credential (final: the connector needs a person); 429 and 5xx are
 * "ask again later"; anything else is the provider's own answer, final.
 */
public final class IntegrationHttp {
  public static final JsonMapper JSON = JsonMapper.builder().build();

  private IntegrationHttp() {}

  public static JsonNode parse(@Nullable String body) {
    return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
  }

  public static SourceException failure(String provider, RuntimeException e) {
    if (e instanceof RestClientResponseException r) {
      int status = r.getStatusCode().value();
      if (status == 401 || status == 403) {
        return new SourceException(
            "CREDENTIALS_REVOKED",
            provider + " refused the credential (" + status + "); reconnect it",
            false);
      }
      if (status == 429) {
        return new SourceException(
            "RATE_LIMITED", provider + " rate limit reached; back off", true);
      }
      if (status >= 500) {
        return new SourceException(
            "SOURCE_UNAVAILABLE", provider + " answered " + status + "; retry later", true);
      }
      String detail = r.getResponseBodyAsString();
      return new SourceException(
          "PROVIDER_ERROR",
          provider + " answered " + status + (detail.isBlank() ? "" : ": " + abbreviate(detail)),
          false);
    }
    if (e instanceof ResourceAccessException) {
      return new SourceException(
          "SOURCE_UNAVAILABLE", provider + " unreachable: " + e.getMessage(), true);
    }
    if (e instanceof SourceException s) {
      return s;
    }
    return new SourceException("PROVIDER_ERROR", provider + ": " + e.getMessage(), false);
  }

  /**
   * Providers write instants three ways: RFC 3339 ({@code ...Z}, {@code ...+02:00}), Salesforce's
   * {@code ...+0000}, and Graph's zone-less {@code ...T09:00:00.0000000} (UTC when asked for it).
   */
  public static @Nullable Instant instant(@Nullable String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    String s = iso.trim();
    java.util.regex.Matcher compact =
        java.util.regex.Pattern.compile("([+-]\\d{2})(\\d{2})$").matcher(s);
    if (compact.find()) {
      s = s.substring(0, compact.start()) + compact.group(1) + ":" + compact.group(2);
    }
    boolean zoned = s.endsWith("Z") || s.matches(".*[+-]\\d{2}:\\d{2}$");
    try {
      return java.time.OffsetDateTime.parse(zoned ? s : s + "Z").toInstant();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String abbreviate(String s) {
    return s.length() > 200 ? s.substring(0, 200) + "..." : s;
  }
}
