package io.travelos.supplier;

import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-adapter protection settings, keyed by provider id. */
@ConfigurationProperties(prefix = "travelos")
public record SupplierProperties(Map<String, Adapter> suppliers) {

  public SupplierProperties {
    suppliers = suppliers == null ? Map.of() : Map.copyOf(suppliers);
  }

  /** Settings for one adapter (travelos.suppliers.<provider>.*), defaults when unconfigured. */
  public Adapter adapter(String provider) {
    return suppliers.getOrDefault(provider, new Adapter(null, null, null));
  }

  /**
   * @param webhookSecret shared secret the supplier signs its notices with (HMAC-SHA256). Null =
   *     this supplier sends no notices, and its webhook path answers 403.
   */
  public record Adapter(
      Integer rateLimitPerSecond, CircuitBreaker circuitBreaker, @Nullable String webhookSecret) {
    public Adapter {
      rateLimitPerSecond = rateLimitPerSecond == null ? 20 : rateLimitPerSecond;
      circuitBreaker =
          circuitBreaker == null ? new CircuitBreaker(null, null, null) : circuitBreaker;
    }
  }

  public record CircuitBreaker(
      Float failureRateThreshold, Integer slidingWindowSize, Duration waitInOpenState) {
    public CircuitBreaker {
      failureRateThreshold = failureRateThreshold == null ? 50f : failureRateThreshold;
      slidingWindowSize = slidingWindowSize == null ? 20 : slidingWindowSize;
      waitInOpenState = waitInOpenState == null ? Duration.ofSeconds(30) : waitInOpenState;
    }
  }
}
