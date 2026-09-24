package io.travelos.policy.engine;

import io.travelos.common.identity.Principal;
import io.travelos.common.money.Money;
import io.travelos.policy.document.PolicyDocument;
import io.travelos.policy.document.PolicyDocument.Consequence;
import io.travelos.policy.engine.Decision.CandidateEvaluation;
import io.travelos.policy.engine.Decision.Economics;
import io.travelos.policy.engine.Decision.Violation;
import io.travelos.policy.engine.Facts.Action;
import io.travelos.policy.engine.Facts.Candidate;
import io.travelos.policy.engine.Facts.Trip;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic: same policy + same facts = same decision, byte for byte. No clock, no randomness,
 * no I/O. Rules run in a fixed order and every rule that ran is named in the decision, whether or
 * not it objected.
 */
public final class PolicyEngine {

  static final String RULE_CURRENCY = "CURRENCY";
  static final String RULE_CABIN = "CABIN_PERMITTED";
  static final String RULE_MAX_STOPS = "MAX_STOPS";
  static final String RULE_LLF = "LOWEST_LOGICAL_FARE";
  static final String RULE_HOTEL = "HOTEL_NIGHTLY_LIMIT";
  static final String RULE_GROUND = "GROUND_TRANSFER_LIMIT";
  static final String RULE_TRIP_BUDGET = "TRIP_BUDGET";
  static final String RULE_BOOKING_HORIZON = "BOOKING_HORIZON";
  static final String RULE_PURCHASE_AUTONOMY = "PURCHASE_AUTONOMY";
  static final String RULE_MANAGER_THRESHOLD = "MANAGER_APPROVAL_THRESHOLD";
  static final String RULE_INCENTIVE = "INCENTIVE_SHARE";
  static final String RULE_AGENT_REBOOKING = "AGENT_REBOOKING_AUTONOMY";
  static final String RULE_AGENT_CANCELLATION = "AGENT_CANCELLATION_AUTONOMY";
  static final String RULE_AGENT_ORDER_CREATE = "AGENT_ORDER_CREATE";
  static final String RULE_HUMAN_ACTION = "HUMAN_ACTION";
  static final String RULE_ACTION_KNOWN = "ACTION_KNOWN";
  static final String RULE_REPLACEMENT_CONSTRAINTS = "REPLACEMENT_ITINERARY_CONSTRAINTS";

  static final String ROLE_MANAGER = "MANAGER";
  static final String ROLE_TRAVELER = "TRAVELER";

  private static final List<TripRule> TRIP_RULES =
      List.of(
          new CurrencyRule(),
          new CabinRule(),
          new MaxStopsRule(),
          new LowestLogicalFareRule(),
          new HotelNightlyLimitRule(),
          new GroundTransferLimitRule(),
          new TripBudgetRule(),
          new BookingHorizonRule(),
          new ManagerApprovalThresholdRule());

  /** Facts shared by every candidate of one evaluation: the benchmark fare and the ceiling. */
  record Context(Money referenceFare, Money inPolicyCeiling) {}

  interface TripRule {
    String id();

    Optional<Violation> evaluate(
        PolicyDocument policy, Trip trip, Candidate candidate, Context ctx);
  }

  public List<CandidateEvaluation> evaluateTrip(
      PolicyDocument policy, Trip trip, List<Candidate> candidates) {
    Context ctx = context(policy, trip, candidates);
    List<CandidateEvaluation> result = new ArrayList<>(candidates.size());
    for (Candidate candidate : candidates) {
      result.add(
          new CandidateEvaluation(candidate.bundleId(), decide(policy, trip, candidate, ctx)));
    }
    return List.copyOf(result);
  }

