package io.travelos.assistance.events;

import io.travelos.assistance.model.AssistanceCase;
import io.travelos.events.EventEnvelope;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** travel.assistance.* per contracts/events/assistance-events.schema.json. */
public final class AssistanceEvents {
  public static final String PRODUCER = "assistance";

  private AssistanceEvents() {}

  public static EventEnvelope opened(AssistanceCase c, Clock clock) {
    return envelope("travel.assistance.case-opened", c, base(c), clock);
  }

  public static EventEnvelope assigned(AssistanceCase c, String by, Clock clock) {
    Map<String, Object> data = base(c);
    data.put("assignedBy", by);
    return envelope("travel.assistance.case-assigned", c, data, clock);
  }

  public static EventEnvelope escalated(AssistanceCase c, String by, String reason, Clock clock) {
    Map<String, Object> data = base(c);
    data.put("escalatedBy", by);
    data.put("reason", reason);
    return envelope("travel.assistance.case-escalated", c, data, clock);
  }

  public static EventEnvelope resolved(AssistanceCase c, String by, Clock clock) {
    Map<String, Object> data = base(c);
    data.put("resolvedBy", by);
    data.put("resolution", c.resolution() == null ? "" : c.resolution());
    return envelope("travel.assistance.case-resolved", c, data, clock);
  }

  public static EventEnvelope closed(AssistanceCase c, String by, Clock clock) {
    Map<String, Object> data = base(c);
    data.put("closedBy", by);
    return envelope("travel.assistance.case-closed", c, data, clock);
  }

  /** Phase 8: a notification left on a channel (or failed for good). */
  public static EventEnvelope notificationSent(
      io.travelos.assistance.notify.NotificationRecords.Notification n,
      String channel,
      String status,
      @Nullable String providerRef,
      Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("notificationId", n.notificationId());
    data.put(
        "recipient",
        n.recipientEmployeeId() != null ? n.recipientEmployeeId() : "role:" + n.recipientRole());
    data.put("category", n.category().name());
    data.put("kind", n.kind());
    data.put("channel", channel);
    data.put("status", status);
    put(data, "tripId", n.tripId());
    put(data, "providerRef", providerRef);
    return EventEnvelope.create(
        "travel.assistance.notification-sent",
        1,
        n.tenant(),
        n.tripId() != null ? n.tripId() : n.notificationId(),
        n.sourceEventId(),
        PRODUCER,
        data,
        clock);
  }

  /** Phase 8: a safety advisory was issued. */
  public static EventEnvelope advisoryIssued(
      io.travelos.assistance.safety.SafetyRecords.Advisory a, int affected, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("advisoryId", a.advisoryId());
    data.put("title", a.title());
    data.put("severity", a.severity().name());
    data.put("countries", a.countries());
    data.put("cities", a.cities());
    data.put("from", a.startsAt().toString());
    data.put("until", a.endsAt().toString());
    data.put("affectedTravelers", affected);
    data.put("issuedBy", a.issuedBy());
    return EventEnvelope.create(
        "travel.assistance.advisory-issued",
        1,
        a.tenant(),
        a.advisoryId(),
        null,
        PRODUCER,
        data,
        clock);
  }

  /** Phase 8: a traveler answered an advisory. */
  public static EventEnvelope checkinRecorded(
      io.travelos.assistance.safety.SafetyRecords.Checkin c, @Nullable String tripId, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("advisoryId", c.advisoryId());
    data.put("travelerId", c.travelerId());
    data.put("status", c.status().name());
    put(data, "note", c.note());
    put(data, "tripId", tripId);
    return EventEnvelope.create(
        "travel.assistance.checkin-recorded",
        1,
        c.tenant(),
        tripId != null ? tripId : c.advisoryId(),
        null,
        PRODUCER,
        data,
        clock);
  }

  private static Map<String, Object> base(AssistanceCase c) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("caseId", c.caseId());
    data.put("kind", c.kind().name());
    data.put("status", c.status().name());
    data.put("priority", c.priority().name());
    data.put("queue", c.queue().name());
    data.put("title", c.title());
    put(data, "tripId", c.tripId());
    put(data, "orderId", c.orderId());
    put(data, "travelerId", c.travelerId());
    put(data, "disruptionId", c.disruptionId());
    put(data, "exposureId", c.exposureId());
    put(data, "owner", c.owner());
    data.put("nextAction", c.nextAction());
    data.put("nextActionRole", c.nextActionRole());
    data.put("escalationLevel", c.escalationLevel());
    data.put("dueAt", c.dueAt().toString());
    return data;
  }

  private static void put(Map<String, Object> data, String key, @Nullable String value) {
    if (value != null && !value.isBlank()) {
      data.put(key, value);
    }
  }

  private static EventEnvelope envelope(
      String type, AssistanceCase c, Map<String, Object> data, Clock clock) {
    String correlation = c.tripId() != null ? c.tripId() : c.caseId();
    return EventEnvelope.create(
        type, 1, c.tenant(), correlation, c.sourceEventId(), PRODUCER, data, clock);
  }
}
