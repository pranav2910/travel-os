package io.travelos.travelcore.trip;

import io.travelos.common.identity.Principal;
import io.travelos.common.money.Money;
import io.travelos.events.EventEnvelope;
import io.travelos.travelcore.approval.Approval;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
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
    if (trip.sourceReference() != null) {
      data.put("sourceReference", trip.sourceReference());
    }
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

  static EventEnvelope booked(
      Trip trip, List<TripComponent> components, @Nullable String causationId, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("orderId", trip.evidence().orderId());
    if (trip.evidence().approvalId() != null) {
      data.put("approvalId", trip.evidence().approvalId());
    }
    data.put("total", money(trip.total()));
    if (!components.isEmpty()) {
      data.put("components", components(components));
    }
    return envelope("travel.trip.booked", trip, causationId, data, clock);
  }

  /** Slice 3: revalidation before booking found a material change; the plan goes back to policy. */
  static EventEnvelope replanned(
      Trip trip,
      String reason,
      @Nullable Money previousTotal,
      boolean requiresApproval,
      @Nullable String previousApprovalId,
      @Nullable String causationId,
      Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("reason", reason);
    data.put("previousTotal", money(previousTotal == null ? trip.total() : previousTotal));
    data.put("newTotal", money(trip.total()));
    data.put("requiresApproval", requiresApproval);
    if (previousApprovalId != null) {
      data.put("previousApprovalId", previousApprovalId);
    }
    if (trip.evidence().policyDecisionId() != null) {
      data.put("policyDecisionId", trip.evidence().policyDecisionId());
    }
    if (trip.evidence().selectedBundleId() != null) {
      data.put("selectedBundleId", trip.evidence().selectedBundleId());
    }
    return envelope("travel.trip.replanned", trip, causationId, data, clock);
  }

  static List<Map<String, Object>> components(List<TripComponent> components) {
    List<Map<String, Object>> out = new java.util.ArrayList<>();
    for (TripComponent c : components) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("componentId", c.componentId());
      m.put("type", c.type());
      m.put("status", c.status());
      if (c.provider() != null) {
        m.put("provider", c.provider());
      }
      if (c.externalRef() != null) {
        m.put("externalRef", c.externalRef());
      }
      if (c.total() != null) {
        m.put("total", money(c.total()));
      }
      if (c.failureCode() != null) {
        m.put("failureCode", c.failureCode());
      }
      if (c.summary() != null) {
        m.put("summary", c.summary());
      }
      out.add(m);
    }
    return out;
  }

  static EventEnvelope failed(
      Trip trip,
      String stage,
      String code,
      @Nullable String message,
      List<TripComponent> components,
      @Nullable String causationId,
      Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("stage", stage);
    data.put("reasonCode", code);
    if (message != null && !message.isBlank()) {
      data.put("message", message.length() > 2000 ? message.substring(0, 2000) : message);
    }
    if (!components.isEmpty()) {
      data.put("components", components(components));
    }
    return envelope("travel.trip.failed", trip, causationId, data, clock);
  }

  /** Slice 5: the trip happened, as a person attested. Never inferred. */
  static EventEnvelope completed(Trip trip, @Nullable String causationId, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("orderId", trip.evidence().orderId());
    return envelope("travel.trip.completed", trip, causationId, data, clock);
  }

  static EventEnvelope cancelled(
      Trip trip, String reason, Principal cancelledBy, @Nullable String causationId, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("reason", reason);
    data.put("cancelledBy", cancelledBy.id());
    return envelope("travel.trip.cancelled", trip, causationId, data, clock);
  }

  /** A booked trip's cancellation was asked for: the Order service must release the reservation. */
  static EventEnvelope cancellationRequested(
      Trip trip, String orderId, String reason, Principal requestedBy, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("orderId", orderId);
    data.put("reason", reason);
    data.put("requestedBy", requestedBy.id());
    return envelope("travel.trip.cancellation-requested", trip, null, data, clock);
  }

  /**
   * A supplier refused to release part of the reservation: the trip stays CANCELLING for a person.
   */
  static EventEnvelope cancellationIncomplete(
      Trip trip,
      String orderId,
      String reasonCode,
      String message,
      @Nullable String causationId,
      Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("orderId", orderId);
    data.put("reasonCode", reasonCode);
    data.put("message", message);
    return envelope("travel.trip.cancellation-incomplete", trip, causationId, data, clock);
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

  static EventEnvelope intentDetected(
      Trip trip, TripService.IntentExtraction x, Principal actor, Clock clock) {
    TravelIntent i = trip.intent();
    Map<String, Object> intent = new LinkedHashMap<>();
    intent.put("origin", i.origin());
    intent.put("destination", i.destination());
    intent.put("earliestDeparture", i.earliestDeparture().toString());
    intent.put("arrivalDeadline", i.arrivalDeadline().toString());
    if (i.returnAfter() != null) {
      intent.put("returnAfter", i.returnAfter().toString());
      intent.put("latestReturn", i.latestReturn().toString());
    }
    if (i.purpose() != null && !i.purpose().isBlank()) {
      intent.put("purpose", i.purpose());
    }
    intent.put("hotelRequired", i.hotelRequired());
    intent.put("travelers", i.travelers());
    if (i.itinerary() != null) {
      intent.put("itinerary", ItineraryCodec.toMap(i.itinerary()));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("method", "FREE_TEXT");
    data.put("result", "EXTRACTED");
    data.put("model", x.call().model().isBlank() ? "unknown" : x.call().model());
    data.put("modelCallId", x.call().callId());
    data.put("confidence", x.confidence());
    data.put("assumptions", x.assumptions());
    data.put("intent", intent);
    data.put("detectedBy", actor.id());
    return EventEnvelope.create(
        "travel.intent.detected",
        1,
        trip.tenantId(),
        trip.tripId(),
        x.causationId(),
        PRODUCER,
        data,
        clock);
  }

  static EventEnvelope intentRejected(
      Trip trip, TripService.IntentExtraction x, Principal actor, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("method", "FREE_TEXT");
    data.put("result", x.result());
    data.put("model", x.call().model().isBlank() ? "unknown" : x.call().model());
    data.put("modelCallId", x.call().callId());
    data.put("missingFields", x.missingFields());
    if (x.clarifyingQuestion() != null && !x.clarifyingQuestion().isBlank()) {
      data.put("clarifyingQuestion", x.clarifyingQuestion());
    }
    data.put("detectedBy", actor.id());
    return EventEnvelope.create(
        "travel.intent.rejected",
        1,
        trip.tenantId(),
        trip.tripId(),
        x.causationId(),
        PRODUCER,
        data,
        clock);
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