  public Decision evaluateAction(PolicyDocument policy, Trip trip, Action action, Principal actor) {
    List<String> rules = new ArrayList<>();
    List<Violation> violations = new ArrayList<>();
    Candidate proposed = action.proposed();
    Economics economics = Economics.none(policy.currency());

    if (actor instanceof Principal.Agent) {
      switch (action.action()) {
        case "order.change" -> {
          rules.add(RULE_AGENT_REBOOKING);
          agentRebooking(policy, action).ifPresent(violations::add);
        }
        case "order.cancel" -> {
          rules.add(RULE_AGENT_CANCELLATION);
          if (!policy.autonomy().cancellation().enabled()) {
            violations.add(
                new Violation(
                    RULE_AGENT_CANCELLATION,
                    "AUTONOMOUS_CANCELLATION_DISABLED",
                    "this policy does not let agents cancel bookings without a person",
                    Consequence.REQUIRE_APPROVAL,
                    ROLE_TRAVELER));
          }
        }
        case "order.create" -> {
          rules.add(RULE_AGENT_ORDER_CREATE);
          violations.add(
              new Violation(
                  RULE_AGENT_ORDER_CREATE,
                  "AGENTS_PROPOSE_PEOPLE_BOOK",
                  "an agent may propose a booking; a person confirms it",
                  Consequence.REQUIRE_APPROVAL,
                  ROLE_TRAVELER));
        }
        default -> {
          rules.add(RULE_ACTION_KNOWN);
          violations.add(unknownAction(action.action()));
        }
      }
    } else {
      switch (action.action()) {
        case "order.create", "order.change" -> {
          rules.add(RULE_HUMAN_ACTION);
          if (proposed == null) {
            violations.add(
                new Violation(
                    RULE_HUMAN_ACTION,
                    "PROPOSAL_REQUIRED",
                    action.action() + " must carry the bundle it would book",
                    Consequence.DENY,
                    null));
          }
          Long threshold = policy.approval().managerRequiredAbove();
          if (action.incrementalCost() != null
              && threshold != null
              && action.incrementalCost().amountMinor() > threshold) {
            violations.add(
                new Violation(
                    RULE_HUMAN_ACTION,
                    "INCREMENTAL_COST_ABOVE_APPROVAL_THRESHOLD",
                    "the change adds "
                        + action.incrementalCost()
                        + ", above the "
                        + Money.of(policy.currency(), threshold)
                        + " manager approval threshold",
                    Consequence.REQUIRE_APPROVAL,
                    ROLE_MANAGER));
          }
        }
        case "order.cancel" -> rules.add(RULE_HUMAN_ACTION);
        default -> {
          rules.add(RULE_ACTION_KNOWN);
          violations.add(unknownAction(action.action()));
        }
      }
    }

    // A change must still get the traveler where the trip needs them: the replacement's times are
    // checked against the frozen intent here, by policy, not by whoever proposed it.
    if ("order.change".equals(action.action()) && action.constraints() != null) {
      if (!action.legTimings().isEmpty() && !action.constraints().legWindows().isEmpty()) {
        rules.add(RULE_REPLACEMENT_CONSTRAINTS);
        violations.addAll(legWindows(action.constraints(), action.legTimings()));
      } else if (action.itinerary() != null) {
        rules.add(RULE_REPLACEMENT_CONSTRAINTS);
        violations.addAll(replacementConstraints(action.constraints(), action.itinerary()));
      }
    }

    // Whatever the actor, a proposed bundle must itself be in policy. A hallucinated business-class
    // fare is caught here, not by trusting the proposer.
    if (proposed != null && !"order.cancel".equals(action.action())) {
      Context ctx = context(policy, trip, List.of(proposed));
      for (TripRule rule : TRIP_RULES) {
        rules.add(rule.id());
        rule.evaluate(policy, trip, proposed, ctx).ifPresent(violations::add);
      }
      economics = economics(policy, proposed, ctx, violations);
      if (policy.incentives().enabled()) {
        rules.add(RULE_INCENTIVE);
      }
    }
    return Decision.of(rules, violations, economics);
  }

