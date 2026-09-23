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
    @Nullable String failureCode,
    @Nullable String explanation,
    @Nullable String sourceReference) {

  /** The Slice 1-3 shape: no source reference. */
  public Trip(
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
      @Nullable String failureCode,
      @Nullable String explanation) {
    this(
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
        failureStage,
        failureCode,
        explanation,
        null);
  }

  public Trip withStatus(TripStatus next, Instant now) {
    if (!status.canTransitionTo(next)) {
      throw new IllegalStateException(
          "trip " + tripId + " cannot go from " + status + " to " + next);
    }
    return copy(
        next, intent, evidence, version + 1, now, total, failureStage, failureCode, explanation);
  }

  /**
   * Freezes the intent (a new version: the plan's inputs changed). Only meaningful on SUBMITTED.
   */
  public Trip withIntent(TravelIntent frozen, Instant now) {
    return copy(
        status, frozen, evidence, version + 1, now, total, failureStage, failureCode, explanation);
  }

  public Trip withEvidence(TripEvidence next, @Nullable Money newTotal) {
    return copy(
        status,
        intent,
        next,
        version,
        updatedAt,
        newTotal == null ? total : newTotal,
        failureStage,
        failureCode,
        explanation);
  }

  public Trip withFailure(String stage, String code) {
    return copy(status, intent, evidence, version, updatedAt, total, stage, code, explanation);
  }

  /** Records a failure on the trip as it stands (no lifecycle move): a new version, a new time. */
  public Trip withFailure(String stage, String code, Instant now) {
    return copy(status, intent, evidence, version + 1, now, total, stage, code, explanation);
  }

  /** The failure is over (e.g. an incomplete cancellation finally released everything). */
  public Trip withoutFailure() {
    return copy(status, intent, evidence, version, updatedAt, total, null, null, explanation);
  }

  public Trip withExplanation(@Nullable String narration) {
    return copy(
        status, intent, evidence, version, updatedAt, total, failureStage, failureCode, narration);
  }

  private Trip copy(
      TripStatus newStatus,
      @Nullable TravelIntent newIntent,
      TripEvidence newEvidence,
      long newVersion,
      Instant newUpdatedAt,
      @Nullable Money newTotal,
      @Nullable String newFailureStage,
      @Nullable String newFailureCode,
      @Nullable String newExplanation) {
    return new Trip(
        tripId,
        tenantId,
        travelerId,
        newStatus,
        source,
        requestText,
        newIntent,
        newEvidence,
        createdBy,
        idempotencyKey,
        requestFingerprint,
        newVersion,
        createdAt,
        newUpdatedAt,
        traveler,
        newTotal,
        newFailureStage,
        newFailureCode,
        newExplanation,
        sourceReference);
  }
}
