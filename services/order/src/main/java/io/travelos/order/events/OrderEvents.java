package io.travelos.order.events;

import io.travelos.common.identity.Principal;
import io.travelos.common.money.Money;
import io.travelos.events.EventEnvelope;
import io.travelos.order.store.ExposureRecord;
import io.travelos.order.store.OrderChangeRecord;
import io.travelos.order.store.OrderRecord;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Builds travel.order.* envelopes. Shapes mirror contracts/events/order-events.schema.json. */
public final class OrderEvents {

  public static final String PRODUCER = "order";

  private OrderEvents() {}

  public static EventEnvelope created(OrderRecord o, @Nullable String causationId, Clock clock) {
    Map<String, Object> data = base(o);
    data.put("supplier", o.supplier());
    data.put("status", "CREATING");
    data.put("total", money(o.total()));
    data.put("idempotencyKey", o.idempotencyKey());
    data.put("items", items(o));
    putIfPresent(data, "policyDecisionId", o.policyDecisionId());
    putIfPresent(data, "optimizationRunId", o.optimizationRunId());
    putIfPresent(data, "approvalId", o.approvalId());
    return envelope("travel.order.created", o, causationId, data, clock);
  }

  public static EventEnvelope confirmed(OrderRecord o, @Nullable String causationId, Clock clock) {
    Map<String, Object> data = base(o);
    data.put("supplier", o.supplier());
    data.put("externalOrderId", o.externalOrderId() == null ? "" : o.externalOrderId());
    data.put("total", money(o.total()));
    data.put("items", items(o));
    return envelope("travel.order.confirmed", o, causationId, data, clock);
  }

  public static EventEnvelope failed(
      OrderRecord o,
      String reasonCode,
      String message,
      boolean compensated,
      @Nullable String causationId,
      Clock clock) {
    return failed(o, reasonCode, message, compensated, List.of(), causationId, clock);
  }

  public static EventEnvelope failed(
      OrderRecord o,
      String reasonCode,
      String message,
      boolean compensated,
      List<ExposureRecord> exposures,
      @Nullable String causationId,
      Clock clock) {
    Map<String, Object> data = base(o);
    data.put("status", o.status().name());
    data.put("reasonCode", reasonCode);
    data.put("message", message.length() > 2000 ? message.substring(0, 2000) : message);
    data.put("compensated", compensated);
    data.put("items", items(o));
    if (!exposures.isEmpty()) {
      data.put("exposures", exposures(exposures));
    }
    return envelope("travel.order.failed", o, causationId, data, clock);
  }

  /** Slice 3: money at risk after a failed compensation; a person must act. */
  public static EventEnvelope compensationFailed(
      OrderRecord o,
      List<ExposureRecord> exposures,
      String reasonCode,
      String message,
      @Nullable String causationId,
      Clock clock) {
    Map<String, Object> data = base(o);
    data.put("reasonCode", reasonCode);
    data.put("message", message.length() > 2000 ? message.substring(0, 2000) : message);
    data.put("exposures", exposures(exposures));
    data.put("requiredRole", "TRAVEL_ADMIN");
    return envelope("travel.order.compensation-failed", o, causationId, data, clock);
  }

  public static EventEnvelope exposureResolved(
      OrderRecord o, ExposureRecord e, Principal by, @Nullable String causationId, Clock clock) {
    Map<String, Object> data = base(o);
    data.put("exposureId", e.exposureId());
    data.put("resolvedBy", by.id());
    data.put("resolution", e.resolution() == null ? "" : e.resolution());
    data.put("amount", money(e.amount()));
    return envelope("travel.order.exposure-resolved", o, causationId, data, clock);
  }

