package io.travelos.travelcore.api;

import io.opentelemetry.api.trace.Span;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import io.travelos.spring.web.idempotency.IdempotencyKeyHeader;
import io.travelos.travelcore.trip.AgentDecision;
import io.travelos.travelcore.trip.IntentRejectedException;
import io.travelos.travelcore.trip.TravelIntent;
import io.travelos.travelcore.trip.Trip;
import io.travelos.travelcore.trip.TripRepository;
import io.travelos.travelcore.trip.TripService;
import io.travelos.travelcore.trip.TripSource;
import io.travelos.travelcore.trip.TripStatus;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/trips", produces = "application/json")
public class TripController {

  /** Lists are bounded; a larger page is a refused request, not a silently shorter one. */
  static final int MAX_LIMIT = 200;

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
              intent,
              request.traveler() == null ? null : request.traveler().toDomain(),
              request.projectId() == null || request.projectId().isBlank()
                  ? null
                  : request.projectId(),
              request.purchaseMode(),
              Boolean.TRUE.equals(request.draft()));
    } catch (TravelIntent.HotelRequestException e) {
      throw new ApiException.Unprocessable(e.code(), e.getMessage());
    } catch (IntentRejectedException e) {
      throw new ApiException.Unprocessable(e.code(), e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ApiException.Unprocessable("INTENT_INVALID", e.getMessage());
    }
    Trip trip;
    try {
      trip = trips.create(me, command, idempotencyKey);
    } catch (IntentRejectedException e) {
      throw new ApiException.Unprocessable(e.code(), e.getMessage());
    }
    Span.current().setAttribute("trip.id", trip.tripId());
    Span.current().setAttribute("tenant.id", trip.tenantId().value());
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/trips/" + trip.tripId()))
        .body(TripResponse.from(trip));
  }

  /** Phase 3: a draft becomes a request and planning starts; idempotent by state. */
  @PostMapping(path = "/{tripId}/submission")
  public ResponseEntity<TripResponse> submit(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String tripId,
      @IdempotencyKeyHeader String idempotencyKey) {
    return ResponseEntity.accepted().body(TripResponse.from(trips.submitDraft(me, tripId)));
  }

  /** Phase 3: change a draft before submitting it (request text, intent, purchase mode). */
  @PutMapping(path = "/{tripId}/draft", consumes = "application/json")
  public TripResponse updateDraft(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String tripId,
      @Valid @RequestBody CreateTripRequest request) {
    TripService.CreateTrip changes;
    try {
      changes =
          new TripService.CreateTrip(
              null,
              request.source() == null ? TripSource.API : request.source(),
              request.request(),
              request.intent() == null ? null : request.intent().toDomain(),
              null,
              null,
              request.purchaseMode(),
              true);
    } catch (IntentRejectedException e) {
      throw new ApiException.Unprocessable(e.code(), e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ApiException.Unprocessable("INTENT_INVALID", e.getMessage());
    }
    try {
      return TripResponse.from(trips.updateDraft(me, tripId, changes));
    } catch (IntentRejectedException e) {
      throw new ApiException.Unprocessable(e.code(), e.getMessage());
    }
  }

  public record PurchaseRequest(@Nullable String bundleId) {}

  /**
   * Phase 3: the buyer authorizes the purchase of the quoted plan at the quoted price. Bound to the
   * plan, total, conditions and quote expiry; idempotent (same key or same plan = the same
   * authorization, one booking attempt). 409 when the trip is not QUOTED, the selection moved on
   * ({@code SELECTION_CHANGED}) or the quote expired ({@code QUOTE_EXPIRED}).
   */
  @PostMapping(path = "/{tripId}/purchase")
  public TripResponse.PurchaseView purchase(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String tripId,
      @IdempotencyKeyHeader String idempotencyKey,
      @RequestBody(required = false) @Nullable PurchaseRequest request) {
    return TripResponse.PurchaseView.from(
        trips.authorizePurchase(
            me, tripId, request == null ? null : request.bundleId(), idempotencyKey));
  }

  /**
   * Every purchase authorization the trip held, newest first: who authorized what, and its fate.
   */
  @GetMapping("/{tripId}/purchase")
  public List<TripResponse.PurchaseView> purchases(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String tripId) {
    return trips.purchaseHistory(me, tripId).stream().map(TripResponse.PurchaseView::from).toList();
  }

  public record SelectionRequest(@jakarta.validation.constraints.NotBlank String bundleId) {}

  /** Phase 3: choose another quoted alternative; the workflow re-quotes it. */
  @PostMapping(path = "/{tripId}/selection", consumes = "application/json")
  public TripResponse select(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String tripId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody SelectionRequest request) {
    return TripResponse.from(trips.selectAlternative(me, tripId, request.bundleId()));
  }

  /**
   * Phase 3: ask for a fresh price on the quoted plan. 202: the workflow answers with a new quote.
   */
  @PostMapping(path = "/{tripId}/quote-refresh")
  public ResponseEntity<TripResponse> refreshQuote(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String tripId,
      @IdempotencyKeyHeader String idempotencyKey) {
    return ResponseEntity.accepted().body(TripResponse.from(trips.refreshQuote(me, tripId)));
  }

  @GetMapping("/{tripId}")
  public TripResponse get(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String tripId) {
    Trip trip = trips.get(me, tripId);
    return TripResponse.from(
        trip,
        trips.latestApproval(trip.tenantId(), trip.tripId()).orElse(null),
        trips.components(trip.tenantId(), trip.tripId()),
        trips.allocation(trip.tenantId(), trip.tripId()).orElse(null),
        trips.activePurchase(trip.tenantId(), trip.tripId()).orElse(null));
  }

  /** Slice 3: component status, total and supplier references, one row per leg/stay/transfer. */
  @GetMapping("/{tripId}/components")
  public List<TripResponse.ComponentView> components(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String tripId) {
    return trips.components(me, tripId).stream().map(TripResponse.ComponentView::from).toList();
  }

  /**
   * {@code scope=mine} (default): the caller's own trips. {@code scope=arranged}: trips the caller
   * arranged for others. {@code scope=tenant}: for TRAVEL_ADMIN / FINANCE every trip of the tenant;
   * for a MANAGER the trips of their reports and the ones they arranged (an approval inbox is
   * {@code scope=tenant&status=AWAITING_APPROVAL}). {@code status} filters every scope.
   */
  @GetMapping
  public List<TripResponse> list(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "mine") String scope,
      @RequestParam(required = false) @Nullable String status) {
    TripStatus wanted = null;
    if (status != null && !status.isBlank()) {
      try {
        wanted = TripStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new ApiException.Unprocessable("STATUS_UNKNOWN", "unknown status " + status);
      }
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new ApiException.Unprocessable(
          "LIMIT_OUT_OF_RANGE", "limit must be between 1 and " + MAX_LIMIT + ", got " + limit);
    }
    int n = limit;
    List<Trip> found =
        switch (scope) {
          case "mine" -> trips.listMine(me, n);
          case "arranged" -> trips.listArranged(me, n);
          case "tenant" -> trips.listForTenant(me, wanted, n);
          default ->
              throw new ApiException.Unprocessable(
                  "SCOPE_UNKNOWN", "scope must be mine, arranged or tenant");
        };
    TripStatus filter = wanted;
    return found.stream()
        .filter(t -> filter == null || t.status() == filter)
        .map(TripResponse::from)
        .toList();
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

  /** Slice 5: completion is attested (traveler after the trip, or a travel admin); idempotent. */
  @PostMapping(path = "/{tripId}/completion")
  public TripResponse complete(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String tripId,
      @IdempotencyKeyHeader String idempotencyKey) {
    return TripResponse.from(trips.complete(me, tripId));
  }

  /**
   * Phase 6: release one component of a booked trip (the hotel is not needed, the return flight
   * is). The trip stays BOOKED; the component becomes CANCELLING, then CANCELLED or CANCEL_FAILED
   * with an exposure for a person. Refunds and credits reach the finance ledger per item.
   */
  @PostMapping(
      path = "/{tripId}/components/{componentId}/cancellation",
      consumes = "application/json")
  public ResponseEntity<TripResponse.ComponentView> cancelComponent(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String tripId,
      @PathVariable String componentId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody CancelTripRequest request) {
    return ResponseEntity.accepted()
        .body(
            TripResponse.ComponentView.from(
                trips.cancelComponent(me, tripId, componentId, request.reason())));
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
