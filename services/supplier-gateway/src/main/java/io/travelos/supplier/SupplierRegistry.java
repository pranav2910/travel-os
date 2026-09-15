package io.travelos.supplier;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.travelos.supplier.AirSupplier.SupplierException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every adapter call goes through its own rate limiter and circuit breaker. A supplier that is down
 * stops being called after the breaker opens (instead of thousands of requests piling onto it) and
 * its failures surface as retryable {@link SupplierException}s the caller can queue or report.
 */
public final class SupplierRegistry {

  private static final Logger log = LoggerFactory.getLogger(SupplierRegistry.class);

  private record Guarded(SupplierAdapter supplier, RateLimiter limiter, CircuitBreaker breaker) {}

  private final Map<String, Guarded> adapters = new LinkedHashMap<>();

  public SupplierRegistry(
      List<? extends SupplierAdapter> suppliers, SupplierProperties properties) {
    for (SupplierAdapter supplier : suppliers) {
      SupplierProperties.Adapter settings = properties.adapter(supplier.provider());
      RateLimiter limiter =
          RateLimiter.of(
              supplier.provider(),
              RateLimiterConfig.custom()
                  .limitForPeriod(settings.rateLimitPerSecond())
                  .limitRefreshPeriod(Duration.ofSeconds(1))
                  .timeoutDuration(Duration.ofMillis(500))
                  .build());
      CircuitBreaker breaker =
          CircuitBreaker.of(
              supplier.provider(),
              CircuitBreakerConfig.custom()
                  .failureRateThreshold(settings.circuitBreaker().failureRateThreshold())
                  .slidingWindowSize(settings.circuitBreaker().slidingWindowSize())
                  .minimumNumberOfCalls(Math.min(10, settings.circuitBreaker().slidingWindowSize()))
                  .waitDurationInOpenState(settings.circuitBreaker().waitInOpenState())
                  .permittedNumberOfCallsInHalfOpenState(3)
                  // Only the supplier's own faults count: a bad offer id is the caller's problem.
                  .recordException(t -> t instanceof SupplierException e ? e.retryable() : true)
                  .build());
      breaker
          .getEventPublisher()
          .onStateTransition(
              e -> log.warn("circuit {} {}", supplier.provider(), e.getStateTransition()));
      adapters.put(supplier.provider(), new Guarded(supplier, limiter, breaker));
    }
  }

  public List<String> providers() {
    return List.copyOf(adapters.keySet());
  }

  /** Providers that sell the given kind, in registration order. */
  public <A extends SupplierAdapter> List<String> providersOf(Class<A> kind) {
    return adapters.values().stream()
        .map(Guarded::supplier)
        .filter(kind::isInstance)
        .map(SupplierAdapter::provider)
        .toList();
  }

  public Optional<SupplierAdapter> find(String provider) {
    return Optional.ofNullable(adapters.get(provider)).map(Guarded::supplier);
  }

  public Optional<AirSupplier> findAir(String provider) {
    return find(provider).filter(AirSupplier.class::isInstance).map(AirSupplier.class::cast);
  }

  /** Runs {@code call} against the named adapter under its limiter and breaker. */
  public <T> T call(String provider, java.util.function.Function<SupplierAdapter, T> call) {
    return call(provider, SupplierAdapter.class, call);
  }

  /**
   * Same, for an adapter of a specific kind: asking a hotel adapter for flights is a caller bug
   * (INVALID_ARGUMENT), not a supplier fault.
   */
  public <A extends SupplierAdapter, T> T call(
      String provider, Class<A> kind, java.util.function.Function<A, T> call) {
    Guarded guarded = adapters.get(provider);
    if (guarded == null) {
      throw new SupplierException("PROVIDER_UNKNOWN", "no adapter for provider " + provider, false);
    }
    if (!kind.isInstance(guarded.supplier())) {
      throw new SupplierException(
          "PROVIDER_KIND_MISMATCH",
          provider + " does not sell " + kind.getSimpleName().replace("Supplier", "").toLowerCase(),
          false);
    }
    Supplier<T> decorated =
        CircuitBreaker.decorateSupplier(
            guarded.breaker(),
            RateLimiter.decorateSupplier(
                guarded.limiter(), () -> call.apply(kind.cast(guarded.supplier()))));
    try {
      return decorated.get();
    } catch (CallNotPermittedException e) {
      throw new SupplierException(
          "CIRCUIT_OPEN", provider + " is unavailable (circuit open)", true);
    } catch (RequestNotPermitted e) {
      throw new SupplierException("RATE_LIMITED", provider + " rate limit reached", true);
    }
  }

  public CircuitBreaker.State circuitState(String provider) {
    Guarded guarded = adapters.get(provider);
    return guarded == null ? CircuitBreaker.State.DISABLED : guarded.breaker().getState();
  }
}