  /** Every constraint the replacement breaks is a DENY: a late arrival is not a cheaper option. */
  static List<Violation> replacementConstraints(Facts.Constraints k, Facts.Itinerary it) {
    List<Violation> out = new ArrayList<>();
    if (k.earliestDeparture() != null && it.outboundDeparture().isBefore(k.earliestDeparture())) {
      out.add(
          deny(
              "DEPARTS_BEFORE_EARLIEST_DEPARTURE",
              "the replacement departs "
                  + it.outboundDeparture()
                  + ", before the trip's earliest departure "
                  + k.earliestDeparture()));
    }
    if (k.arrivalDeadline() != null && it.outboundArrival().isAfter(k.arrivalDeadline())) {
      out.add(
          deny(
              "ARRIVES_AFTER_DEADLINE",
              "the replacement arrives "
                  + it.outboundArrival()
                  + ", after the trip's arrival deadline "
                  + k.arrivalDeadline()));
    }
    if (k.returnAfter() != null
        && it.inboundDeparture() != null
        && it.inboundDeparture().isBefore(k.returnAfter())) {
      out.add(
          deny(
              "RETURNS_BEFORE_RETURN_WINDOW",
              "the replacement returns "
                  + it.inboundDeparture()
                  + ", before the trip's earliest return "
                  + k.returnAfter()));
    }
    if (k.latestReturn() != null
        && it.inboundDeparture() != null
        && it.inboundDeparture().isAfter(k.latestReturn())) {
      out.add(
          deny(
              "RETURNS_AFTER_RETURN_WINDOW",
              "the replacement returns "
                  + it.inboundDeparture()
                  + ", after the trip's latest return "
                  + k.latestReturn()));
    }
    if (k.returnAfter() != null && it.inboundDeparture() == null) {
      out.add(deny("RETURN_LEG_MISSING", "the trip needs a return leg; the replacement has none"));
    }
    return out;
  }

  /** Slice 3: every leg of a multi-leg replacement must fly inside its own window. */
  static List<Violation> legWindows(Facts.Constraints k, List<Facts.LegTiming> timings) {
    List<Violation> out = new ArrayList<>();
    for (Facts.LegTiming t : timings) {
      Facts.Window w = k.legWindows().get(t.componentId());
      if (w == null) {
        continue;
      }
      if (t.departure().isBefore(w.earliestDeparture())) {
        out.add(
            deny(
                "LEG_DEPARTS_BEFORE_WINDOW",
                "leg "
                    + t.componentId()
                    + " departs "
                    + t.departure()
                    + ", before its earliest departure "
                    + w.earliestDeparture()));
      }
      if (t.arrival().isAfter(w.arrivalDeadline())) {
        out.add(
            deny(
                "LEG_ARRIVES_AFTER_DEADLINE",
                "leg "
                    + t.componentId()
                    + " lands "
                    + t.arrival()
                    + ", after its deadline "
                    + w.arrivalDeadline()));
      }
    }
    return out;
  }

  private static Violation deny(String code, String message) {
    return new Violation(RULE_REPLACEMENT_CONSTRAINTS, code, message, Consequence.DENY, null);
  }

  private Decision decide(PolicyDocument policy, Trip trip, Candidate candidate, Context ctx) {
    List<String> rules = new ArrayList<>();
    List<Violation> violations = new ArrayList<>();
    for (TripRule rule : TRIP_RULES) {
      rules.add(rule.id());
      rule.evaluate(policy, trip, candidate, ctx).ifPresent(violations::add);
    }
    Economics economics = economics(policy, candidate, ctx, violations);
    if (policy.incentives().enabled()) {
      rules.add(RULE_INCENTIVE);
    }
    // Phase 3: purchase authority. Policy may authorize the purchase on its own only when its
    // autonomy.purchase section allows it, the plan is not denied and the total is within the
    // section's limit. A required approval stays a person's decision either way.
    rules.add(RULE_PURCHASE_AUTONOMY);
    Decision decision = Decision.of(rules, violations, economics);
    PolicyDocument.Purchase purchase = policy.autonomy().purchase();
    Money limit =
        purchase.maxTotal() == null ? null : Money.of(policy.currency(), purchase.maxTotal());
    boolean withinLimit =
        limit == null
            || (candidate.total().currency().equals(limit.currency())
                && candidate.total().compareTo(limit) <= 0);
    boolean autonomous =
        purchase.enabled() && decision.outcome() != Decision.Outcome.DENY && withinLimit;
    return decision.withPurchaseAutonomy(autonomous, limit);
  }

