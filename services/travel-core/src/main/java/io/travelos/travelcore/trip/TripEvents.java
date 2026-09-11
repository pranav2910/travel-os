package io.travelos.travelcore.trip;

import io.travelos.common.identity.Principal;
import io.travelos.common.money.Money;
import io.travelos.events.EventEnvelope;
import io.travelos.travelcore.approval.Approval;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Builds travel.trip.* and travel.approval.* envelopes. Payload shapes mirror
 * contracts/events/{trip,approval}-events.schema.json.
 */
final class TripEvents {

  static final String PRODUCER = "travel-core";

  private TripEvents() {}

  static EventEnvelope created(Trip trip, @Nullable String causationId, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("travelerId", trip.travelerId());
    data.put("status", trip.status().name());
    data.put("source", trip.source().name());
    data.put("requestedBy", trip.createdBy().id());
    return envelope("travel.trip.created", trip, causationId, data, clock);
  }

  static EventEnvelope planned(
      Trip trip, boolean requiresApproval, @Nullable String causationId, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("selectedBundleId", trip.evidence().selectedBundleId());
    data.put("optimizationRunId", trip.evidence().optimizationRunId());
    data.put("policyDecisionId", trip.evidence().policyDecisionId());
    data.put("requiresApproval", requiresApproval);
    data.put("total", money(trip.total()));
    data.put("status", trip.status().name());
    return envelope("travel.trip.planned", trip, causationId, data, clock);
  }

  static EventEnvelope booked(Trip trip, @Nullable String causationId, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("orderId", trip.evidence().orderId());
    if (trip.evidence().approvalId() != null) {
      data.put("approvalId", trip.evidence().approvalId());
    }
    data.put("total", money(trip.total()));
    return envelope("travel.trip.booked", trip, causationId, data, clock);
  }

  static EventEnvelope failed(
      Trip trip,
      String stage,
      String code,
      @Nullable String message,
      @Nullable String causationId,
      Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("stage", stage);
    data.put("reasonCode", code);
    if (message != null && !message.isBlank()) {
      data.put("message", message.length() > 2000 ? message.substring(0, 2000) : message);
    }
    return envelope("travel.trip.failed", trip, causationId, data, clock);
  }

  static EventEnvelope cancelled(
      Trip trip, String reason, Principal cancelledBy, @Nullable String causationId, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("reason", reason);
    data.put("cancelledBy", cancelledBy.id());
    return envelope("travel.trip.cancelled", trip, causationId, data, clock);
  }

  static EventEnvelope approvalRequested(Trip trip, Approval approval, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("approvalId", approval.approvalId());
    data.put("tripId", trip.tripId());
    data.put("requestedFrom", "role:" + approval.requiredRole());
    data.put("role", approval.requiredRole());
    data.put("policyDecisionId", approval.policyDecisionId());
    data.put("total", money(trip.total()));
    return EventEnvelope.create(
        "travel.approval.requested",
        1,
        trip.tenantId(),
        trip.tripId(),
        null,
        PRODUCER,
        data,
        clock);
  }

  static EventEnvelope approvalDecided(
      Trip trip, Approval approval, Principal by, @Nullable String comment, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("approvalId", approval.approvalId());
    data.put("tripId", trip.tripId());
    data.put("decidedBy", by.id());
    if (comment != null && !comment.isBlank()) {
      data.put("comment", comment);
    }
    String type =
        approval.status() == Approval.Status.APPROVED
            ? "travel.approval.approved"
            : "travel.approval.rejected";
    return EventEnvelope.create(
        type, 1, trip.tenantId(), trip.tripId(), null, PRODUCER, data, clock);
  }

  private static Map<String, Object> money(@Nullable Money money) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("currency", money == null ? "USD" : money.currency());
    m.put("amountMinor", money == null ? 0L : money.amountMinor());
    return m;
  }

  private static EventEnvelope envelope(
      String type, Trip trip, @Nullable String causationId, Map<String, Object> data, Clock clock) {
    return EventEnvelope.create(
        type, 1, trip.tenantId(), trip.tripId(), causationId, PRODUCER, data, clock);
  }
}
