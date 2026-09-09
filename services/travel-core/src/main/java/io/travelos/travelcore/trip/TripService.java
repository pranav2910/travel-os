package io.travelos.travelcore.trip;

import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.spring.outbox.Outbox;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripService {

  /** Slice 1 scope is a product decision, enforced here, not a TODO in a comment. */
  private static final int SLICE_1_MAX_TRAVELERS = 1;

  private final TripRepository trips;
  private final Outbox outbox;
  private final Clock clock;

  public TripService(TripRepository trips, Outbox outbox, Clock clock) {
    this.trips = trips;
    this.outbox = outbox;
    this.clock = clock;
  }

  /**
   * Creates a trip, or returns the one already created with the same Idempotency-Key. The key is
   * scoped by tenant; reusing it with a different body is a client bug and gets 422.
   */
  @Transactional
  public Trip create(RequestPrincipal me, CreateTrip command, String idempotencyKey) {
    String travelerId =
        command.travelerId() == null ? me.employeeIdOrThrow() : command.travelerId();
    if (!TripAccess.canCreateFor(me, travelerId)) {
      throw new ApiException.Forbidden(
          "NOT_AN_ARRANGER", "only MANAGER or TRAVEL_ADMIN may create trips for other travelers");
    }
    if (command.intent() != null && command.intent().travelers() > SLICE_1_MAX_TRAVELERS) {
      throw new ApiException.Unprocessable(
          "SLICE_SCOPE_SINGLE_TRAVELER", "this release books trips for one traveler at a time");
    }

    String fingerprint = command.fingerprint(travelerId);
    Optional<Trip> existing = trips.findByIdempotencyKey(me.tenant(), idempotencyKey);
    if (existing.isPresent()) {
      Trip trip = existing.get();
      if (!trip.requestFingerprint().equals(fingerprint)) {
        throw new ApiException.Unprocessable(
            "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used with a different request");
      }
      return trip;
    }

    Instant now = clock.instant();
    Trip trip =
        new Trip(
            Ids.newId(IdPrefix.TRIP),
            me.tenant(),
            travelerId,
            TripStatus.SUBMITTED,
            command.source(),
            command.requestText(),
            command.intent(),
            TripEvidence.NONE,
            me.principal(),
            idempotencyKey,
            fingerprint,
            0,
            now,
            now);
    trips.insert(trip);
    trips.appendHistory(trip, null, TripStatus.SUBMITTED, null, me.principal(), now);
    outbox.append(TripEvents.created(trip, null, clock));
    return trip;
  }

  @Transactional(readOnly = true)
  public Trip get(RequestPrincipal me, String tripId) {
    return trips
        .find(me.tenant(), tripId)
        .filter(trip -> TripAccess.canRead(me, trip))
        // 404, not 403: the caller learns nothing about trips they cannot see.
        .orElseThrow(() -> new ApiException.NotFound("trip", tripId));
  }

  @Transactional(readOnly = true)
  public List<Trip> listMine(RequestPrincipal me, int limit) {
    return trips.listForTraveler(me.tenant(), me.employeeIdOrThrow(), limit);
  }

  @Transactional(readOnly = true)
  public List<TripRepository.StatusChange> history(RequestPrincipal me, String tripId) {
    Trip trip = get(me, tripId);
    return trips.history(trip.tenantId(), trip.tripId());
  }

  /** Idempotent by state: cancelling a cancelled trip returns it unchanged. */
  @Transactional
  public Trip cancel(RequestPrincipal me, String tripId, String reason) {
    Trip trip =
        trips
            .find(me.tenant(), tripId)
            .filter(t -> TripAccess.canCancel(me, t))
            .orElseThrow(() -> new ApiException.NotFound("trip", tripId));
    if (trip.status() == TripStatus.CANCELLED) {
      return trip;
    }
    if (!trip.status().canTransitionTo(TripStatus.CANCELLED)) {
      throw new ApiException.Conflict(
          "TRIP_NOT_CANCELLABLE", "trip in status " + trip.status() + " cannot be cancelled here");
    }
    Instant now = clock.instant();
    Trip cancelled = trip.withStatus(TripStatus.CANCELLED, now);
    if (!trips.updateStatus(cancelled, trip.version())) {
      throw new ApiException.Conflict(
          "TRIP_MODIFIED_CONCURRENTLY", "trip changed while cancelling; re-read and retry");
    }
    trips.appendHistory(trip, trip.status(), TripStatus.CANCELLED, reason, me.principal(), now);
    outbox.append(TripEvents.cancelled(cancelled, reason, me.principal(), null, clock));
    return cancelled;
  }

  /** The create command, decoupled from the HTTP shape. */
  public record CreateTrip(
      @Nullable String travelerId,
      TripSource source,
      @Nullable String requestText,
      @Nullable TravelIntent intent) {

    public CreateTrip {
      if ((requestText == null || requestText.isBlank()) && intent == null) {
        throw new IllegalArgumentException("a trip needs a request text or a structured intent");
      }
    }

    /** SHA-256 over the canonical form so identical retries match and different bodies do not. */
    String fingerprint(String resolvedTravelerId) {
      String canonical =
          String.join(
              "|",
              resolvedTravelerId,
              source.name(),
              requestText == null ? "" : requestText.strip(),
              intent == null ? "" : intent.toString());
      return Fingerprints.sha256Hex(canonical);
    }
  }
}