  /**
   * Phase 3: booking horizon and trip length, judged against the reference time the evaluation was
   * asked with (never the engine's own clock). Absent facts mean nothing to judge.
   */
  private static final class BookingHorizonRule implements TripRule {
    @Override
    public String id() {
      return RULE_BOOKING_HORIZON;
    }

    @Override
    public Optional<Violation> evaluate(
        PolicyDocument policy, Trip trip, Candidate c, Context ctx) {
      PolicyDocument.TripBudget rules = policy.trip();
      Consequence consequence = rules.horizonConsequence();
      if (trip.referenceTime() != null && trip.earliestDeparture() != null) {
        java.time.Duration ahead =
            java.time.Duration.between(trip.referenceTime(), trip.earliestDeparture());
        if (rules.maxAdvanceDays() != null
            && ahead.compareTo(java.time.Duration.ofDays(rules.maxAdvanceDays())) > 0) {
          return Optional.of(
              new Violation(
                  RULE_BOOKING_HORIZON,
                  "BOOKING_HORIZON_EXCEEDED",
                  "departure is "
                      + ahead.toDays()
                      + " days ahead, beyond the "
                      + rules.maxAdvanceDays()
                      + "-day booking horizon",
                  consequence,
                  approverFor(consequence)));
        }
        if (rules.minLeadHours() != null
            && ahead.compareTo(java.time.Duration.ofHours(rules.minLeadHours())) < 0) {
          return Optional.of(
              new Violation(
                  RULE_BOOKING_HORIZON,
                  "LEAD_TIME_TOO_SHORT",
                  "departure is "
                      + Math.max(0, ahead.toHours())
                      + " hours away, less than the "
                      + rules.minLeadHours()
                      + "-hour minimum notice",
                  consequence,
                  approverFor(consequence)));
        }
      }
      if (rules.maxDurationDays() != null
          && trip.earliestDeparture() != null
          && trip.latestReturn() != null) {
        java.time.Duration length =
            java.time.Duration.between(trip.earliestDeparture(), trip.latestReturn());
        if (length.compareTo(java.time.Duration.ofDays(rules.maxDurationDays())) > 0) {
          return Optional.of(
              new Violation(
                  RULE_BOOKING_HORIZON,
                  "TRIP_TOO_LONG",
                  "the trip spans "
                      + length.toDays()
                      + " days, more than the "
                      + rules.maxDurationDays()
                      + "-day maximum",
                  consequence,
                  approverFor(consequence)));
        }
      }
      return Optional.empty();
    }
  }

  /**
   * Lowest logical fare = the cheapest air fare among candidates whose cabin the policy permits on
   * this route (falling back to the cheapest of all). The ceiling is that plus the policy's band.
   */
  static Context context(PolicyDocument policy, Trip trip, List<Candidate> candidates) {
    Money zero = Money.zero(policy.currency());
    List<Cabin> permitted = permittedCabins(policy, trip);
    Money lowestPermitted = null;
    Money lowestAny = null;
    for (Candidate c : candidates) {
      if (c.air() == null || !c.air().fare().currency().equals(policy.currency())) {
        continue;
      }
      Money fare = c.air().fare();
      if (lowestAny == null || fare.compareTo(lowestAny) < 0) {
        lowestAny = fare;
      }
      if (permitted.contains(c.air().highestCabin())
          && (lowestPermitted == null || fare.compareTo(lowestPermitted) < 0)) {
        lowestPermitted = fare;
      }
    }
    Money reference =
        lowestPermitted != null ? lowestPermitted : lowestAny != null ? lowestAny : zero;
    Money ceiling =
        reference.plus(
            Money.of(policy.currency(), policy.flight().lowestLogicalFare().maxAmountAbove()));
    return new Context(reference, ceiling);
  }

  static List<Cabin> permittedCabins(PolicyDocument policy, Trip trip) {
    return trip.international()
        ? policy.flight().internationalCabins()
        : policy.flight().domesticCabins();
  }

