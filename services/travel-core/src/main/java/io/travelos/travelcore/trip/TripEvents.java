package io.travelos.travelcore.trip;

import io.travelos.common.identity.Principal;
import io.travelos.events.EventEnvelope;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Builds travel.trip.* envelopes. Payload shapes mirror contracts/events/trip-events.schema.json.
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

  static EventEnvelope cancelled(
      Trip trip, String reason, Principal cancelledBy, @Nullable String causationId, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tripId", trip.tripId());
    data.put("reason", reason);
    data.put("cancelledBy", cancelledBy.id());
    return envelope("travel.trip.cancelled", trip, causationId, data, clock);
  }

  private static EventEnvelope envelope(
      String type, Trip trip, @Nullable String causationId, Map<String, Object> data, Clock clock) {
    return EventEnvelope.create(
        type, 1, trip.tenantId(), trip.tripId(), causationId, PRODUCER, data, clock);
  }
}
