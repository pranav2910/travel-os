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