  /**
   * At or below the ceiling the traveler earns share x (ceiling - fare), capped. Above it, when the
   * policy says TRAVELER_PAYS, the traveler owes the excess. Candidates without air get nothing.
   */
  static Economics economics(
      PolicyDocument policy, Candidate candidate, Context ctx, List<Violation> violations) {
    String currency = policy.currency();
    if (candidate.air() == null || !candidate.air().fare().currency().equals(currency)) {
      return Economics.none(currency);
    }
    Money fare = candidate.air().fare();
    Money incentive = Money.zero(currency);
    Money pays = Money.zero(currency);
    if (fare.compareTo(ctx.inPolicyCeiling()) <= 0) {
      if (policy.incentives().enabled()) {
        long savings = ctx.inPolicyCeiling().minus(fare).amountMinor();
        long reward = (long) Math.floor(savings * policy.incentives().shareOfSavings());
        incentive = Money.of(currency, Math.min(reward, policy.incentives().maxReward()));
      }
    } else {
      boolean travelerPays =
          violations.stream()
              .anyMatch(
                  v -> RULE_LLF.equals(v.ruleId()) && v.consequence() == Consequence.TRAVELER_PAYS);
      if (travelerPays) {
        pays = fare.minus(ctx.inPolicyCeiling());
      }
    }
    return new Economics(ctx.referenceFare(), ctx.inPolicyCeiling(), incentive, pays);
  }

  private static Optional<Violation> agentRebooking(PolicyDocument policy, Action action) {
    PolicyDocument.Rebooking rebooking = policy.autonomy().flightRebooking();
    if (!rebooking.enabled()) {
      return Optional.of(
          new Violation(
              RULE_AGENT_REBOOKING,
              "AUTONOMOUS_REBOOKING_DISABLED",
              "this policy does not let agents change flights without a person",
              Consequence.REQUIRE_APPROVAL,
              ROLE_TRAVELER));
    }
    Money incremental = action.incrementalCost();
    if (incremental == null) {
      return Optional.of(
          new Violation(
              RULE_AGENT_REBOOKING,
              "INCREMENTAL_COST_UNKNOWN",
              "an autonomous change must state its incremental cost",
              Consequence.REQUIRE_APPROVAL,
              ROLE_TRAVELER));
    }
    Money limit = Money.of(policy.currency(), rebooking.maxIncrementalCost());
    if (!incremental.currency().equals(policy.currency())) {
      return Optional.of(
          new Violation(
              RULE_AGENT_REBOOKING,
              "CURRENCY_MISMATCH",
              "incremental cost is in "
                  + incremental.currency()
                  + ", policy is in "
                  + policy.currency(),
              Consequence.DENY,
              null));
    }
    if (incremental.compareTo(limit) > 0) {
      return Optional.of(
          new Violation(
              RULE_AGENT_REBOOKING,
              "INCREMENTAL_COST_ABOVE_AUTONOMY_LIMIT",
              "the change adds " + incremental + ", above the " + limit + " autonomous limit",
              Consequence.REQUIRE_APPROVAL,
              ROLE_TRAVELER));
    }
    return Optional.empty();
  }

  private static Violation unknownAction(String action) {
    return new Violation(
        RULE_ACTION_KNOWN,
        "UNKNOWN_ACTION",
        "action '" + action + "' is not something policy can authorize",
        Consequence.DENY,
        null);
  }

  // ------------------------------------------------------------------ trip rules

  private static final class CurrencyRule implements TripRule {
    @Override
    public String id() {
      return RULE_CURRENCY;
    }

