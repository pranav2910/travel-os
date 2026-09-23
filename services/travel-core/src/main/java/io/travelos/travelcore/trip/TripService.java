package io.travelos.travelcore.trip;

import io.travelos.common.identity.Principal;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import io.travelos.spring.outbox.Outbox;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import io.travelos.travelcore.approval.Approval;
import io.travelos.travelcore.approval.ApprovalRepository;
import io.travelos.travelcore.approval.ApprovalSignaler;
import io.travelos.workflows.TripPlanning;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripService {

  /** Slice 1 scope is a product decision, enforced here, not a TODO in a comment. */
  private static final int SLICE_1_MAX_TRAVELERS = 1;

  private final TripRepository trips;
  private final ApprovalRepository approvals;
  private final TripComponentRepository components;
  private final AgentDecisionRepository ledger;
  private final ApprovalSignaler signaler;
  private final Outbox outbox;
  private final Clock clock;

  public TripService(
      TripRepository trips,
      ApprovalRepository approvals,
      AgentDecisionRepository ledger,
      ApprovalSignaler signaler,
      Outbox outbox,
      Clock clock,
      TripComponentRepository components) {
    this.trips = trips;
    this.approvals = approvals;
    this.ledger = ledger;
    this.signaler = signaler;
    this.outbox = outbox;
    this.clock = clock;
    this.components = components;
  }

  /**
   * Creates a trip, or returns the one already created with the same Idempotency-Key. The key is
   * scoped by tenant; reusing it with a different body is a client bug and gets 422.
   */
  @Transactional
  public Trip create(RequestPrincipal me, CreateTrip command, String idempotencyKey) {
    return create(me, command, idempotencyKey, null);
  }

  /**
   * Slice 4: the same creation for a trusted service acting for a person (Enterprise Context
   * converting a detected demand). {@code me} is the person as that service validated them; {@code
   * sourceReference} names what the trip was made from and lands in travel.trip.created.
   */
  @Transactional
  public Trip create(
      RequestPrincipal me,
      CreateTrip command,
      String idempotencyKey,
      @Nullable String sourceReference) {
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
    // The snapshot is the requester's own claims when they travel themselves; an arranger's trip
    // for someone else only knows the traveler id until Enterprise Context supplies the profile.
    TravelerSnapshot traveler =
        travelerId.equals(me.employeeId())
            ? TravelerSnapshot.of(travelerId, me)
            : new TravelerSnapshot(travelerId, "", "", "");
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
            now,
            traveler,
            null,
            null,
            null,
            null,
            sourceReference);
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

  /** Internal (workflow) read: tenant-scoped, no per-person visibility rule. */
  @Transactional(readOnly = true)
  public Trip getInternal(TenantId tenant, String tripId) {
    return trips.find(tenant, tripId).orElseThrow(() -> new ApiException.NotFound("trip", tripId));
  }

  @Transactional(readOnly = true)
  public Optional<Approval> latestApproval(TenantId tenant, String tripId) {
    return approvals.latestForTrip(tenant, tripId);
  }

  @Transactional(readOnly = true)
  public List<Trip> listMine(RequestPrincipal me, int limit) {
    return trips.listForTraveler(me.tenant(), me.employeeIdOrThrow(), limit);
  }

  /**
   * The tenant's trips for the roles that may read any of them (an approver's inbox). A traveler
   * asking for the tenant scope is refused: the list would show trips they may not read.
   */
  @Transactional(readOnly = true)
  public List<Trip> listForTenant(RequestPrincipal me, @Nullable TripStatus status, int limit) {
    if (!TripAccess.canReadTenantWide(me)) {
      throw new ApiException.Forbidden(
          "NOT_TENANT_WIDE", "MANAGER, TRAVEL_ADMIN or FINANCE role required for scope=tenant");
    }
    return trips.listForTenant(me.tenant(), status, limit);
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
    if (!trips.update(cancelled, trip.version())) {
      throw new ApiException.Conflict(
          "TRIP_MODIFIED_CONCURRENTLY", "trip changed while cancelling; re-read and retry");
    }
    trips.appendHistory(trip, trip.status(), TripStatus.CANCELLED, reason, me.principal(), now);
    outbox.append(TripEvents.cancelled(cancelled, reason, me.principal(), null, clock));
    return cancelled;
  }

  /**
   * Slice 5: a person attests that the trip took place. The traveler may do so once the last leg's
   * deadline has passed; a travel admin at any time (e.g. from expense reconciliation). Idempotent
   * by state. Nothing else ever marks a trip completed: a booked trip is not a completed one.
   */
  @Transactional
  public Trip complete(RequestPrincipal me, String tripId) {
    Trip trip =
        trips
            .find(me.tenant(), tripId)
            .filter(t -> TripAccess.canRead(me, t))
            .orElseThrow(() -> new ApiException.NotFound("trip", tripId));
    if (trip.status() == TripStatus.COMPLETED) {
      return trip;
    }
    boolean owner = trip.travelerId().equals(me.employeeId());
    if (!owner && !me.hasRole("TRAVEL_ADMIN")) {
      throw new ApiException.Forbidden(
          "NOT_ALLOWED", "only the traveler or a travel admin may attest completion");
    }
    if (trip.status() != TripStatus.BOOKED) {
      throw new ApiException.Conflict(
          "TRIP_NOT_BOOKED", "only a BOOKED trip can be completed; this one is " + trip.status());
    }
    Instant now = clock.instant();
    if (owner && !me.hasRole("TRAVEL_ADMIN") && trip.intent() != null) {
      Instant lastArrival =
          trip.intent().itinerary() != null
              ? trip.intent().itinerary().lastArrival()
              : (trip.intent().latestReturn() != null
                  ? trip.intent().latestReturn()
                  : trip.intent().arrivalDeadline());
      if (now.isBefore(lastArrival)) {
        throw new ApiException.Conflict(
            "TRIP_NOT_OVER", "the trip's last arrival is " + lastArrival + "; attest after it");
      }
    }
    Trip completed = trip.withStatus(TripStatus.COMPLETED, now);
    if (!trips.update(completed, trip.version())) {
      throw new ApiException.Conflict(
          "TRIP_MODIFIED_CONCURRENTLY", "trip changed while completing; re-read and retry");
    }
    trips.appendHistory(
        trip,
        trip.status(),
        TripStatus.COMPLETED,
        "completed, attested by " + me.principal().id(),
        me.principal(),
        now);
    outbox.append(TripEvents.completed(completed, null, clock));
    return completed;
  }

  /**
   * The workflow's lever. Validates the lifecycle, records evidence, creates the approval when
   * entering AWAITING_APPROVAL, and publishes the matching event. Idempotent: asking for the
   * current status returns the trip unchanged.
   */
  @Transactional
  public Trip transition(TenantId tenant, Principal actor, Transition t) {
    Trip trip = getInternal(tenant, t.tripId());
    if (trip.status() == t.to()) {
      return trip;
    }
    if (!trip.status().canTransitionTo(t.to())) {
      throw new IllegalStateException(
          "trip " + trip.tripId() + " cannot go from " + trip.status() + " to " + t.to());
    }
    Instant now = clock.instant();
    Trip next =
        trip.withStatus(t.to(), now)
            .withEvidence(
                trip.evidence()
                    .merge(
                        t.selectedBundleId(),
                        t.optimizationRunId(),
                        t.policyDecisionId(),
                        null,
                        t.orderId()),
                t.total());
    Approval approval = null;
    if (t.to() == TripStatus.AWAITING_APPROVAL) {
      String role =
          t.approverRole() == null || t.approverRole().isBlank() ? "MANAGER" : t.approverRole();
      String policyDecisionId = next.evidence().policyDecisionId();
      approval =
          approvals
              .pendingForTrip(tenant, trip.tripId())
              .orElseGet(
                  () -> {
                    Approval created =
                        new Approval(
                            Ids.newId(IdPrefix.APPROVAL),
                            tenant,
                            trip.tripId(),
                            role,
                            Approval.Status.PENDING,
                            policyDecisionId,
                            now,
                            null,
                            null,
                            null,
                            null);
                    approvals.insert(created);
                    return created;
                  });
      next =
          next.withEvidence(
              next.evidence().merge(null, null, null, approval.approvalId(), null), null);
    }
    if (t.to() == TripStatus.FAILED) {
      next = next.withFailure(t.failureStage(), t.failureCode());
    }
    boolean replanned =
        t.to() == TripStatus.AWAITING_APPROVAL && trip.status() == TripStatus.APPROVED;
    if (t.explanation() != null && !t.explanation().isBlank()) {
      next = next.withExplanation(t.explanation());
    }
    if (!trips.update(next, trip.version())) {
      throw new IllegalStateException("trip " + trip.tripId() + " changed concurrently; retry");
    }
    trips.appendHistory(trip, trip.status(), t.to(), t.reason(), actor, now);
    switch (t.to()) {
      case AWAITING_APPROVAL -> {
        if (replanned) {
          outbox.append(
              TripEvents.replanned(
                  next,
                  t.replanReason() == null ? "PRICE_CHANGED" : t.replanReason(),
                  trip.total(),
                  true,
                  trip.evidence().approvalId(),
                  t.causationId(),
                  clock));
        }
        outbox.append(TripEvents.planned(next, true, t.causationId(), clock));
        outbox.append(TripEvents.approvalRequested(next, approval, clock));
      }
      case APPROVED -> {
        if (trip.status() == TripStatus.PLANNING) {
          outbox.append(TripEvents.planned(next, false, t.causationId(), clock));
        }
      }
      case PLANNING -> {
        if (trip.status() == TripStatus.APPROVED) {
          // the approved plan's quote expired: the trip is planned again from a fresh search
          outbox.append(
              TripEvents.replanned(
                  next,
                  t.replanReason() == null ? "QUOTE_EXPIRED" : t.replanReason(),
                  trip.total(),
                  false,
                  trip.evidence().approvalId(),
                  t.causationId(),
                  clock));
        }
      }
      case BOOKED ->
          outbox.append(
              TripEvents.booked(
                  next, components.list(tenant, trip.tripId()), t.causationId(), clock));
      case FAILED ->
          outbox.append(
              TripEvents.failed(
                  next,
                  t.failureStage(),
                  t.failureCode(),
                  t.reason(),
                  components.list(tenant, trip.tripId()),
                  t.causationId(),
                  clock));
      case CANCELLED ->
          outbox.append(
              TripEvents.cancelled(
                  next,
                  t.reason() == null ? "cancelled" : t.reason(),
                  actor,
                  t.causationId(),
                  clock));
      default -> {}
    }
    return next;
  }

  /**
   * Records what intent extraction concluded. EXTRACTED freezes the intent on a SUBMITTED trip;
   * every outcome is ledgered with its model-call evidence and published. Idempotent per model
   * call: a retried activity finds its call already ledgered and changes nothing.
   */
  @Transactional
  public Trip applyIntentExtraction(TenantId tenant, Principal actor, IntentExtraction x) {
    Trip trip = getInternal(tenant, x.tripId());
    if (x.call() == null || !Ids.isValid(IdPrefix.MODEL_CALL, x.call().callId())) {
      throw new IllegalArgumentException("intent extraction needs model-call evidence (llm_ id)");
    }
    Instant now = clock.instant();
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("method", "FREE_TEXT");
    if (!x.missingFields().isEmpty()) {
      detail.put("missingFields", x.missingFields());
    }
    if (x.clarifyingQuestion() != null && !x.clarifyingQuestion().isBlank()) {
      detail.put("clarifyingQuestion", x.clarifyingQuestion());
    }
    boolean fresh =
        ledger.insertIfAbsent(
            new AgentDecision(
                Ids.newId(IdPrefix.DECISION),
                tenant,
                trip.tripId(),
                actor,
                AgentDecision.INTENT_EXTRACTION,
                x.result(),
                x.confidence(),
                x.assumptions(),
                detail,
                x.call(),
                now));
    if (!fresh) {
      return trip;
    }
    if (!"EXTRACTED".equals(x.result())) {
      outbox.append(TripEvents.intentRejected(trip, x, actor, clock));
      return trip;
    }
    if (x.intent() == null) {
      throw new IllegalArgumentException("EXTRACTED needs an intent");
    }
    TravelIntent understood;
    try {
      understood = x.intent().withExplicitStay();
    } catch (TravelIntent.HotelRequestException e) {
      // The model understood a hotel the platform cannot pin to nights: refuse now, before any
      // planning, with the same actionable code the API returns. The ledger keeps the extraction.
      if (trip.status() == TripStatus.SUBMITTED) {
        transition(
            tenant,
            actor,
            new Transition(
                trip.tripId(),
                TripStatus.FAILED,
                e.getMessage(),
                null,
                null,
                null,
                null,
                null,
                null,
                "INTENT",
                e.code(),
                null,
                null));
      }
      return getInternal(tenant, trip.tripId());
    }
    if (trip.intent() != null) {
      if (trip.intent().equals(understood)) {
        return trip;
      }
      throw new IllegalStateException("trip " + trip.tripId() + " already has a frozen intent");
    }
    if (trip.status() != TripStatus.SUBMITTED) {
      throw new IllegalStateException(
          "intent can only be set on a SUBMITTED trip, not " + trip.status());
    }
    Trip next = trip.withIntent(understood, now);
    if (!trips.update(next, trip.version())) {
      throw new IllegalStateException("trip " + trip.tripId() + " changed concurrently; retry");
    }
    trips.appendHistory(
        trip,
        trip.status(),
        trip.status(),
        String.format(
            java.util.Locale.ROOT,
            "intent extracted from free text by %s (confidence %.2f)",
            x.call().model(),
            x.confidence()),
        actor,
        now);
    outbox.append(TripEvents.intentDetected(next, x, actor, clock));
    return next;
  }

  @Transactional(readOnly = true)
  public List<AgentDecision> agentDecisions(RequestPrincipal me, String tripId) {
    Trip trip = get(me, tripId);
    return ledger.listForTrip(trip.tenantId(), trip.tripId());
  }

  /** What the LLM gateway concluded, as relayed by the workflow. */
  public record IntentExtraction(
      String tripId,
      String result,
      @Nullable TravelIntent intent,
      List<String> missingFields,
      @Nullable String clarifyingQuestion,
      List<String> assumptions,
      double confidence,
      AgentDecision.@Nullable ModelCallEvidence call,
      @Nullable String causationId) {}

  /** A manager or travel admin (never the traveler) decides the pending approval. */
  @Transactional
  public Approval decideApproval(
      RequestPrincipal me,
      String tripId,
      Approval.Status decision,
      @Nullable String comment,
      String idempotencyKey) {
    if (decision == Approval.Status.PENDING) {
      throw new IllegalArgumentException("decision must be APPROVED or REJECTED");
    }
    Trip trip =
        trips
            .find(me.tenant(), tripId)
            .filter(t -> TripAccess.canRead(me, t))
            .orElseThrow(() -> new ApiException.NotFound("trip", tripId));
    if (!me.hasAnyRole("MANAGER", "TRAVEL_ADMIN")) {
      throw new ApiException.Forbidden(
          "NOT_AN_APPROVER", "only MANAGER or TRAVEL_ADMIN may decide approvals");
    }
    if (trip.travelerId().equals(me.employeeId())) {
      throw new ApiException.Forbidden("SELF_APPROVAL", "a traveler cannot approve their own trip");
    }
    Optional<Approval> pending = approvals.pendingForTrip(me.tenant(), tripId);
    if (pending.isEmpty()) {
      Approval latest =
          approvals
              .latestForTrip(me.tenant(), tripId)
              .orElseThrow(
                  () ->
                      new ApiException.Conflict(
                          "NO_PENDING_APPROVAL", "this trip is not awaiting approval"));
      if (idempotencyKey.equals(latest.decisionIdempotencyKey())) {
        return latest;
      }
      throw new ApiException.Conflict(
          "APPROVAL_ALREADY_DECIDED",
          "approval " + latest.approvalId() + " was already " + latest.status());
    }
    Instant now = clock.instant();
    if (!approvals.decide(
        pending.get(), decision, me.principal().id(), comment, idempotencyKey, now)) {
      throw new ApiException.Conflict("APPROVAL_ALREADY_DECIDED", "decided concurrently; re-read");
    }
    Approval decided = approvals.find(me.tenant(), pending.get().approvalId()).orElseThrow();
    outbox.append(TripEvents.approvalDecided(trip, decided, me.principal(), comment, clock));
    signaler.approvalDecided(
        trip.tripId(),
        new TripPlanning.ApprovalDecision(
            decided.approvalId(), decided.status().name(), me.principal().id(), comment));
    return decided;
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
              intent == null ? "" : intent.canonical());
      return Fingerprints.sha256Hex(canonical);
    }
  }

  /** A lifecycle move requested by the workflow. Blank evidence fields keep existing values. */
  public record Transition(
      String tripId,
      TripStatus to,
      @Nullable String reason,
      @Nullable String selectedBundleId,
      @Nullable String optimizationRunId,
      @Nullable String policyDecisionId,
      @Nullable String orderId,
      @Nullable Money total,
      @Nullable String approverRole,
      @Nullable String failureStage,
      @Nullable String failureCode,
      @Nullable String causationId,
      @Nullable String explanation,
      @Nullable String replanReason) {
    public Transition(
        String tripId,
        TripStatus to,
        @Nullable String reason,
        @Nullable String selectedBundleId,
        @Nullable String optimizationRunId,
        @Nullable String policyDecisionId,
        @Nullable String orderId,
        @Nullable Money total,
        @Nullable String approverRole,
        @Nullable String failureStage,
        @Nullable String failureCode,
        @Nullable String causationId,
        @Nullable String explanation) {
      this(
          tripId,
          to,
          reason,
          selectedBundleId,
          optimizationRunId,
          policyDecisionId,
          orderId,
          total,
          approverRole,
          failureStage,
          failureCode,
          causationId,
          explanation,
          null);
    }
  }

  // ------------------------------------------------------------------ Slice 3: components

  /** The workflow reports where each component stands; the same report twice changes nothing. */
  @Transactional
  public Trip updateComponents(TenantId tenant, String tripId, List<TripComponent> states) {
    Trip trip = getInternal(tenant, tripId);
    for (TripComponent c : states) {
      components.upsert(tenant, tripId, c);
    }
    return trip;
  }

  @Transactional(readOnly = true)
  public List<TripComponent> components(TenantId tenant, String tripId) {
    return components.list(tenant, tripId);
  }

  @Transactional(readOnly = true)
  public List<TripComponent> components(RequestPrincipal me, String tripId) {
    Trip trip = get(me, tripId);
    return components.list(trip.tenantId(), trip.tripId());
  }
}
