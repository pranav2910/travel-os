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
import io.travelos.travelcore.context.ContextClient;
import io.travelos.workflows.TripPlanning;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripService {

  /** Slice 1 scope is a product decision, enforced here, not a TODO in a comment. */
  private static final int SLICE_1_MAX_TRAVELERS = 1;

  private static final Logger log = LoggerFactory.getLogger(TripService.class);

  private final TripRepository trips;
  private final ApprovalRepository approvals;
  private final TripComponentRepository components;
  private final AgentDecisionRepository ledger;
  private final ApprovalSignaler signaler;
  private final Outbox outbox;
  private final Clock clock;
  private final ContextClient context;
  private final TripAllocationRepository allocations;
  private final PurchaseAuthorizationRepository purchases;
  private final ConversationRepository conversations;

  /** Phase 3: how long a person's purchase authorization stands when the quote names no expiry. */
  static final java.time.Duration DEFAULT_AUTHORIZATION_LIFETIME = java.time.Duration.ofHours(24);

  public TripService(
      TripRepository trips,
      ApprovalRepository approvals,
      AgentDecisionRepository ledger,
      ApprovalSignaler signaler,
      Outbox outbox,
      Clock clock,
      TripComponentRepository components,
      ContextClient context,
      TripAllocationRepository allocations,
      PurchaseAuthorizationRepository purchases,
      ConversationRepository conversations) {
    this.trips = trips;
    this.approvals = approvals;
    this.ledger = ledger;
    this.signaler = signaler;
    this.outbox = outbox;
    this.clock = clock;
    this.components = components;
    this.context = context;
    this.allocations = allocations;
    this.purchases = purchases;
    this.conversations = conversations;
  }

  /** Phase 3: the move to BOOKING found no purchase authorization covering the plan and price. */
  public static final class PurchaseNotAuthorizedException extends IllegalStateException {
    public static final String CODE = "PURCHASE_NOT_AUTHORIZED";

    public PurchaseNotAuthorizedException(String message) {
      super(CODE + ": " + message);
    }
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
    // Who may arrange for whom is Enterprise Context's answer (self, HRIS manager, guest sponsor,
    // an explicit grant, TRAVEL_ADMIN), never a claim in the request. Without Enterprise Context
    // the Slice 1 rule stands. Nothing in the body can widen either.
    ContextClient.Authorization authorization = authorize(me, travelerId, command.projectId());
    if (command.intent() != null && command.intent().travelers() > SLICE_1_MAX_TRAVELERS) {
      throw new ApiException.Unprocessable(
          "SLICE_SCOPE_SINGLE_TRAVELER", "this release books trips for one traveler at a time");
    }

    String fingerprint = command.fingerprint(travelerId);
    Optional<Trip> existing = trips.findByIdempotencyKey(me.tenant(), idempotencyKey);
    if (existing.isPresent()) {
      return replay(existing.get(), fingerprint);
    }

    Instant now = clock.instant();
    if (command.intent() != null) {
      // Catalog, clock and currency are checked once, here: a stored trip is never re-judged.
      IntentValidation.check(command.intent(), now);
    }
    // The reservation is made in the traveler's name as their profile states it. Only when
    // Enterprise Context knows no profile does the identity come from elsewhere: the requester's
    // own claims when they travel themselves, or the arranger's statement of who they book for.
    TravelerSnapshot traveler;
    ContextClient.@Nullable Snapshot profile =
        profile(me, authorization, travelerId, command.projectId());
    if (profile != null) {
      traveler =
          new TravelerSnapshot(
              travelerId, profile.givenName(), profile.familyName(), profile.email());
    } else if (travelerId.equals(me.employeeId())) {
      traveler = TravelerSnapshot.of(travelerId, me);
    } else if (command.traveler() != null && command.traveler().complete()) {
      traveler = command.traveler().snapshot(travelerId);
    } else {
      throw new ApiException.Unprocessable(
          "TRAVELER_IDENTITY_REQUIRED",
          "a trip for another traveler needs traveler.givenName, traveler.familyName and"
              + " traveler.email: the reservation is made in their name");
    }
    TripStatus initial = command.draft() ? TripStatus.DRAFT : TripStatus.SUBMITTED;
    Trip trip =
        new Trip(
            Ids.newId(IdPrefix.TRIP),
            me.tenant(),
            travelerId,
            initial,
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
            sourceReference,
            command.purchaseMode(),
            null,
            List.of());
    if (trips.insert(trip) == 0) {
      // Two identical requests raced past the lookup above: the unique key let exactly one in.
      // The loser answers with the winner's trip, the same way a sequential retry would.
      return trips
          .findByIdempotencyKey(me.tenant(), idempotencyKey)
          .map(winner -> replay(winner, fingerprint))
          .orElseThrow(
              () ->
                  new ApiException.Conflict(
                      "TRIP_CREATE_RACE", "the request raced a concurrent create; retry"));
    }
    if (profile != null) {
      ContextClient.Allocation a = profile.allocation();
      allocations.insert(
          me.tenant(),
          new TripAllocation(
              trip.tripId(),
              a.departmentId(),
              a.costCenterId(),
              a.legalEntityId(),
              a.officeId(),
              a.projectId(),
              a.projectRestricted(),
              a.managerEmployeeId(),
              me.employeeId(),
              authorization.basis() == null ? "SELF" : authorization.basis(),
              profile.kind(),
              profile.profileVersion(),
              now));
    }
    trips.appendHistory(trip, null, initial, null, me.principal(), now);
    if (!command.draft()) {
      // A draft is not a request yet: nothing is planned, nothing is announced, until submission.
      outbox.append(TripEvents.created(trip, null, clock));
    }
    return trip;
  }

  /** Phase 3: a draft becomes a request; planning starts. Idempotent by state. */
  @Transactional
  public Trip submitDraft(RequestPrincipal me, String tripId) {
    Trip trip = requireDraftOwner(me, tripId);
    if (trip.status() != TripStatus.DRAFT) {
      return trip;
    }
    Instant now = clock.instant();
    if (trip.intent() != null) {
      IntentValidation.check(trip.intent(), now);
    }
    Trip submitted = trip.withStatus(TripStatus.SUBMITTED, now);
    if (!trips.update(submitted, trip.version())) {
      throw new ApiException.Conflict("TRIP_CHANGED", "the draft changed concurrently; re-read");
    }
    trips.appendHistory(
        trip, TripStatus.DRAFT, TripStatus.SUBMITTED, "submitted", me.principal(), now);
    outbox.append(TripEvents.created(submitted, null, clock));
    return submitted;
  }

  /** Phase 3: a draft's request text, intent or purchase mode change before submission. */
  @Transactional
  public Trip updateDraft(RequestPrincipal me, String tripId, CreateTrip changes) {
    Trip trip = requireDraftOwner(me, tripId);
    if (trip.status() != TripStatus.DRAFT) {
      throw new ApiException.Conflict(
          "TRIP_NOT_A_DRAFT", "trip " + tripId + " is " + trip.status() + "; only drafts change");
    }
    Instant now = clock.instant();
    if (changes.intent() != null) {
      IntentValidation.check(changes.intent(), now);
    }
    Trip next =
        trip.withDraftRequest(
            changes.requestText(),
            changes.intent(),
            changes.purchaseMode(),
            changes.fingerprint(trip.travelerId()),
            now);
    if (!trips.update(next, trip.version())) {
      throw new ApiException.Conflict("TRIP_CHANGED", "the draft changed concurrently; re-read");
    }
    trips.appendHistory(
        trip, TripStatus.DRAFT, TripStatus.DRAFT, "draft updated", me.principal(), now);
    return next;
  }

  private Trip requireDraftOwner(RequestPrincipal me, String tripId) {
    Trip trip = get(me, tripId);
    if (!mayBuy(me, trip)) {
      throw new ApiException.Forbidden(
          "NOT_THE_REQUESTER", "only the traveler, the arranger or a travel admin edits a draft");
    }
    return trip;
  }

  /** Who may confirm a purchase or change a draft: the buyer, never an approver by role. */
  private boolean mayBuy(RequestPrincipal me, Trip trip) {
    if (trip.travelerId().equals(me.employeeId()) || me.hasRole("TRAVEL_ADMIN")) {
      return true;
    }
    return allocations
        .find(trip.tenantId(), trip.tripId())
        .map(a -> me.employeeId() != null && me.employeeId().equals(a.arrangerEmployeeId()))
        .orElse(me.hasRole("MANAGER") && trip.createdBy().id().equals(me.principal().id()));
  }

  // ------------------------------------------------------------------ Phase 3: purchase

  /**
   * A person authorizes the purchase of the quoted plan at the quoted price. Bound to the selected
   * bundle, the total, the conditions, the traveler and the quote's expiry; idempotent by key and
   * by state: confirming twice yields the same authorization and, later, one booking attempt.
   */
  @Transactional
  public PurchaseAuthorization authorizePurchase(
      RequestPrincipal me,
      String tripId,
      @Nullable String expectedBundleId,
      String idempotencyKey) {
    Trip trip = get(me, tripId);
    if (!mayBuy(me, trip)) {
      throw new ApiException.Forbidden(
          "NOT_THE_BUYER",
          "only the traveler, the arranger or a travel admin authorizes a purchase");
    }
    Optional<PurchaseAuthorization> replay =
        purchases.findByIdempotencyKey(me.tenant(), idempotencyKey);
    if (replay.isPresent()) {
      return replay.get();
    }
    if (trip.status() != TripStatus.QUOTED) {
      Optional<PurchaseAuthorization> active = purchases.active(me.tenant(), tripId);
      if (active.isPresent() && active.get().basis() == PurchaseAuthorization.Basis.HUMAN) {
        return active.get();
      }
      throw new ApiException.Conflict(
          "TRIP_NOT_QUOTED",
          "trip " + tripId + " is " + trip.status() + "; there is no quote to confirm");
    }
    String bundle = trip.evidence().selectedBundleId();
    Money total = trip.total();
    if (bundle == null || total == null) {
      throw new ApiException.Conflict("QUOTE_INCOMPLETE", "the quote has no plan or no price yet");
    }
    if (expectedBundleId != null && !expectedBundleId.equals(bundle)) {
      throw new ApiException.Conflict(
          "SELECTION_CHANGED",
          "the trip now quotes "
              + bundle
              + ", not "
              + expectedBundleId
              + "; re-read before confirming");
    }
    Instant now = clock.instant();
    if (trip.quoteExpiresAt() != null && !trip.quoteExpiresAt().isAfter(now)) {
      throw new ApiException.Conflict(
          "QUOTE_EXPIRED", "the quote expired at " + trip.quoteExpiresAt() + "; refresh it first");
    }
    Optional<PurchaseAuthorization> active = purchases.active(me.tenant(), tripId);
    if (active.isPresent()) {
      if (active.get().covers(bundle, total, now)) {
        return active.get();
      }
      purchases.supersede(me.tenant(), active.get().authorizationId(), null, "re-confirmed", now);
    }
    PurchaseAuthorization a =
        new PurchaseAuthorization(
            Ids.newId(IdPrefix.PURCHASE_AUTHORIZATION),
            me.tenant(),
            tripId,
            PurchaseAuthorization.Status.ACTIVE,
            PurchaseAuthorization.Basis.HUMAN,
            me.principal().id(),
            bundle,
            total,
            selectedConditions(trip),
            trip.travelerId(),
            allocations
                .find(trip.tenantId(), tripId)
                .map(TripAllocation::profileVersion)
                .orElse(0L),
            trip.version(),
            trip.evidence().policyDecisionId(),
            idempotencyKey,
            trip.quoteExpiresAt() == null
                ? now.plus(DEFAULT_AUTHORIZATION_LIFETIME)
                : trip.quoteExpiresAt(),
            now,
            now,
            null,
            null,
            null);
    if (purchases.insert(a) == 0) {
      // the same key or a concurrent confirmation won: answer with what stands
      return purchases
          .findByIdempotencyKey(me.tenant(), idempotencyKey)
          .or(() -> purchases.active(me.tenant(), tripId))
          .orElseThrow(
              () -> new ApiException.Conflict("PURCHASE_RACE", "confirmation raced; retry"));
    }
    trips.appendHistory(
        trip,
        TripStatus.QUOTED,
        TripStatus.QUOTED,
        "purchase authorized by " + me.principal().id(),
        me.principal(),
        now);
    outbox.append(
        TripEvents.purchaseAuthorized(
            trip, a, active.map(PurchaseAuthorization::authorizationId).orElse(null), null, clock));
    noteConversation(
        trip, "STATUS", "Purchase confirmed for " + total + " by " + me.principal().id() + ".");
    signaler.purchaseAuthorized(
        tripId,
        new TripPlanning.PurchaseAuthorized(a.authorizationId(), bundle, me.principal().id()));
    return a;
  }

  private static @Nullable String selectedConditions(Trip trip) {
    String bundle = trip.evidence().selectedBundleId();
    return trip.alternatives().stream()
        .filter(alt -> alt.bundleId().equals(bundle))
        .map(TripAlternative::conditions)
        .filter(c -> c != null)
        .findFirst()
        .orElse(null);
  }

  /** Every authorization the trip ever held, newest first. */
  @Transactional(readOnly = true)
  public List<PurchaseAuthorization> purchaseHistory(RequestPrincipal me, String tripId) {
    Trip trip = get(me, tripId);
    return purchases.history(trip.tenantId(), trip.tripId());
  }

  @Transactional(readOnly = true)
  public Optional<PurchaseAuthorization> activePurchase(TenantId tenant, String tripId) {
    return purchases.active(tenant, tripId);
  }

  /**
   * A person chooses another of the quoted alternatives. The trip quotes it at its listed price at
   * once (any authorization for the previous plan is superseded) and the workflow re-quotes it.
   */
  @Transactional
  public Trip selectAlternative(RequestPrincipal me, String tripId, String bundleId) {
    Trip trip = get(me, tripId);
    if (!mayBuy(me, trip)) {
      throw new ApiException.Forbidden(
          "NOT_THE_BUYER", "only the buyer chooses between alternatives");
    }
    if (trip.status() != TripStatus.QUOTED) {
      throw new ApiException.Conflict(
          "TRIP_NOT_QUOTED",
          "trip " + tripId + " is " + trip.status() + "; there is nothing to choose");
    }
    if (bundleId.equals(trip.evidence().selectedBundleId())) {
      return trip;
    }
    TripAlternative chosen =
        trip.alternatives().stream()
            .filter(a -> a.bundleId().equals(bundleId))
            .findFirst()
            .orElseThrow(
                () ->
                    new ApiException.Unprocessable(
                        "ALTERNATIVE_UNKNOWN",
                        bundleId + " is not one of this trip's quoted alternatives"));
    Instant now = clock.instant();
    Trip next =
        trip.withStatus(TripStatus.QUOTED, now)
            .withEvidence(trip.evidence().merge(bundleId, null, null, null, null), chosen.total());
    if (!trips.update(next, trip.version())) {
      throw new ApiException.Conflict("TRIP_CHANGED", "the trip changed concurrently; re-read");
    }
    purchases
        .active(trip.tenantId(), tripId)
        .ifPresent(
            a ->
                purchases.supersede(
                    trip.tenantId(),
                    a.authorizationId(),
                    null,
                    "selection changed to " + bundleId,
                    now));
    trips.appendHistory(
        trip, TripStatus.QUOTED, TripStatus.QUOTED, "selected " + bundleId, me.principal(), now);
    outbox.append(TripEvents.quoted(next, "selection changed", null, clock));
    signaler.selectionChanged(
        tripId, new TripPlanning.SelectionChanged(bundleId, me.principal().id()));
    return next;
  }

  /** A person asks for a fresh price on the quoted plan; the workflow answers with a new quote. */
  @Transactional(readOnly = true)
  public Trip refreshQuote(RequestPrincipal me, String tripId) {
    Trip trip = get(me, tripId);
    if (!mayBuy(me, trip)) {
      throw new ApiException.Forbidden("NOT_THE_BUYER", "only the buyer refreshes a quote");
    }
    if (trip.status() != TripStatus.QUOTED) {
      throw new ApiException.Conflict(
          "TRIP_NOT_QUOTED",
          "trip " + tripId + " is " + trip.status() + "; there is no quote to refresh");
    }
    signaler.refreshQuote(tripId, "requested by " + me.principal().id());
    return trip;
  }

  /** Phase 3: a conversation's assistant turn, when the trip belongs to one. */
  private void noteConversation(Trip trip, String kind, String text) {
    if (trip.source() != TripSource.CONVERSATION || trip.sourceReference() == null) {
      return;
    }
    conversations
        .find(trip.tenantId(), trip.sourceReference())
        .ifPresent(
            c ->
                conversations.append(
                    trip.tenantId(),
                    c.conversationId(),
                    Ids.newId(IdPrefix.MESSAGE),
                    Conversation.Role.ASSISTANT,
                    text,
                    trip.tripId(),
                    kind,
                    null,
                    clock.instant()));
  }

  private ContextClient.Authorization authorize(
      RequestPrincipal me, String travelerId, @Nullable String projectId) {
    boolean self = travelerId.equals(me.employeeId());
    if (!context.enabled()) {
      if (!TripAccess.canCreateFor(me, travelerId)) {
        throw new ApiException.Forbidden(
            "NOT_AN_ARRANGER", "only MANAGER or TRAVEL_ADMIN may create trips for other travelers");
      }
      return new ContextClient.Authorization(true, self ? "SELF" : "LEGACY_ROLE", null, false);
    }
    ContextClient.Authorization a;
    try {
      a = context.authorize(me, travelerId, projectId);
    } catch (ContextClient.ContextUnavailableException e) {
      if (self && projectId == null) {
        // A person may always ask for their own travel; the profile is filled in later.
        log.warn(
            "enterprise-context unavailable; {} requests own trip without a profile",
            me.principal().id(),
            e);
        return new ContextClient.Authorization(true, "SELF", null, false);
      }
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CONTEXT_UNAVAILABLE",
          "Enterprise Context did not answer; arranging for another traveler needs its authorization");
    }
    if (a.allowed()) {
      return a;
    }
    String reason = a.reasonCode() == null ? "NOT_AN_ARRANGER" : a.reasonCode();
    if (self && "TRAVELER_UNKNOWN".equals(reason) && projectId == null) {
      // Not yet in the directory (no HRIS sync, no profile): their own travel is still theirs.
      return new ContextClient.Authorization(true, "SELF", null, false);
    }
    throw new ApiException.Forbidden(
        reason,
        switch (reason) {
          case "TRAVELER_INACTIVE" -> "traveler " + travelerId + " is deactivated";
          case "PROJECT_RESTRICTED" ->
              "project " + projectId + " is restricted to its members and their managers";
          case "PROJECT_UNKNOWN" -> "no active project " + projectId;
          case "TRAVELER_UNKNOWN" -> "no traveler " + travelerId;
          default -> me.principal().id() + " may not arrange travel for " + travelerId;
        });
  }

  private ContextClient.@Nullable Snapshot profile(
      RequestPrincipal me,
      ContextClient.Authorization authorization,
      String travelerId,
      @Nullable String projectId) {
    if (!context.enabled() || "LEGACY_ROLE".equals(authorization.basis())) {
      return null;
    }
    try {
      return context.snapshot(me, travelerId, projectId, "TRIP_CREATE").orElse(null);
    } catch (ContextClient.ContextUnavailableException e) {
      if (travelerId.equals(me.employeeId())) {
        log.warn("enterprise-context unavailable; own trip proceeds on claims", e);
        return null;
      }
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CONTEXT_UNAVAILABLE",
          "Enterprise Context did not answer");
    }
  }

  /** The allocation captured at creation, when there is one. */
  @Transactional(readOnly = true)
  public Optional<TripAllocation> allocation(TenantId tenant, String tripId) {
    return allocations.find(tenant, tripId);
  }

  private boolean visible(RequestPrincipal me, Trip trip) {
    return TripAccess.canRead(me, trip, allocations.find(trip.tenantId(), trip.tripId()));
  }

  /** The trip already created with this key, provided the body is the same request. */
  private static Trip replay(Trip trip, String fingerprint) {
    if (!trip.requestFingerprint().equals(fingerprint)) {
      throw new ApiException.Unprocessable(
          "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used with a different request");
    }
    return trip;
  }

  @Transactional(readOnly = true)
  public Trip get(RequestPrincipal me, String tripId) {
    return trips
        .find(me.tenant(), tripId)
        .filter(trip -> visible(me, trip))
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

  /** Trips the caller arranged for others (an arranger's desk). */
  @Transactional(readOnly = true)
  public List<Trip> listArranged(RequestPrincipal me, int limit) {
    return trips.listArrangedBy(me.tenant(), me.employeeIdOrThrow(), limit);
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
    if (me.hasAnyRole("TRAVEL_ADMIN", "FINANCE")) {
      return trips.listForTenant(me.tenant(), status, limit);
    }
    // A manager's inbox: their reports' trips and the ones they arranged, never the tenant's.
    return trips.listForManager(me.tenant(), me.employeeIdOrThrow(), status, limit);
  }

  @Transactional(readOnly = true)
  public List<TripRepository.StatusChange> history(RequestPrincipal me, String tripId) {
    Trip trip = get(me, tripId);
    return trips.history(trip.tenantId(), trip.tripId());
  }

  /**
   * Idempotent by state: cancelling a cancelled (or already cancelling) trip returns it unchanged.
   *
   * <p>A trip that holds no reservation is CANCELLED here and now. A BOOKED trip is not: its
   * reservation is confirmed at the suppliers, so the trip becomes CANCELLING and the Order service
   * is asked to release it (travel.trip.cancellation-requested). CANCELLED is recorded only once
   * every component is released, or CANCELLING stays with an exposure for a person when a supplier
   * refuses: a confirmed reservation is never shown as cancelled before that is established.
   */
  @Transactional
  public Trip cancel(RequestPrincipal me, String tripId, String reason) {
    purchases
        .active(me.tenant(), tripId)
        .ifPresent(
            a ->
                purchases.revoke(
                    me.tenant(), a.authorizationId(), "trip cancelled", clock.instant()));
    Trip trip =
        trips
            .find(me.tenant(), tripId)
            .filter(t -> TripAccess.canCancel(me, t))
            .orElseThrow(() -> new ApiException.NotFound("trip", tripId));
    if (trip.status() == TripStatus.CANCELLED || trip.status() == TripStatus.CANCELLING) {
      return trip;
    }
    TripStatus to =
        trip.status() == TripStatus.BOOKED ? TripStatus.CANCELLING : TripStatus.CANCELLED;
    if (!trip.status().canTransitionTo(to)) {
      throw new ApiException.Conflict(
          "TRIP_NOT_CANCELLABLE", "trip in status " + trip.status() + " cannot be cancelled here");
    }
    String orderId = trip.evidence().orderId();
    if (to == TripStatus.CANCELLING && (orderId == null || orderId.isBlank())) {
      throw new ApiException.Conflict(
          "TRIP_ORDER_UNKNOWN",
          "this booked trip carries no order reference; a travel admin must release it");
    }
    Instant now = clock.instant();
    Trip next = trip.withStatus(to, now);
    if (!trips.update(next, trip.version())) {
      throw new ApiException.Conflict(
          "TRIP_MODIFIED_CONCURRENTLY", "trip changed while cancelling; re-read and retry");
    }
    trips.appendHistory(trip, trip.status(), to, reason, me.principal(), now);
    if (to == TripStatus.CANCELLING) {
      outbox.append(TripEvents.cancellationRequested(next, orderId, reason, me.principal(), clock));
    } else {
      outbox.append(TripEvents.cancelled(next, reason, me.principal(), null, clock));
      signaler.cancelled(trip.tripId());
    }
    return next;
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
            .filter(t -> visible(me, t))
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
    if (trip.status() == t.to() && t.to() != TripStatus.QUOTED) {
      // Idempotent by state, except a re-quote: QUOTED -> QUOTED is a new price or a new selection.
      if (t.to() == TripStatus.CANCELLING && t.failureCode() != null) {
        return recordIncompleteCancellation(tenant, actor, trip, t);
      }
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
    if (t.to() == TripStatus.CANCELLED && trip.status() == TripStatus.CANCELLING) {
      next = next.withoutFailure();
    }
    boolean replanned =
        t.to() == TripStatus.AWAITING_APPROVAL && trip.status() == TripStatus.APPROVED;
    if (t.explanation() != null && !t.explanation().isBlank()) {
      next = next.withExplanation(t.explanation());
    }
    if (t.to() == TripStatus.QUOTED) {
      next =
          next.withQuote(
              t.quoteExpiresAt(),
              t.alternatives().isEmpty() ? trip.alternatives() : t.alternatives());
    }
    // Phase 3: purchase authority. A plan or price the workflow now reports supersedes an
    // authorization that covered another; policy-granted autonomy is recorded as one; the move to
    // BOOKING consumes exactly one that covers the plan and price, or is refused.
    PurchaseAuthorization covering = reconcilePurchase(tenant, actor, trip, next, t, now);
    if (t.to() == TripStatus.BOOKING && covering == null) {
      throw new PurchaseNotAuthorizedException(
          "trip "
              + trip.tripId()
              + " has no active purchase authorization for "
              + next.evidence().selectedBundleId()
              + " at "
              + next.total());
    }
    if (!trips.update(next, trip.version())) {
      throw new IllegalStateException("trip " + trip.tripId() + " changed concurrently; retry");
    }
    if (t.to() == TripStatus.BOOKING) {
      String attempt = trip.tripId() + ":CREATE-ORDER:1";
      if (!purchases.consume(tenant, covering.authorizationId(), attempt, now)) {
        throw new PurchaseNotAuthorizedException(
            "authorization "
                + covering.authorizationId()
                + " was consumed or withdrawn concurrently");
      }
    }
    trips.appendHistory(trip, trip.status(), t.to(), t.reason(), actor, now);
    switch (t.to()) {
      case QUOTED -> {
        outbox.append(TripEvents.quoted(next, t.reason(), t.causationId(), clock));
        noteConversation(
            next,
            "QUOTE",
            "Planned: "
                + next.total()
                + " ("
                + Math.max(1, next.alternatives().size())
                + " option(s))."
                + (next.explanation() == null ? "" : " " + next.explanation())
                + (Trip.PURCHASE_CONFIRM.equals(next.purchaseMode()) || covering == null
                    ? " Confirm the purchase to continue."
                    : ""));
      }
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
        if (trip.status() == TripStatus.PLANNING || trip.status() == TripStatus.QUOTED) {
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
      case BOOKED -> {
        outbox.append(
            TripEvents.booked(
                next, components.list(tenant, trip.tripId()), t.causationId(), clock));
        noteConversation(
            next,
            "STATUS",
            "Booked: " + next.total() + ", order " + next.evidence().orderId() + ".");
      }
      case FAILED -> {
        outbox.append(
            TripEvents.failed(
                next,
                t.failureStage(),
                t.failureCode(),
                t.reason(),
                components.list(tenant, trip.tripId()),
                t.causationId(),
                clock));
        boolean question = "NEEDS_CLARIFICATION".equals(t.failureCode());
        noteConversation(
            next,
            question ? "QUESTION" : "FAILURE",
            question
                ? (t.reason() == null ? "The request needs more detail." : t.reason())
                : "Could not plan this: "
                    + t.failureCode()
                    + (t.reason() == null ? "" : " (" + t.reason() + ")"));
        if (question) {
          conversations
              .findByTrip(tenant, trip.tripId())
              .ifPresent(
                  c ->
                      conversations.update(
                          tenant,
                          c.conversationId(),
                          Conversation.Status.AWAITING_USER,
                          null,
                          now));
        }
      }
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
   * Phase 3. Returns the ACTIVE authorization covering the trip's plan and price after this move,
   * minting a POLICY_AUTONOMY one when the workflow reports that policy granted it, or null.
   */
  private @Nullable PurchaseAuthorization reconcilePurchase(
      TenantId tenant, Principal actor, Trip before, Trip next, Transition t, Instant now) {
    String bundle = next.evidence().selectedBundleId();
    Money total = next.total();
    Optional<PurchaseAuthorization> active = purchases.active(tenant, before.tripId());
    boolean planKnown = bundle != null && total != null;
    if (active.isPresent() && planKnown && !active.get().covers(bundle, total, now)) {
      String why =
          !active.get().bundleId().equals(bundle)
              ? "plan changed to " + bundle
              : total.compareTo(active.get().total()) > 0
                  ? "price changed from " + active.get().total() + " to " + total
                  : "quote expired";
      purchases.supersede(tenant, active.get().authorizationId(), null, why, now);
      noteConversation(next, "STATUS", "The earlier confirmation no longer applies: " + why + ".");
      active = Optional.empty();
    }
    if (active.isEmpty() && planKnown && t.autonomousPurchase()) {
      PurchaseAuthorization a =
          new PurchaseAuthorization(
              Ids.newId(IdPrefix.PURCHASE_AUTHORIZATION),
              tenant,
              before.tripId(),
              PurchaseAuthorization.Status.ACTIVE,
              PurchaseAuthorization.Basis.POLICY_AUTONOMY,
              actor.id(),
              bundle,
              total,
              t.conditions(),
              before.travelerId(),
              allocations
                  .find(tenant, before.tripId())
                  .map(TripAllocation::profileVersion)
                  .orElse(0L),
              before.version(),
              next.evidence().policyDecisionId(),
              null,
              t.quoteExpiresAt() == null
                  ? now.plus(DEFAULT_AUTHORIZATION_LIFETIME)
                  : t.quoteExpiresAt(),
              now,
              now,
              null,
              null,
              null);
      if (purchases.insert(a) == 1) {
        outbox.append(TripEvents.purchaseAuthorized(next, a, null, t.causationId(), clock));
        active = Optional.of(a);
      } else {
        active = purchases.active(tenant, before.tripId());
      }
    }
    return active.filter(a -> planKnown && a.covers(bundle, total, now)).orElse(null);
  }

  /**
   * A supplier refused to release part of a cancelling trip's reservation. The trip stays
   * CANCELLING (never CANCELLED) with the refusal on it, a history row and an event for the people
   * who resolve exposures. Idempotent per code: the workflow reporting the same refusal again
   * changes nothing.
   */
  private Trip recordIncompleteCancellation(
      TenantId tenant, Principal actor, Trip trip, Transition t) {
    if (t.failureCode().equals(trip.failureCode())) {
      return trip;
    }
    Instant now = clock.instant();
    Trip next =
        trip.withFailure(
            t.failureStage() == null ? "CANCELLATION" : t.failureStage(), t.failureCode(), now);
    if (!trips.update(next, trip.version())) {
      throw new IllegalStateException("trip " + trip.tripId() + " changed concurrently; retry");
    }
    trips.appendHistory(trip, TripStatus.CANCELLING, TripStatus.CANCELLING, t.reason(), actor, now);
    String orderId = trip.evidence().orderId() == null ? "" : trip.evidence().orderId();
    outbox.append(
        TripEvents.cancellationIncomplete(
            next,
            orderId,
            t.failureCode(),
            t.reason() == null ? "a supplier refused the cancellation" : t.reason(),
            t.causationId(),
            clock));
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
      // What the model understood must satisfy what the API would have refused up front: an
      // unknown place, a departure that has passed, a hotel without nights.
      IntentValidation.check(understood, now);
    } catch (IntentRejectedException e) {
      // Refuse now, before any planning, with the same actionable code the API returns. The
      // ledger keeps the extraction.
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
            .filter(t -> visible(me, t))
            .orElseThrow(() -> new ApiException.NotFound("trip", tripId));
    if (!me.hasAnyRole("MANAGER", "TRAVEL_ADMIN")) {
      throw new ApiException.Forbidden(
          "NOT_AN_APPROVER", "only MANAGER or TRAVEL_ADMIN may decide approvals");
    }
    if (trip.travelerId().equals(me.employeeId())) {
      throw new ApiException.Forbidden("SELF_APPROVAL", "a traveler cannot approve their own trip");
    }
    if (!TripAccess.canApprove(me, trip, allocations.find(trip.tenantId(), trip.tripId()))) {
      throw new ApiException.Forbidden(
          "NOT_THE_APPROVER", "this trip's approval belongs to the traveler's manager");
    }
    Optional<Approval> pending = approvals.pendingForTrip(me.tenant(), tripId);
    if (pending.isPresent() && trip.status() != TripStatus.AWAITING_APPROVAL) {
      // the requester withdrew (or the trip failed) after the approval was asked for
      throw new ApiException.Conflict(
          "TRIP_NOT_AWAITING_APPROVAL",
          "trip " + tripId + " is " + trip.status() + "; its approval can no longer be decided");
    }
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
      @Nullable TravelIntent intent,
      @Nullable TravelerIdentity traveler,
      @Nullable String projectId,
      String purchaseMode,
      boolean draft) {

    public CreateTrip(
        @Nullable String travelerId,
        TripSource source,
        @Nullable String requestText,
        @Nullable TravelIntent intent,
        @Nullable TravelerIdentity traveler,
        @Nullable String projectId) {
      this(travelerId, source, requestText, intent, traveler, projectId, null, false);
    }

    public CreateTrip(
        @Nullable String travelerId,
        TripSource source,
        @Nullable String requestText,
        @Nullable TravelIntent intent) {
      this(travelerId, source, requestText, intent, null, null);
    }

    public CreateTrip(
        @Nullable String travelerId,
        TripSource source,
        @Nullable String requestText,
        @Nullable TravelIntent intent,
        @Nullable TravelerIdentity traveler) {
      this(travelerId, source, requestText, intent, traveler, null);
    }

    public CreateTrip {
      if ((requestText == null || requestText.isBlank()) && intent == null) {
        throw new IllegalArgumentException("a trip needs a request text or a structured intent");
      }
      purchaseMode =
          purchaseMode == null || purchaseMode.isBlank() ? Trip.PURCHASE_POLICY : purchaseMode;
      if (!Trip.PURCHASE_POLICY.equals(purchaseMode)
          && !Trip.PURCHASE_CONFIRM.equals(purchaseMode)) {
        throw new IllegalArgumentException("purchaseMode must be POLICY or CONFIRM");
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
              intent == null ? "" : intent.canonical(),
              traveler == null ? "" : traveler.canonical(),
              projectId == null ? "" : projectId,
              purchaseMode);
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
      @Nullable String replanReason,
      boolean autonomousPurchase,
      @Nullable Instant quoteExpiresAt,
      List<TripAlternative> alternatives,
      @Nullable String conditions) {
    public Transition {
      alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }

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
        @Nullable String explanation,
        @Nullable String replanReason) {
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
          replanReason,
          false,
          null,
          List.of(),
          null);
    }

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