  private static List<Map<String, Object>> exposures(List<ExposureRecord> exposures) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (ExposureRecord e : exposures) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("exposureId", e.exposureId());
      m.put("itemId", e.itemId());
      putIfPresent(m, "componentId", e.componentId());
      m.put("provider", e.provider());
      m.put("externalRef", e.externalRef());
      m.put("amount", money(e.amount()));
      m.put("reason", e.reason());
      putIfPresent(m, "detail", e.detail());
      m.put("status", e.status().name());
      out.add(m);
    }
    return out;
  }

  public static EventEnvelope cancelled(
      OrderRecord o,
      @Nullable Money refund,
      String reason,
      Principal by,
      @Nullable String causationId,
      Clock clock) {
    Map<String, Object> data = base(o);
    if (refund != null) {
      data.put("refund", money(refund));
    }
    data.put("reason", reason);
    data.put("cancelledBy", by.id());
    return envelope("travel.order.cancelled", o, causationId, data, clock);
  }

  public static EventEnvelope changeRequested(
      OrderRecord o, OrderChangeRecord c, @Nullable String causationId, Clock clock) {
    Map<String, Object> data = base(o);
    data.put("disruptionId", c.disruptionId());
    data.put("changeId", c.changeId());
    data.put("idempotencyKey", c.idempotencyKey());
    data.put("previousBundleId", c.previousBundleId());
    data.put("replacementBundleId", c.replacementBundleId());
    putIfPresent(data, "policyDecisionId", c.policyDecisionId());
    putIfPresent(data, "optimizationRunId", c.optimizationRunId());
    putIfPresent(data, "approvalId", c.approvalId());
    data.put("requestedBy", c.requestedBy().id());
    return envelope("travel.order.change-requested", o, causationId, data, clock);
  }

  public static EventEnvelope changed(
      OrderRecord o, OrderChangeRecord c, @Nullable String causationId, Clock clock) {
    Map<String, Object> data = base(o);
    Money incremental =
        Money.of(c.currency(), c.incrementalMinor() == null ? 0L : c.incrementalMinor());
    data.put("incrementalCost", money(incremental));
    data.put("changedBy", c.requestedBy().id());
    putIfPresent(data, "policyDecisionId", c.policyDecisionId());
    data.put("disruptionId", c.disruptionId());
    data.put("changeId", c.changeId());
    data.put("previousBundleId", c.previousBundleId());
    data.put("replacementBundleId", c.replacementBundleId());
    putIfPresent(data, "optimizationRunId", c.optimizationRunId());
    putIfPresent(data, "approvalId", c.approvalId());
    putIfPresent(data, "externalOrderId", o.externalOrderId());
    putIfPresent(data, "recordLocator", c.recordLocator());
    data.put("total", money(o.total()));
    data.put("items", items(o));
    return envelope("travel.order.changed", o, causationId, data, clock);
  }

  private static Map<String, Object> base(OrderRecord o) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("orderId", o.orderId());
    data.put("tripId", o.tripId());
    return data;
  }

  private static List<Map<String, Object>> items(OrderRecord o) {
    List<Map<String, Object>> items = new ArrayList<>();
    for (OrderRecord.Item item : o.items()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("itemId", item.itemId());
      m.put("type", item.offerType());
      m.put("status", item.status().name());
      putIfPresent(m, "externalRef", item.externalRef());
      putIfPresent(m, "recordLocator", item.recordLocator());
      putIfPresent(m, "componentId", item.componentId());
      m.put("total", money(item.total()));
      putIfPresent(m, "failureCode", item.failureCode());
      // Slice 5: who the outcome of this item belongs to, so consumers never re-read the order
      putIfPresent(m, "provider", item.provider());
      putIfPresent(m, "supplierKey", SupplierKeys.of(item.offerJson()));
      items.add(m);
    }
    return items;
  }

  private static void putIfPresent(Map<String, Object> m, String key, @Nullable String value) {
    if (value != null && !value.isBlank()) {
      m.put(key, value);
    }
  }

  private static Map<String, Object> money(Money money) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("currency", money.currency());
    m.put("amountMinor", money.amountMinor());
    return m;
  }

  private static EventEnvelope envelope(
      String type,
      OrderRecord o,
      @Nullable String causationId,
      Map<String, Object> data,
      Clock clock) {
    return EventEnvelope.create(
        type, 1, o.tenant(), o.tripId(), causationId, PRODUCER, data, clock);
  }
}
