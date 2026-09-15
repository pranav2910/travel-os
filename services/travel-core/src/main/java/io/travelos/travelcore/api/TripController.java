package io.travelos.travelcore.api;

import io.opentelemetry.api.trace.Span;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import io.travelos.spring.web.idempotency.IdempotencyKeyHeader;
import io.travelos.travelcore.trip.AgentDecision;
import io.travelos.travelcore.trip.TravelIntent;
import io.travelos.travelcore.trip.Trip;
import io.travelos.travelcore.trip.TripRepository;
import io.travelos.travelcore.trip.TripService;
import io.travelos.travelcore.trip.TripSource;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/trips", produces = "application/json")
public class TripController {

  private final TripService trips;

  public TripController(TripService trips) {
    this.trips = trips;
  }

  /**
   * 202: the trip is accepted; planning continues asynchronously. Poll GET or follow the events.
   */
  @PostMapping(consumes = "application/json")
  public ResponseEntity<TripResponse> create(
      @AuthenticationPrincipal RequestPrincipal me,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody CreateTripRequest request) {
    TripService.CreateTrip command;
    try {
      TravelIntent intent = request.intent() == null ? null : request.intent().toDomain();
      command =
          new TripService.CreateTrip(
              request.travelerId(),
              request.source() == null ? TripSource.API : request.source(),
              request.request(),
              intent);
    } catch (IllegalArgumentException e) {
      throw new ApiException.Unprocessable("INTENT_INVALID", e.getMessage());
    }
    Trip trip = trips.create(me, command, idempotencyKey);
    Span.current().setAttribute("trip.id", trip.tripId());
    Span.current().setAttribute("tenant.id", trip.tenantId().value());
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/trips/" + trip.tripId()))
        .body(TripResponse.from(trip));
  }

  @GetMapping("/{tripId}")
  public TripResponse get(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String tripId) {
    Trip trip = trips.get(me, tripId);
    return TripResponse.from(
        trip,
        trips.latestApproval(trip.tenantId(), trip.tripId()).orElse(null),
        trips.components(trip.tenantId(), trip.tripId()));
  }

  /** Slice 3: component status, total and supplier references, one row per leg/stay/transfer. */
  @GetMapping("/{tripId}/components")
  public List<TripResponse.ComponentView> components(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String tripId) {
    return trips.components(me, tripId).stream().map(TripResponse.ComponentView::from).toList();
  }

  @GetMapping
  public List<TripResponse> listMine(
      @AuthenticationPrincipal RequestPrincipal me, @RequestParam(defaultValue = "50") int limit) {
    return trips.listMine(me, Math.clamp(limit, 1, 200)).stream().map(TripResponse::from).toList();
  }

  /** The agent-decision ledger: what a model concluded about this trip, with its evidence. */
  @GetMapping("/{tripId}/decisions")
  public List<AgentDecision> decisions(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String tripId) {
    return trips.agentDecisions(me, tripId);
  }

  /** The audit trail of status changes: the first piece of the explainability API. */
  @GetMapping("/{tripId}/history")
  public List<TripRepository.StatusChange> history(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String tripId) {
    return trips.history(me, tripId);
  }

  /** Cancellation is a POST that returns 200 with the new state; repeats are idempotent. */
  @PostMapping(path = "/{tripId}/cancellation", consumes = "application/json")
  public TripResponse cancel(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String tripId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody CancelTripRequest request) {
    return TripResponse.from(trips.cancel(me, tripId, request.reason()));
  }
}
