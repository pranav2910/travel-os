package io.travelos.policy.governance;

import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import io.travelos.policy.engine.Facts;
import io.travelos.policy.governance.GovernanceRecords.Agreement;
import io.travelos.policy.governance.GovernanceRecords.Budget;
import io.travelos.policy.governance.GovernanceRecords.PolicyScope;
import io.travelos.policy.governance.GovernanceRecords.Reservation;
import io.travelos.policy.governance.GovernanceRecords.ReservationStatus;
import io.travelos.policy.governance.GovernanceRecords.Scope;
import io.travelos.policy.governance.GovernanceRecords.ScopeKind;
import io.travelos.policy.store.PolicyRepository;
import io.travelos.policy.store.PolicyVersion;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 7: which policy, which budget and which supplier agreements govern a trip, and the budget's
 * reservations. Resolution is by the trip's allocation scope, most specific first (employee,
 * project, cost center, department, legal entity, office), then the tenant default. Budget
 * movements happen under the budget row's lock so two trips cannot both take the last of it.
 */
@Service
public class GovernanceService {
  private static final Logger log = LoggerFactory.getLogger(GovernanceService.class);

  public record Resolved(
      Optional<PolicyVersion> policy,
      String policyScopeKind,
      Facts.@Nullable Budget budget,
      Map<String, Set<String>> preferredProviders,
      List<Agreement> agreements) {}

  public enum ReserveStatus {
    NO_BUDGET,
    RESERVED,
    EXCEEDED,
    COMMITTED,
    RELEASED
  }

  public record ReserveResult(
      ReserveStatus status,
      @Nullable String budgetId,
      @Nullable String reservationId,
      @Nullable Money remaining,
      boolean hard,
      String message) {}

  private final GovernanceRepository store;
  private final PolicyRepository policies;
  private final Clock clock;

