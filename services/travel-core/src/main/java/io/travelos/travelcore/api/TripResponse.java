package io.travelos.travelcore.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.travelos.travelcore.api.ApprovalController.ApprovalResponse;
import io.travelos.travelcore.approval.Approval;
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
    Instant updatedAt,
    TravelerView traveler,
    @Nullable MoneyView total,
    @Nullable ApprovalResponse approval,
    @Nullable String failureStage,
    @Nullable String failureCode,
    @Nullable String explanation) {

  public record TravelerView(
      String travelerId, String givenName, String familyName, String email) {}

  public record MoneyView(String currency, long amountMinor, String display) {}

  public static TripResponse from(Trip trip) {
    return from(trip, null);
  }

  public static TripResponse from(Trip trip, @Nullable Approval approval) {
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
        trip.updatedAt(),
        new TravelerView(
            trip.traveler().travelerId(),
            trip.traveler().givenName(),
            trip.traveler().familyName(),
            trip.traveler().email()),
        trip.total() == null
            ? null
            : new MoneyView(
                trip.total().currency(), trip.total().amountMinor(), trip.total().toString()),
        approval == null ? null : ApprovalResponse.from(approval),
        trip.failureStage(),
        trip.failureCode(),
        trip.explanation());
  }
}
