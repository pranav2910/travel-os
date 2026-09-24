package io.travelos.supplier.live;

import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * travelos.integrations.*: credentials and endpoints of real suppliers. Every value comes from the
 * secrets mechanism (env → Secret / Secrets Manager); nothing here has a default that reaches a
 * real system. An adapter is registered only when its credentials are present.
 */
@ConfigurationProperties(prefix = "travelos.integrations")
public record LiveIntegrationProperties(@Nullable Duffel duffel, @Nullable Hotelbeds hotelbeds) {

  public LiveIntegrationProperties {
    duffel = duffel == null ? new Duffel(null, null, null, null) : duffel;
    hotelbeds = hotelbeds == null ? new Hotelbeds(null, null, null, null, null) : hotelbeds;
  }

  /**
   * @param accessToken Duffel access token (test tokens start with {@code duffel_test_})
   * @param baseUrl {@code https://api.duffel.com}; the same host serves test and live tokens
   * @param privateFares corporate codes per airline IATA code, for negotiated fares
   */
  public record Duffel(
      @Nullable String accessToken,
      @Nullable String baseUrl,
      @Nullable Duration timeout,
      @Nullable Map<String, String> privateFares) {
    public Duffel {
      baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.duffel.com" : baseUrl;
      timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
      privateFares = privateFares == null ? Map.of() : Map.copyOf(privateFares);
    }

    public boolean configured() {
      return accessToken != null && !accessToken.isBlank();
    }

    /** Test tokens book nothing real; a live token spends money. Told apart, never assumed. */
    public boolean testEnvironment() {
      return accessToken != null && accessToken.startsWith("duffel_test_");
    }
  }

  /**
   * @param apiKey APItude API key
   * @param secret APItude shared secret (signs every request)
   * @param baseUrl {@code https://api.test.hotelbeds.com} (test) or {@code
   *     https://api.hotelbeds.com}
   */
  public record Hotelbeds(
      @Nullable String apiKey,
      @Nullable String secret,
      @Nullable String baseUrl,
      @Nullable Duration timeout,
      @Nullable Integer radiusKm) {
    public Hotelbeds {
      baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.test.hotelbeds.com" : baseUrl;
      timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
      radiusKm = radiusKm == null ? 25 : radiusKm;
    }

    public boolean configured() {
      return apiKey != null && !apiKey.isBlank() && secret != null && !secret.isBlank();
    }

    public boolean testEnvironment() {
      return baseUrl.contains("api.test.hotelbeds.com");
    }
  }
}
