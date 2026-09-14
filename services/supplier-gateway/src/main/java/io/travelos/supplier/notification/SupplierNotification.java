package io.travelos.supplier.notification;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A supplier's notice in OUR vocabulary. Adapters produce it from whatever the vendor sent; nothing
 * above the gateway ever sees the vendor shape. {@code reason} is the supplier's own words and is
 * carried as data only: it may be shown to people, it never drives a decision.
 */
public record SupplierNotification(
    String provider,
    String supplierEventId,
    String tenantId,
    String correlationId,
    String externalOrderId,
    @Nullable String recordLocator,
    Type type,
    Severity severity,
    Instant detectedAt,
    @Nullable String rawReference,
    @Nullable String reason,
    AffectedSegment affected) {

  public enum Type {
    FLIGHT_CANCELLED,
    SCHEDULE_CHANGE,
    DELAY
  }

  public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
  }

  /** The gateway fills in what only it knows: the tenant and trip this booking belongs to. */
  public SupplierNotification withRef(
      String tenantId, String correlationId, @Nullable String recordLocator) {
    return new SupplierNotification(
        provider,
        supplierEventId,
        tenantId,
        correlationId,
        externalOrderId,
        recordLocator == null ? this.recordLocator : recordLocator,
        type,
        severity,
        detectedAt,
        rawReference,
        reason,
        affected);
  }

  public record AffectedSegment(
      String segmentId,
      String carrier,
      String flightNumber,
      String origin,
      String destination,
      Instant scheduledDeparture,
      @Nullable Instant scheduledArrival) {}
}
