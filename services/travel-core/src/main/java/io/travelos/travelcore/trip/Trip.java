package io.travelos.travelcore.trip;

import io.travelos.common.identity.Principal;
import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record Trip(
    String tripId,
    TenantId tenantId,
    String travelerId,
    TripStatus status,
    TripSource source,
    @Nullable String requestText,
    @Nullable TravelIntent intent,
    TripEvidence evidence,
    Principal createdBy,
    String idempotencyKey,
    String requestFingerprint,
    long version,
    Instant createdAt,
    Instant updatedAt,
    TravelerSnapshot traveler,
    @Nullable Money total,
    @Nullable String failureStage,
    @Nullable String failureCode) {

  public Trip withStatus(TripStatus next, Instant now) {
    if (!status.canTransitionTo(next)) {
      throw new IllegalStateException(
          "trip " + tripId + " cannot go from " + status + " to " + next);
    }
    return new Trip(
        tripId,
        tenantId,
        travelerId,
        next,
        source,
        requestText,
        intent,
        evidence,
        createdBy,
        idempotencyKey,
        requestFingerprint,
        version + 1,
        createdAt,
        now,
        traveler,
        total,
        failureStage,
        failureCode);
  }

  public Trip withEvidence(TripEvidence next, @Nullable Money newTotal) {
    return new Trip(
        tripId,
        tenantId,
        travelerId,
        status,
        source,
        requestText,
        intent,
        next,
        createdBy,
        idempotencyKey,
        requestFingerprint,
        version,
        createdAt,
        updatedAt,
        traveler,
        newTotal == null ? total : newTotal,
        failureStage,
        failureCode);
  }

  public Trip withFailure(String stage, String code) {
    return new Trip(
        tripId,
        tenantId,
        travelerId,
        status,
        source,
        requestText,
        intent,
        evidence,
        createdBy,
        idempotencyKey,
        requestFingerprint,
        version,
        createdAt,
        updatedAt,
        traveler,
        total,
        stage,
        code);
  }
}