  public GovernanceService(GovernanceRepository store, PolicyRepository policies, Clock clock) {
    this.store = store;
    this.policies = policies;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ resolution

  @Transactional(readOnly = true)
  public Resolved resolve(TenantId tenant, Scope scope, Instant at) {
    Optional<PolicyVersion> policy = Optional.empty();
    String how = "NONE";
    for (ScopeKind kind : ScopeKind.values()) {
      String ref = kind.refIn(scope);
      if (ref == null || ref.isBlank()) {
        continue;
      }
      Optional<PolicyScope> assigned = store.scope(tenant, kind, ref);
      if (assigned.isPresent()) {
        policy = policies.current(tenant, assigned.get().policyId());
        if (policy.isPresent()) {
          how = kind.name();
          break;
        }
        log.warn(
            "scope {} {} names policy {} which has no published version",
            kind,
            ref,
            assigned.get().policyId());
      }
    }
    if (policy.isEmpty()) {
      policy = policies.defaultPolicy(tenant);
      how = policy.isPresent() ? "TENANT" : "NONE";
    }
    Facts.Budget budget =
        budgetFor(tenant, scope, at, false)
            .map(b -> new Facts.Budget(b.budgetId(), b.remaining(), b.hard()))
            .orElse(null);
    List<Agreement> agreements =
        store.agreements(tenant, true).stream().filter(a -> a.validAt(at)).toList();
    Map<String, Set<String>> preferred = new LinkedHashMap<>();
    for (Agreement a : agreements) {
      if (a.preferred()) {
        preferred.computeIfAbsent(a.kind(), k -> new LinkedHashSet<>()).add(a.provider());
      }
    }
    return new Resolved(policy, how, budget, preferred, agreements);
  }

  private Optional<Budget> budgetFor(TenantId tenant, Scope scope, Instant at, boolean lock) {
    for (ScopeKind kind : ScopeKind.values()) {
      String ref = kind.refIn(scope);
      if (ref == null || ref.isBlank()) {
        continue;
      }
      List<Budget> found = store.budgetsFor(tenant, kind, ref, at, lock);
      if (!found.isEmpty()) {
        return Optional.of(found.getFirst());
      }
    }
    return Optional.empty();
  }

  // ------------------------------------------------------------------ reservations

  /** Reserves the amount against the scope's budget; idempotent per trip and budget. */
  @Transactional
  public ReserveResult reserve(
      TenantId tenant, String tripId, @Nullable String travelerId, Scope scope, Money amount) {
    Instant now = clock.instant();
    Optional<Budget> found = budgetFor(tenant, scope, now, true);
    if (found.isEmpty()) {
      return new ReserveResult(
          ReserveStatus.NO_BUDGET, null, null, null, false, "no budget covers this scope now");
    }
    Budget b = found.get();
    if (!b.currency().equals(amount.currency())) {
      return new ReserveResult(
          ReserveStatus.NO_BUDGET,
          b.budgetId(),
          null,
          b.remaining(),
          b.hard(),
          "budget "
              + b.budgetId()
              + " is in "
              + b.currency()
              + ", the trip in "
              + amount.currency());
    }
    Optional<Reservation> existing = store.reservation(b.budgetId(), tripId);
    if (existing.isPresent()) {
      Reservation r = existing.get();
      ReserveStatus status =
          switch (r.status()) {
            case RESERVED -> ReserveStatus.RESERVED;
            case COMMITTED -> ReserveStatus.COMMITTED;
            case RELEASED -> ReserveStatus.RELEASED;
          };
      if (r.status() == ReservationStatus.RELEASED) {
        // a released trip asking again (a re-plan): reserve afresh under the same row
        return reserveAgain(b, r, amount, now);
      }
      return new ReserveResult(
          status,
          b.budgetId(),
          r.reservationId(),
          b.remaining(),
          b.hard(),
          "already " + r.status());
    }
    if (amount.compareTo(b.remaining()) > 0) {
      return new ReserveResult(
          ReserveStatus.EXCEEDED,
          b.budgetId(),
          null,
          b.remaining(),
          b.hard(),
          amount + " does not fit the " + b.remaining() + " left in " + b.name());
    }
    Reservation r =
        new Reservation(
            Ids.newId(IdPrefix.BUDGET_RESERVATION),
            b.budgetId(),
            tenant,
            tripId,
            travelerId,
            amount.amountMinor(),
            ReservationStatus.RESERVED,
            now,
            now);
    store.insertReservation(r);
    store.moveBudget(b.budgetId(), amount.amountMinor(), 0, now);
    Money remaining = b.remaining().minus(amount);
    return new ReserveResult(
        ReserveStatus.RESERVED, b.budgetId(), r.reservationId(), remaining, b.hard(), "reserved");
  }

  private ReserveResult reserveAgain(Budget b, Reservation r, Money amount, Instant now) {
    if (amount.compareTo(b.remaining()) > 0) {
      return new ReserveResult(
          ReserveStatus.EXCEEDED,
          b.budgetId(),
          r.reservationId(),
          b.remaining(),
          b.hard(),
          amount + " does not fit the " + b.remaining() + " left in " + b.name());
    }
    store.updateReservation(
        r.reservationId(), ReservationStatus.RESERVED, amount.amountMinor(), now);
    store.moveBudget(b.budgetId(), amount.amountMinor(), 0, now);
    return new ReserveResult(
        ReserveStatus.RESERVED,
        b.budgetId(),
        r.reservationId(),
        b.remaining().minus(amount),
        b.hard(),
        "reserved again");
  }

  /**
   * COMMIT (bought, at what it cost) or RELEASE (not bought) every open reservation of the trip.
   */
  @Transactional
  public ReserveResult settle(
      TenantId tenant, String tripId, boolean commit, @Nullable Money amount) {
    Instant now = clock.instant();
    ReserveResult last =
        new ReserveResult(
            ReserveStatus.NO_BUDGET, null, null, null, false, "no reservation for trip " + tripId);
    for (Reservation r : store.reservationsOfTrip(tenant, tripId)) {
      Budget b = store.lockBudget(r.budgetId()).orElseThrow();
      if (r.status() == ReservationStatus.RESERVED) {
        if (commit) {
          long committed =
              amount != null && amount.currency().equals(b.currency())
                  ? amount.amountMinor()
                  : r.amountMinor();
          store.updateReservation(r.reservationId(), ReservationStatus.COMMITTED, committed, now);
          store.moveBudget(b.budgetId(), -r.amountMinor(), committed, now);
          last =
              new ReserveResult(
                  ReserveStatus.COMMITTED,
                  b.budgetId(),
                  r.reservationId(),
                  Money.of(
                      b.currency(),
                      b.amountMinor()
                          - (b.reservedMinor() - r.amountMinor())
                          - (b.committedMinor() + committed)),
                  b.hard(),
                  "committed");
        } else {
          store.updateReservation(
              r.reservationId(), ReservationStatus.RELEASED, r.amountMinor(), now);
          store.moveBudget(b.budgetId(), -r.amountMinor(), 0, now);
          last =
              new ReserveResult(
                  ReserveStatus.RELEASED,
                  b.budgetId(),
                  r.reservationId(),
                  Money.of(
                      b.currency(),
                      b.amountMinor() - (b.reservedMinor() - r.amountMinor()) - b.committedMinor()),
                  b.hard(),
                  "released");
        }
      } else if (r.status() == ReservationStatus.COMMITTED && !commit) {
        // the trip was bought and then cancelled: the money comes back to the budget
        store.updateReservation(
            r.reservationId(), ReservationStatus.RELEASED, r.amountMinor(), now);
        store.moveBudget(b.budgetId(), 0, -r.amountMinor(), now);
        last =
            new ReserveResult(
                ReserveStatus.RELEASED,
                b.budgetId(),
                r.reservationId(),
                Money.of(
                    b.currency(),
                    b.amountMinor() - b.reservedMinor() - (b.committedMinor() - r.amountMinor())),
                b.hard(),
                "released after commit");
      } else {
        last =
            new ReserveResult(
                commit ? ReserveStatus.COMMITTED : ReserveStatus.RELEASED,
                b.budgetId(),
                r.reservationId(),
                b.remaining(),
                b.hard(),
                "already " + r.status());
      }
    }
    return last;
  }

  // ------------------------------------------------------------------ administration

  @Transactional
  public PolicyScope assignScope(RequestPrincipal me, ScopeKind kind, String ref, String policyId) {
    requireAdmin(me);
    if (policies.current(me.tenant(), policyId).isEmpty()) {
      throw new ApiException.Unprocessable("POLICY_UNKNOWN", "no published policy " + policyId);
    }
    PolicyScope s =
        new PolicyScope(
            Ids.newId(IdPrefix.POLICY_SCOPE),
            me.tenant(),
            kind,
            ref,
            policyId,
            me.principal().id(),
            clock.instant());
    store.upsertScope(s);
    return store.scope(me.tenant(), kind, ref).orElse(s);
  }

  @Transactional
  public void unassignScope(RequestPrincipal me, ScopeKind kind, String ref) {
    requireAdmin(me);
    if (!store.deleteScope(me.tenant(), kind, ref)) {
      throw new ApiException.NotFound("scope", kind + "/" + ref);
    }
  }

  @Transactional(readOnly = true)
  public List<PolicyScope> scopes(RequestPrincipal me) {
    requireReader(me);
    return store.scopes(me.tenant());
  }

  @Transactional
  public Budget createBudget(
      RequestPrincipal me,
      String name,
      ScopeKind kind,
      String ref,
      Instant start,
      Instant end,
      Money amount,
      boolean hard) {
    requireFinance(me);
    if (!end.isAfter(start)) {
      throw new ApiException.Unprocessable("PERIOD_INVALID", "periodEnd must be after periodStart");
    }
    if (amount.amountMinor() < 0) {
      throw new ApiException.Unprocessable("AMOUNT_INVALID", "amount must be >= 0");
    }
    Instant now = clock.instant();
    Budget b =
        new Budget(
            Ids.newId(IdPrefix.BUDGET),
            me.tenant(),
            name,
            kind,
            ref,
            start,
            end,
            amount.currency(),
            amount.amountMinor(),
            0,
            0,
            hard,
            true,
            0,
            me.principal().id(),
            now,
            now);
    store.insertBudget(b);
    return b;
  }

  @Transactional
  public void deactivateBudget(RequestPrincipal me, String budgetId) {
    requireFinance(me);
    if (!store.deactivateBudget(me.tenant(), budgetId, clock.instant())) {
      throw new ApiException.NotFound("budget", budgetId);
    }
  }

  @Transactional(readOnly = true)
  public List<Budget> budgets(RequestPrincipal me) {
    requireReader(me);
    return store.budgets(me.tenant());
  }

  @Transactional(readOnly = true)
  public Budget budget(RequestPrincipal me, String budgetId) {
    requireReader(me);
    return store
        .budget(me.tenant(), budgetId)
        .orElseThrow(() -> new ApiException.NotFound("budget", budgetId));
  }

  @Transactional(readOnly = true)
  public List<Reservation> reservations(RequestPrincipal me, String budgetId) {
    budget(me, budgetId);
    return store.reservationsOfBudget(me.tenant(), budgetId, 200);
  }

  @Transactional
  public Agreement createAgreement(
      RequestPrincipal me,
      String provider,
      String kind,
      @Nullable String carrier,
      @Nullable String rateCode,
      boolean preferred,
      boolean negotiated,
      @Nullable String contractRef,
      @Nullable Instant validFrom,
      @Nullable Instant validUntil) {
    requireAdmin(me);
    if (!List.of("AIR", "HOTEL", "GROUND", "RAIL", "CAR").contains(kind)) {
      throw new ApiException.Unprocessable(
          "KIND_UNKNOWN", "kind must be AIR, HOTEL, GROUND, RAIL or CAR");
    }
    if (negotiated && (rateCode == null || rateCode.isBlank())) {
      throw new ApiException.Unprocessable(
          "RATE_CODE_REQUIRED", "a negotiated agreement names its rate code");
    }
    Instant now = clock.instant();
    Agreement a =
        new Agreement(
            Ids.newId(IdPrefix.SUPPLIER_AGREEMENT),
            me.tenant(),
            provider,
            kind,
            GovernanceRepository.blankToNull(carrier),
            GovernanceRepository.blankToNull(rateCode),
            preferred,
            negotiated,
            GovernanceRepository.blankToNull(contractRef),
            validFrom == null ? now : validFrom,
            validUntil,
            true,
            me.principal().id(),
            now);
    store.insertAgreement(a);
    return a;
  }

  @Transactional
  public void endAgreement(RequestPrincipal me, String agreementId) {
    requireAdmin(me);
    if (!store.deactivateAgreement(me.tenant(), agreementId)) {
      throw new ApiException.NotFound("agreement", agreementId);
    }
  }

  @Transactional(readOnly = true)
  public List<Agreement> agreements(RequestPrincipal me) {
    requireReader(me);
    return store.agreements(me.tenant(), false);
  }

  private static void requireAdmin(RequestPrincipal me) {
    if (!me.hasRole("TRAVEL_ADMIN")) {
      throw new ApiException.Forbidden("NOT_A_TRAVEL_ADMIN", "TRAVEL_ADMIN role required");
    }
  }

  private static void requireFinance(RequestPrincipal me) {
    if (!me.hasAnyRole("FINANCE", "TRAVEL_ADMIN")) {
      throw new ApiException.Forbidden("NOT_FINANCE", "FINANCE or TRAVEL_ADMIN role required");
    }
  }

  private static void requireReader(RequestPrincipal me) {
    if (!me.hasAnyRole("FINANCE", "TRAVEL_ADMIN", "MANAGER")) {
      throw new ApiException.Forbidden(
          "NOT_ALLOWED", "MANAGER, FINANCE or TRAVEL_ADMIN role required");
    }
  }
}