    @Override
    public Optional<Violation> evaluate(
        PolicyDocument policy, Trip trip, Candidate c, Context ctx) {
      if (!c.total().currency().equals(policy.currency())) {
        return Optional.of(
            new Violation(
                RULE_CURRENCY,
                "CURRENCY_MISMATCH",
                "bundle is priced in "
                    + c.total().currency()
                    + ", policy is in "
                    + policy.currency(),
                Consequence.DENY,
                null));
      }
      for (Facts.Hotel h : c.hotels()) {
        if (!h.nightlyRate().currency().equals(policy.currency())) {
          return Optional.of(
              new Violation(
                  RULE_CURRENCY,
                  "CURRENCY_MISMATCH",
                  "a stay is priced in "
                      + h.nightlyRate().currency()
                      + ", policy is in "
                      + policy.currency()
                      + "; the platform does not convert currencies",
                  Consequence.DENY,
                  null));
        }
      }
      for (Facts.Ground g : c.ground()) {
        if (!g.fare().currency().equals(policy.currency())) {
          return Optional.of(
              new Violation(
                  RULE_CURRENCY,
                  "CURRENCY_MISMATCH",
                  "a transfer is priced in "
                      + g.fare().currency()
                      + ", policy is in "
                      + policy.currency(),
                  Consequence.DENY,
                  null));
        }
      }
      return Optional.empty();
    }
  }

  private static final class CabinRule implements TripRule {
    @Override
    public String id() {
      return RULE_CABIN;
    }

    @Override
    public Optional<Violation> evaluate(
        PolicyDocument policy, Trip trip, Candidate c, Context ctx) {
      if (c.air() == null) {
        return Optional.empty();
      }
      List<Cabin> permitted = permittedCabins(policy, trip);
      if (!permitted.contains(c.air().highestCabin())) {
        return Optional.of(
            new Violation(
                RULE_CABIN,
                "CABIN_NOT_PERMITTED",
                c.air().highestCabin()
                    + " cabin is not permitted on "
                    + (trip.international() ? "international" : "domestic")
                    + " itineraries (permitted: "
                    + permitted
                    + ")",
                Consequence.DENY,
                null));
      }
      return Optional.empty();
    }
  }

  private static final class MaxStopsRule implements TripRule {
    @Override
    public String id() {
      return RULE_MAX_STOPS;
    }

    @Override
    public Optional<Violation> evaluate(
        PolicyDocument policy, Trip trip, Candidate c, Context ctx) {
      Integer max = policy.flight().maxStops();
      if (c.air() == null || max == null || c.air().maxStops() <= max) {
        return Optional.empty();
      }
      Consequence consequence = policy.flight().maxStopsConsequence();
      return Optional.of(
          new Violation(
              RULE_MAX_STOPS,
              "TOO_MANY_STOPS",
              c.air().maxStops() + " stops on one journey; policy allows " + max,
              consequence,
              approverFor(consequence)));
    }
  }

  private static final class LowestLogicalFareRule implements TripRule {
    @Override
    public String id() {
      return RULE_LLF;
    }

    @Override
    public Optional<Violation> evaluate(
        PolicyDocument policy, Trip trip, Candidate c, Context ctx) {
      if (c.air() == null
          || !c.air().fare().currency().equals(policy.currency())
          || c.air().fare().compareTo(ctx.inPolicyCeiling()) <= 0) {
        return Optional.empty();
      }
      Consequence consequence = policy.flight().lowestLogicalFare().onViolation();
      return Optional.of(
          new Violation(
              RULE_LLF,
              "FARE_ABOVE_POLICY_CEILING",
              "fare "
                  + c.air().fare()
                  + " exceeds the in-policy ceiling "
                  + ctx.inPolicyCeiling()
                  + " (lowest logical fare "
                  + ctx.referenceFare()
                  + " + "
                  + Money.of(
                      policy.currency(), policy.flight().lowestLogicalFare().maxAmountAbove())
                  + ")",
              consequence,
              approverFor(consequence)));
    }
  }

  private static final class HotelNightlyLimitRule implements TripRule {
    @Override
    public String id() {
      return RULE_HOTEL;
    }

