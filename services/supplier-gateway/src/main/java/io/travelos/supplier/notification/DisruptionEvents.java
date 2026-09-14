package io.travelos.supplier.notification;

import io.travelos.common.tenant.TenantId;
import io.travelos.events.EventEnvelope;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** travel.disruption.detected, shaped by contracts/events/disruption-events.schema.json. */
public final class DisruptionEvents {

  public static final String PRODUCER = "supplier-gateway";
  public static final String DETECTED = "travel.disruption.detected";

  private DisruptionEvents() {}

  public static EventEnvelope detected(String disruptionId, SupplierNotification n, Clock clock) {
    Map<String, Object> affected = new LinkedHashMap<>();
    if (n.affected().segmentId() != null && !n.affected().segmentId().isBlank()) {
      affected.put("segmentId", n.affected().segmentId());
    }
    affected.put("carrier", n.affected().carrier());
    affected.put("flightNumber", n.affected().flightNumber());
    affected.put("origin", n.affected().origin());
    affected.put("destination", n.affected().destination());
    affected.put("scheduledDeparture", n.affected().scheduledDeparture().toString());
    if (n.affected().scheduledArrival() != null) {
      affected.put("scheduledArrival", n.affected().scheduledArrival().toString());
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("disruptionId", disruptionId);
    data.put("type", n.type().name());
    data.put("supplier", n.provider());
    data.put("supplierEventId", n.supplierEventId());
    data.put("externalOrderId", n.externalOrderId());
    if (n.recordLocator() != null && !n.recordLocator().isBlank()) {
      data.put("recordLocator", n.recordLocator());
    }
    data.put("detectedAt", n.detectedAt().toString());
    data.put("severity", n.severity().name());
    if (n.rawReference() != null && !n.rawReference().isBlank()) {
      data.put("rawReference", truncate(n.rawReference(), 512));
    }
    if (n.reason() != null && !n.reason().isBlank()) {
      data.put("reason", truncate(n.reason(), 2000));
    }
    data.put("affected", affected);
    return EventEnvelope.create(
        DETECTED,
        1,
        TenantId.of(n.tenantId()),
        n.correlationId(),
        n.supplierEventId(),
        PRODUCER,
        data,
        clock);
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }
}
