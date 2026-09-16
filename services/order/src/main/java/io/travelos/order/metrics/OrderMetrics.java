package io.travelos.order.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.travelos.order.store.ExposureRepository;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Component-level booking metrics (Slice 3 carry-over). Every label is bounded: the component type
 * is one of AIR / HOTEL / GROUND / OTHER and the outcome is a fixed vocabulary. No trip id, order
 * id, employee id or supplier text ever becomes a label.
 *
 * <ul>
 *   <li>{@code travelos_order_component_bookings_total{type,outcome}}: CONFIRMED, RECONCILED (the
 *       supplier's answer was lost and a status lookup settled it), FAILED
 *   <li>{@code travelos_order_component_compensations_total{type,outcome}}: RELEASED, CANCEL_FAILED
 *   <li>{@code travelos_order_exposures_open}: gauge, exposures still waiting for a person
 *   <li>{@code travelos_order_exposures_resolved_total}
 * </ul>
 */
@Component
public class OrderMetrics {
  private static final Set<String> TYPES = Set.of("AIR", "HOTEL", "GROUND");
  private final MeterRegistry registry;

  public OrderMetrics(MeterRegistry registry, ExposureRepository exposures) {
    this.registry = registry;
    Gauge.builder("travelos.order.exposures.open", exposures, ExposureRepository::countOpen)
        .description("unresolved exposures: bookings that could not be released, awaiting a person")
        .register(registry);
  }

  public void booked(String offerType, String outcome) {
    Counter.builder("travelos.order.component.bookings")
        .description("component bookings by outcome")
        .tags(Tags.of("type", type(offerType), "outcome", outcome))
        .register(registry)
        .increment();
  }

  public void compensated(String offerType, String outcome) {
    Counter.builder("travelos.order.component.compensations")
        .description("component releases during compensation, by outcome")
        .tags(Tags.of("type", type(offerType), "outcome", outcome))
        .register(registry)
        .increment();
  }

  public void exposureResolved() {
    Counter.builder("travelos.order.exposures.resolved")
        .description("exposures closed by a person")
        .register(registry)
        .increment();
  }

  static String type(String offerType) {
    String t = offerType == null ? "" : offerType.toUpperCase(Locale.ROOT);
    return TYPES.contains(t) ? t : "OTHER";
  }
}
