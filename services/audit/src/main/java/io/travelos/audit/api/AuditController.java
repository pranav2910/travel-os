package io.travelos.audit.api;

import io.travelos.audit.store.AuditRecord;
import io.travelos.audit.store.AuditRepository;
import io.travelos.audit.store.TripIndexEntry;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only. The trail is what the platform said happened, in order; the ledger is that trail
 * assembled into an answer to "why". Access mirrors Travel Core: your own trips, or an oversight
 * role. Unknown and unauthorized both read as 404.
 */
@RestController
@RequestMapping(path = "/api/v1/audit", produces = "application/json")
public class AuditController {

  private final AuditRepository repository;

  public AuditController(AuditRepository repository) {
    this.repository = repository;
  }

  public record TripTrail(String tripId, String travelerId, List<AuditRecord> events) {}

  @GetMapping("/trips/{tripId}")
  public TripTrail trail(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String tripId) {
    TripIndexEntry trip = visibleTrip(me, tripId);
    return new TripTrail(tripId, trip.travelerId(), repository.trail(me.tenant(), tripId));
  }

  @GetMapping("/trips/{tripId}/decisions")
  public DecisionLedger decisions(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String tripId) {
    TripIndexEntry trip = visibleTrip(me, tripId);
    return DecisionLedger.from(tripId, trip.travelerId(), repository.trail(me.tenant(), tripId));
  }

  public record DemandTrail(String candidateId, String travelerId, List<AuditRecord> events) {}

  /**
   * Slice 4: everything that happened to a detected travel demand, from detection to conversion.
   */
  @GetMapping("/demand/{candidateId}")
  public DemandTrail demand(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String candidateId) {
    TripIndexEntry entry =
        repository
            .findTrip(me.tenant(), candidateId)
            .filter(t -> AuditAccess.canReadTrip(me, t))
            .orElseThrow(() -> new ApiException.NotFound("demand candidate", candidateId));
    return new DemandTrail(
        candidateId, entry.travelerId(), repository.trail(me.tenant(), candidateId));
  }

  /** Tenant-wide exploration for TRAVEL_ADMIN / FINANCE: "every order confirmed this week". */
  @GetMapping("/events")
  public List<AuditRecord> events(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam String type,
      @RequestParam(required = false) @Nullable Instant from,
      @RequestParam(required = false) @Nullable Instant to,
      @RequestParam(defaultValue = "100") int limit) {
    if (!AuditAccess.canQueryEvents(me)) {
      throw new ApiException.Forbidden(
          "NOT_AN_AUDITOR", "only TRAVEL_ADMIN or FINANCE may query the audit log");
    }
    return repository.byType(me.tenant(), type, from, to, Math.clamp(limit, 1, 1000));
  }

  private TripIndexEntry visibleTrip(RequestPrincipal me, String tripId) {
    return repository
        .findTrip(me.tenant(), tripId)
        .filter(trip -> AuditAccess.canReadTrip(me, trip))
        .orElseThrow(() -> new ApiException.NotFound("trip", tripId));
  }
}
