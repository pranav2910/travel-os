package io.travelos.travelcore.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.travelos.travelcore.trip.Trip;
import io.travelos.travelcore.trip.TripEvidence;
import io.travelos.travelcore.trip.TripSource;
import io.travelos.travelcore.trip.TripStatus;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripResponse(
    String tripId,
    String tenantId,
    String travelerId,
    TripStatus status,
    TripSource source,
    @Nullable String request,
    @Nullable IntentRequest intent,
    TripEvidence evidence,
    String createdBy,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public static TripResponse from(Trip trip) {
    return new TripResponse(
        trip.tripId(),
        trip.tenantId().value(),
        trip.travelerId(),
        trip.status(),
        trip.source(),
        trip.requestText(),
        trip.intent() == null ? null : IntentRequest.from(trip.intent()),
        trip.evidence(),
        trip.createdBy().id(),
        trip.version(),
        trip.createdAt(),
        trip.updatedAt());
  }
}
