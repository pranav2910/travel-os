package io.travelos.context.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.travelos.context.model.ConnectorKind;
import org.springframework.stereotype.Component;

/**
 * Slice 4 metrics. Every label is a fixed vocabulary (connector kind, an outcome enum); no tenant,
 * candidate, employee or source id and no free text ever becomes a label.
 *
 * <ul>
 *   <li>{@code travelos_demand_sync_pages_total{kind,outcome}} OK | SOURCE_UNAVAILABLE |
 *       RATE_LIMITED | FAILED
 *   <li>{@code travelos_demand_sync_runs_total{kind,outcome}} COMPLETED | FAILED
 *   <li>{@code travelos_demand_items_total{kind,outcome}} NEW | UPDATED | DELETED |
 *       DUPLICATE_DELIVERY | STALE_REVISION | UNRESOLVED_IDENTITY
 *   <li>{@code travelos_demand_candidates_total{decision}} the detection rule's decision
 *   <li>{@code travelos_demand_duplicates_suppressed_total{kind}} DUPLICATE_DELIVERY |
 *       STALE_REVISION | CORRELATED_SOURCE | CONVERSION_REPEATED
 *   <li>{@code travelos_demand_conversions_total{outcome}} CREATED | ALREADY_CONVERTED | REFUSED
 *   <li>{@code travelos_demand_notifications_total{outcome}} ACCEPTED | DUPLICATE | REJECTED
 * </ul>
 */
@Component
public class DemandMetrics {
  private final MeterRegistry registry;

  public DemandMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void page(ConnectorKind kind, String outcome) {
    count("travelos.demand.sync.pages", Tags.of("kind", kind.name(), "outcome", outcome));
  }

  public void run(ConnectorKind kind, String outcome) {
    count("travelos.demand.sync.runs", Tags.of("kind", kind.name(), "outcome", outcome));
  }

  public void item(ConnectorKind kind, String outcome) {
    count("travelos.demand.items", Tags.of("kind", kind.name(), "outcome", outcome));
  }

  public void candidate(String decision) {
    count("travelos.demand.candidates", Tags.of("decision", decision));
  }

  public void duplicateSuppressed(String kind) {
    count("travelos.demand.duplicates.suppressed", Tags.of("kind", kind));
  }

  public void conversion(String outcome) {
    count("travelos.demand.conversions", Tags.of("outcome", outcome));
  }

  public void notification(String outcome) {
    count("travelos.demand.notifications", Tags.of("outcome", outcome));
  }

  private void count(String name, Tags tags) {
    Counter.builder(name).tags(tags).register(registry).increment();
  }
}