    @Override
    public Optional<Violation> evaluate(
        PolicyDocument policy, Trip trip, Candidate c, Context ctx) {
      Long limit = policy.hotel().nightlyLimit();
      if (c.hotels().isEmpty() || limit == null) {
        return Optional.empty();
      }
      Money max = Money.of(policy.currency(), limit);
      // every stay is checked; the first one over the limit is the objection (its stay is named)
      for (Facts.Hotel h : c.hotels()) {
        if (!h.nightlyRate().currency().equals(policy.currency())
            || h.nightlyRate().compareTo(max) <= 0) {
          continue;
        }
        Consequence consequence = policy.hotel().consequence();
        return Optional.of(
            new Violation(
                RULE_HOTEL,
                "HOTEL_RATE_ABOVE_LIMIT",
                "nightly rate "
                    + h.nightlyRate()
                    + (h.componentId() == null ? "" : " for stay " + h.componentId())
                    + " exceeds the "
                    + max
                    + " limit",
                consequence,
                approverFor(consequence)));
      }
      return Optional.empty();
    }
  }

  /** Slice 3: each ground transfer within the policy's per-transfer limit. */
  private static final class GroundTransferLimitRule implements TripRule {
    @Override
    public String id() {
      return RULE_GROUND;
    }

    @Override
    public Optional<Violation> evaluate(
        PolicyDocument policy, Trip trip, Candidate c, Context ctx) {
      Long limit = policy.ground().perTransferLimit();
      if (c.ground().isEmpty() || limit == null) {
        return Optional.empty();
      }
      Money max = Money.of(policy.currency(), limit);
      for (Facts.Ground g : c.ground()) {
        if (!g.fare().currency().equals(policy.currency()) || g.fare().compareTo(max) <= 0) {
          continue;
        }
        Consequence consequence = policy.ground().consequence();
        return Optional.of(
            new Violation(
                RULE_GROUND,
                "GROUND_TRANSFER_ABOVE_LIMIT",
                "transfer "
                    + (g.componentId() == null ? "" : g.componentId() + " ")
                    + "costs "
                    + g.fare()
                    + ", above the "
                    + max
                    + " limit per transfer",
                consequence,
                approverFor(consequence)));
      }
      return Optional.empty();
    }
  }

  /** Slice 3: the whole itinerary, every component included, against the trip budget. */
  private static final class TripBudgetRule implements TripRule {
    @Override
    public String id() {
      return RULE_TRIP_BUDGET;
    }

    @Override
    public Optional<Violation> evaluate(
        PolicyDocument policy, Trip trip, Candidate c, Context ctx) {
      Long budget = policy.trip().maxTotal();
      if (budget == null || !c.total().currency().equals(policy.currency())) {
        return Optional.empty();
      }
      Money max = Money.of(policy.currency(), budget);
      if (c.total().compareTo(max) <= 0) {
        return Optional.empty();
      }
      Consequence consequence = policy.trip().consequence();
      return Optional.of(
          new Violation(
              RULE_TRIP_BUDGET,
              "TRIP_TOTAL_ABOVE_BUDGET",
              "the itinerary costs " + c.total() + ", above the " + max + " trip budget",
              consequence,
              approverFor(consequence)));
    }
  }

  private static final class ManagerApprovalThresholdRule implements TripRule {
    @Override
    public String id() {
      return RULE_MANAGER_THRESHOLD;
    }

    @Override
    public Optional<Violation> evaluate(
        PolicyDocument policy, Trip trip, Candidate c, Context ctx) {
      Long threshold = policy.approval().managerRequiredAbove();
      if (threshold == null || !c.total().currency().equals(policy.currency())) {
        return Optional.empty();
      }
      Money max = Money.of(policy.currency(), threshold);
      if (c.total().compareTo(max) <= 0) {
        return Optional.empty();
      }
      return Optional.of(
          new Violation(
              RULE_MANAGER_THRESHOLD,
              "TOTAL_ABOVE_APPROVAL_THRESHOLD",
              "trip total " + c.total() + " exceeds the " + max + " auto-approve limit",
              Consequence.REQUIRE_APPROVAL,
              ROLE_MANAGER));
    }
  }

  private static @Nullable String approverFor(Consequence consequence) {
    return consequence == Consequence.REQUIRE_APPROVAL ? ROLE_MANAGER : null;
  }
}
