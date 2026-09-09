package io.travelos.common.tenant;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Tenant identifier: an opaque lowercase slug ({@code acme}), not a UUID. It appears in JWT claims,
 * cache keys, Kafka headers and log lines, so it has to be short and human-readable. Every business
 * object carries one; every query filters by one.
 */
public record TenantId(String value) {

  private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

  public TenantId {
    Objects.requireNonNull(value, "tenant id");
    if (!SLUG.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "tenant id must be a lowercase slug of 1-63 [a-z0-9-] characters: " + value);
    }
  }

  public static TenantId of(String value) {
    return new TenantId(value);
  }

  /**
   * Tenant-scoped cache key: {@code tenant:acme:trip:trip_01J...}. Never cache without the tenant.
   */
  public String cacheKey(String... parts) {
    return "tenant:" + value + ":" + String.join(":", parts);
  }

  @Override
  public String toString() {
    return value;
  }
}
