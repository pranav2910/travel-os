package io.travelos.supplier;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-adapter protection settings, keyed by provider id. */
@ConfigurationProperties(prefix = "travelos.suppliers")
public record SupplierProperties(Map<String, Adapter> adapters) {

  public SupplierProperties {
    adapters = adapters == null ? Map.of() : Map.copyOf(adapters);
  }

  public Adapter adapter(String provider) {
    return adapters.getOrDefault(provider, new Adapter(null, null));
  }

  public record Adapter(Integer rateLimitPerSecond, CircuitBreaker circuitBreaker) {
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
