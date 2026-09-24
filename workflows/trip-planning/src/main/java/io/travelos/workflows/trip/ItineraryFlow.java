package io.travelos.workflows.trip;

import com.google.protobuf.Timestamp;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.common.v1.TimeWindow;
import io.travelos.contracts.offer.v1.Bundle;
import io.travelos.contracts.offer.v1.FlightSegment;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.offer.v1.OfferType;
import io.travelos.contracts.optimization.v1.ComponentCandidates;
import io.travelos.contracts.optimization.v1.ComponentSelection;
import io.travelos.contracts.optimization.v1.ItineraryConstraints;
import io.travelos.contracts.optimization.v1.LearningInputs;
import io.travelos.contracts.optimization.v1.OptimizationPreferences;
import io.travelos.contracts.optimization.v1.OptimizeItineraryRequest;
import io.travelos.contracts.optimization.v1.OptimizeItineraryResponse;
import io.travelos.contracts.optimization.v1.RankedCandidate;
import io.travelos.contracts.optimization.v1.Weights;
import io.travelos.contracts.order.v1.CreateOrderCommand;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderItem;
import io.travelos.contracts.order.v1.OrderStatus;
import io.travelos.contracts.policy.v1.CandidateDecision;
import io.travelos.contracts.policy.v1.EvaluateTripRequest;
import io.travelos.contracts.policy.v1.EvaluateTripResponse;
import io.travelos.contracts.policy.v1.Outcome;
import io.travelos.contracts.policy.v1.PolicyDecision;
import io.travelos.contracts.supplier.v1.Passenger;
import io.travelos.contracts.supplier.v1.QuoteOfferRequest;
import io.travelos.contracts.supplier.v1.QuoteOfferResponse;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.supplier.v1.SearchGroundRequest;
import io.travelos.contracts.supplier.v1.SearchGroundResponse;
import io.travelos.contracts.supplier.v1.SearchHotelsRequest;
import io.travelos.contracts.supplier.v1.SearchHotelsResponse;
import io.travelos.contracts.trip.v1.ComponentState;
import io.travelos.contracts.trip.v1.Itinerary;
import io.travelos.contracts.trip.v1.Leg;
import io.travelos.contracts.trip.v1.Stay;
import io.travelos.contracts.trip.v1.Transfer;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.TravelIntent;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.TripStatus;
import io.travelos.contracts.trip.v1.UpdateComponentsRequest;
import io.travelos.workflows.TripPlanning;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Slice 3: plans and books a multi-component itinerary inside the trip workflow. Deterministic code
 * over idempotent activities, exactly like the Slice 1 path: suppliers search per component, policy
 * filters every offer and then judges the whole itinerary, the optimizer composes it, a person
 * approves when policy says so, every quote is checked again right before the only mutation (a
 * material change goes back through policy and approval), and the Order service books in dependency
 * order and compensates in reverse. Component states are reported to Travel Core at every step so
 * the API can say where each leg, stay and transfer stands.
 */
final class ItineraryFlow {

  /** What the flow needs from the workflow that hosts it. */
  interface Host {
    Trip transition(
        String tenant, String tripId, TripStatus to, Consumer<TransitionTripRequest.Builder> b);

    /** APPROVED | REJECTED | CANCELLED (withdrawn meanwhile), or null when nobody decided. */
    @Nullable String awaitDecision(String tenant, String tripId);

    /**
     * Phase 3: AUTHORIZED | CANCELLED | REFRESH | SELECTION:&lt;bundleId&gt;, or null when nobody
     * acted in time.
     */
    @Nullable String awaitPurchase(String tenant, String tripId, String selectedBundleId);

    /** Moves the trip to BOOKING, or null when it was cancelled while it waited. */
    @Nullable Trip beginBooking(
        String tenant, String tripId, Consumer<TransitionTripRequest.Builder> b);

    TripPlanning.@Nullable ApprovalDecision decision();

    void forgetDecision();

    TripWorkflow.Outcome fail(
        String tenant, String tripId, String stageName, String code, String message);

    void stage(TripPlanning.Stage stage);

    String paymentToken();

    /** Phase 4: the passenger as suppliers need them, from Enterprise Context at booking time. */
    Passenger passenger(String tenant, String tripId, Trip trip);

    @Nullable String explain(
        String tenant,
        String tripId,
        TravelIntent intent,
        Bundle selected,
        List<RankedCandidate> ranking,
        PolicyDecision decision,
        int searched,
        int permitted);

    RequestContext ctx(String tenant, String tripId, String idempotencyKey);

    /** Slice 5: the learning inputs for this attempt, resolved once and pinned by the history. */
    LearningInputs resolveLearning(String tenant, String tripId, String travelerId);
  }

  static final int MIN_CONNECTION_MINUTES = 60;
  static final int TRANSFER_AFTER_ARRIVAL_MINUTES = 45;
  static final int TRANSFER_BEFORE_DEPARTURE_MINUTES = 90;
  static final Duration TRANSFER_REACH = Duration.ofHours(4);
  static final Duration TRANSFER_EARLIEST_BEFORE = Duration.ofHours(6);

  private final TripActivities activities;
  private final TripActivities booking;
  private final Host host;
  private final Logger log;

  ItineraryFlow(TripActivities activities, TripActivities booking, Host host, Logger log) {
    this.activities = activities;
    this.booking = booking;
    this.host = host;
    this.log = log;
  }

  /** One component of the frozen itinerary, in one shape. */
  record Component(
      String id,
      OfferType type,
      int sequence,
      boolean required,
      List<String> dependsOn,
      @Nullable Leg leg,
      @Nullable Stay stay,
      @Nullable Transfer transfer) {}

  /** Plans per run: the first, and one more when a quote expired while a person was deciding. */
  static final int MAX_PLANS = 2;

  private static final TripWorkflow.Outcome REPLAN =
      new TripWorkflow.Outcome("", "REPLAN", null, null, null);

  /** True once a plan a person had approved was abandoned: the next plan asks a person again. */
  private boolean decidedBefore;

  /**
   * Phase 7: the governance of the trip's scope, read once per run; null when policy cannot say.
   */
  private io.travelos.contracts.policy.v1.@Nullable Governance governance;

  TripWorkflow.Outcome run(String tenant, String tripId, Trip trip) {
    try {
      governance =
          activities.governance(
              io.travelos.contracts.policy.v1.GetGovernanceRequest.newBuilder()
                  .setCtx(host.ctx(tenant, tripId, ""))
                  .setTravelerId(trip.getTravelerId())
                  .setScope(Governed.scopeOf(trip))
                  .build());
    } catch (ActivityFailure e) {
      log.warn("trip {}: governance unavailable; searching public fares", tripId);
    }
    for (int attempt = 1; ; attempt++) {
      TripWorkflow.Outcome outcome = plan(tenant, tripId, trip, attempt);
      if (outcome != REPLAN) {
        return outcome;
      }
    }
  }

  /** One plan-and-book pass; REPLAN when a quote expired after approval and a pass is left. */
  private TripWorkflow.Outcome plan(String tenant, String tripId, Trip trip, int attempt) {
    TravelIntent intent = trip.getIntent();
    Itinerary itinerary = intent.getItinerary();
    List<Component> components = components(itinerary);
    Map<String, Leg> legs = new HashMap<>();
    itinerary.getLegsList().forEach(l -> legs.put(l.getComponentId(), l));
    report(tenant, tripId, components, c -> state(c, "PLANNED", null, null));

    // ---- search, one supplier search per component
    host.stage(TripPlanning.Stage.SEARCHING);
    Map<String, List<Offer>> offersByComponent = new LinkedHashMap<>();
    int searched = 0;
    for (Component c : components) {
      List<Offer> found = search(tenant, tripId, intent, c, legs);
      offersByComponent.put(c.id(), found);
      searched += found.size();
      if (found.isEmpty() && c.required()) {
        report(tenant, tripId, List.of(c), x -> state(x, "FAILED", null, "NO_OFFERS"));
        return host.fail(
            tenant,
            tripId,
            "SEARCH",
            "NO_OFFERS",
            "no supplier returned offers for " + describe(c));
      }
    }

    // ---- policy, per component: every offer is judged on its own before anything is combined
    host.stage(TripPlanning.Stage.EVALUATING_POLICY);
    Map<String, List<Offer>> permittedByComponent = new LinkedHashMap<>();
    Map<String, Integer> deniedByComponent = new LinkedHashMap<>();
    int permitted = 0;
    for (Component c : components) {
      List<Offer> offers = offersByComponent.get(c.id());
      List<Offer> allowed = new ArrayList<>();
      if (!offers.isEmpty()) {
        EvaluateTripResponse verdicts =
            activities.evaluatePolicy(
                EvaluateTripRequest.newBuilder()
                    .setCtx(host.ctx(tenant, tripId, ""))
                    .setTripId(tripId)
                    .setTravelerId(trip.getTravelerId())
                    .setIntent(intent)
                    .setScope(Governed.scopeOf(trip))
                    .addAllCandidates(offers.stream().map(ItineraryFlow::single).toList())
                    .build());
        Map<String, Outcome> outcomes = new HashMap<>();
        for (CandidateDecision cd : verdicts.getCandidatesList()) {
          outcomes.put(cd.getBundleId(), cd.getDecision().getOutcome());
        }
        for (Offer o : offers) {
          Outcome outcome = outcomes.get(single(o).getBundleId());
          if (outcome != null && outcome != Outcome.DENY) {
            allowed.add(o);
          }
        }
      }
      deniedByComponent.put(c.id(), offers.size() - allowed.size());
      permittedByComponent.put(c.id(), allowed);
      permitted += allowed.size();
      if (allowed.isEmpty() && c.required() && !offers.isEmpty()) {
        report(tenant, tripId, List.of(c), x -> state(x, "FAILED", null, "ALL_CANDIDATES_DENIED"));
        return host.fail(
            tenant,
            tripId,
            "POLICY",
            "ALL_CANDIDATES_DENIED",
            "every offer for " + describe(c) + " was denied by policy");
      }
    }

    // ---- optimize the whole itinerary
    host.stage(TripPlanning.Stage.OPTIMIZING);
    OptimizeItineraryRequest.Builder optimize =
        OptimizeItineraryRequest.newBuilder()
            .setCtx(host.ctx(tenant, tripId, ""))
            .setTripId(tripId)
            .setConstraints(
                ItineraryConstraints.newBuilder()
                    .setCurrency(itinerary.getCurrency())
                    .setMinConnectionMinutes(MIN_CONNECTION_MINUTES)
                    .setTransferAfterArrivalMinutes(TRANSFER_AFTER_ARRIVAL_MINUTES)
                    .setTransferBeforeDepartureMinutes(TRANSFER_BEFORE_DEPARTURE_MINUTES))
            .setPreferences(
                OptimizationPreferences.newBuilder()
                    .setWeights(
                        Weights.newBuilder()
                            .setCost(0.4)
                            .setTime(0.25)
                            .setRisk(0.15)
                            .setPreference(0.1)
                            .setExperience(0.1))
                    .addAllPreferredCarriers(Purchase.preferredCarriers(intent)));
    for (Component c : components) {
      optimize.addComponents(candidates(c, permittedByComponent.get(c.id()), legs));
    }
    optimize.setLearning(host.resolveLearning(tenant, tripId, trip.getTravelerId()));
    OptimizeItineraryResponse optimized = activities.optimizeItinerary(optimize.build());
    if (!optimized.hasSelected() || optimized.getSelected().getOffersCount() == 0) {
      for (ComponentSelection sel : optimized.getComponentsList()) {
        if ("INFEASIBLE".equals(sel.getOutcome())) {
          components.stream()
              .filter(c -> c.id().equals(sel.getComponentId()))
              .findFirst()
              .ifPresent(
                  c ->
                      report(
                          tenant,
                          tripId,
                          List.of(c),
                          x ->
                              state(
                                  x,
                                  "FAILED",
                                  null,
                                  sel.getInfeasibilityReasonsCount() > 0
                                      ? sel.getInfeasibilityReasons(0)
                                      : "INFEASIBLE")));
        }
      }
      return host.fail(
          tenant,
          tripId,
          "OPTIMIZATION",
          "NO_FEASIBLE_ITINERARY",
          "no combination of permitted offers fits: "
              + String.join(", ", optimized.getInfeasibilityReasonsList()));
    }
    Bundle selected = optimized.getSelected();
    List<RankedCandidate> ranking = List.of(rankedFor(selected, optimized));

    // ---- policy on the whole itinerary: budget, thresholds, approval
    PolicyDecision selectedDecision = judge(tenant, tripId, trip, intent, selected);
    if (selectedDecision.getOutcome() == Outcome.DENY) {
      return host.fail(
          tenant,
          tripId,
          "POLICY",
          "ITINERARY_DENIED",
          "policy denies the itinerary: " + reasonSummary(selectedDecision));
    }
    Map<String, Offer> chosen = byComponent(selected);
    report(
        tenant,
        tripId,
        components,
        c -> {
          Offer o = chosen.get(c.id());
          return o == null ? state(c, "SKIPPED", null, null) : state(c, "QUOTED", o, null);
        });
    String explanation =
        host.explain(
            tenant, tripId, intent, selected, ranking, selectedDecision, searched, permitted);

    // ---- Phase 3: purchase authority. Policy granted it for this plan (Travel Core records a
    // POLICY_AUTONOMY authorization) or a person confirms the quoted plan before anything else.
    final boolean confirm = Purchase.confirmRequired(trip, selectedDecision);
    Bundle quoted = selected;
    if (confirm) {
      Confirmation c =
          awaitConfirmation(
              tenant,
              tripId,
              components,
              selected,
              selectedDecision,
              optimized.getOptimizationRunId(),
              explanation,
              "planned; a person confirms the purchase",
              attempt,
              true);
      if (c.outcome() != null) {
        return c.outcome();
      }
      quoted = c.plan();
    }
    final Bundle planned = quoted;
    final Money plannedTotal = planned.getTotal();
    final boolean autonomous = !confirm;

    // ---- approval
    String approvalId = null;
    String approvedBy = null;
    if (selectedDecision.getRequiresApproval() || decidedBefore) {
      host.stage(TripPlanning.Stage.AWAITING_APPROVAL);
      String role =
          selectedDecision.getApproversCount() > 0
              ? selectedDecision.getApprovers(0).getRole()
              : "MANAGER";
      Trip awaiting =
          host.transition(
              tenant,
              tripId,
              TripStatus.AWAITING_APPROVAL,
              b -> {
                b.setSelectedBundleId(planned.getBundleId())
                    .setOptimizationRunId(optimized.getOptimizationRunId())
                    .setPolicyDecisionId(selectedDecision.getDecisionId())
                    .setTotal(plannedTotal)
                    .setApproverRole(role)
                    .addAllApprovalChain(Governed.chain(selectedDecision, role))
                    .setApprovalExpiresAfterSeconds(
                        selectedDecision.getApprovalExpiresAfterSeconds())
                    .setAutonomousPurchase(autonomous)
                    .setQuoteExpiresAt(Purchase.quoteExpiry(planned, Workflow.currentTimeMillis()))
                    .setConditions(Purchase.conditions(planned))
                    .setReason(
                        decidedBefore
                            ? "the quote behind the earlier approval expired; the re-planned"
                                + " itinerary needs a decision again"
                            : reasonSummary(selectedDecision));
                if (decidedBefore) {
                  b.setReplanReason("QUOTE_EXPIRED");
                }
                if (explanation != null) {
                  b.setExplanation(explanation);
                }
              });
      approvalId = awaiting.getApprovalId();
      String verdict = host.awaitDecision(tenant, tripId);
      if (verdict == null) {
        return host.fail(tenant, tripId, "APPROVAL", "APPROVAL_TIMED_OUT", "no decision in time");
      }
      if ("CANCELLED".equals(verdict)) {
        return withdrawn(tripId);
      }
      if (!"APPROVED".equals(verdict)) {
        return rejected(tenant, tripId);
      }
      approvedBy = host.decision() == null ? "a manager" : host.decision().decidedBy();
      String by = approvedBy;
      host.transition(tenant, tripId, TripStatus.APPROVED, b -> b.setReason("approved by " + by));
    } else {
      host.transition(
          tenant,
          tripId,
          TripStatus.APPROVED,
          b -> {
            b.setSelectedBundleId(planned.getBundleId())
                .setOptimizationRunId(optimized.getOptimizationRunId())
                .setPolicyDecisionId(selectedDecision.getDecisionId())
                .setTotal(plannedTotal)
                .setAutonomousPurchase(autonomous)
                .setQuoteExpiresAt(Purchase.quoteExpiry(planned, Workflow.currentTimeMillis()))
                .setConditions(Purchase.conditions(planned))
                .setReason(
                    confirm
                        ? "purchase authorized; in policy, no approval required"
                        : "in policy, no approval required");
            if (explanation != null) {
              b.setExplanation(explanation);
            }
          });
    }

    // ---- revalidate every quote right before the only mutation
    host.stage(TripPlanning.Stage.REVALIDATING);
    report(
        tenant,
        tripId,
        components,
        c ->
            chosen.containsKey(c.id())
                ? state(c, "REVALIDATING", chosen.get(c.id()), null)
                : state(c, "SKIPPED", null, null));
    Bundle.Builder revalidated = Bundle.newBuilder().setBundleId(planned.getBundleId());
    List<String> changedComponents = new ArrayList<>();
    long newTotal = 0;
    boolean requoted = false;
    for (Offer o : planned.getOffersList()) {
      QuoteOfferResponse quote;
      try {
        quote =
            activities.quote(
                QuoteOfferRequest.newBuilder()
                    .setCtx(host.ctx(tenant, tripId, ""))
                    .setProvider(o.getProvider())
                    .setProviderOfferId(o.getProviderOfferId())
                    .build());
      } catch (ActivityFailure e) {
        if (isFinal(e)) {
          Component c = componentOf(components, o.getComponentId());
          String why = describe(c) + " can no longer be quoted: " + e.getCause().getMessage();
          if (attempt < MAX_PLANS) {
            // The quote died while the trip waited: a long approval outlives a supplier's hold.
            // That is not a failure of the request. Plan again from a fresh search, and a person
            // decides again if a person had decided on the plan that expired.
            log.warn("trip {}: {}; re-planning ({} of {})", tripId, why, attempt + 1, MAX_PLANS);
            decidedBefore = decidedBefore || approvalId != null;
            host.forgetDecision();
            host.transition(
                tenant,
                tripId,
                TripStatus.PLANNING,
                b -> b.setReason(why + "; re-planning").setReplanReason("QUOTE_EXPIRED"));
            return REPLAN;
          }
          report(tenant, tripId, List.of(c), x -> state(x, "FAILED", o, "OFFER_GONE"));
          return host.fail(tenant, tripId, "REVALIDATION", "OFFER_GONE", why);
        }
        throw e;
      }
      Offer current = requoted(o, quote.getOffer());
      if (current.getTotal().getAmountMinor() != o.getTotal().getAmountMinor()
          || quote.getRequoted()) {
        changedComponents.add(o.getComponentId());
      }
      requoted |= quote.getRequoted();
      newTotal += current.getTotal().getAmountMinor();
      revalidated.addOffers(current);
    }
    revalidated.setTotal(
        Money.newBuilder().setCurrency(plannedTotal.getCurrency()).setAmountMinor(newTotal));
    Bundle plan = revalidated.build();
    PolicyDecision bookingDecision = selectedDecision;
    if (newTotal > plannedTotal.getAmountMinor()) {
      // Material: the approved plan is stale. Policy judges the new one; a person decides again
      // whenever a person had decided before, or policy now asks for one.
      log.warn(
          "trip {}: revalidation changed the total from {} to {} ({}); re-planning",
          tripId,
          plannedTotal.getAmountMinor(),
          newTotal,
          changedComponents);
      bookingDecision = judge(tenant, tripId, trip, intent, plan);
      if (bookingDecision.getOutcome() == Outcome.DENY) {
        return host.fail(
            tenant,
            tripId,
            "REVALIDATION",
            "REVALIDATED_PLAN_DENIED",
            "the re-quoted itinerary is denied by policy: " + reasonSummary(bookingDecision));
      }
      Map<String, Offer> requotedOffers = byComponent(plan);
      report(
          tenant,
          tripId,
          components,
          c ->
              requotedOffers.containsKey(c.id())
                  ? state(c, "QUOTED", requotedOffers.get(c.id()), null)
                  : state(c, "SKIPPED", null, null));
      if (Purchase.confirmRequired(trip, bookingDecision)) {
        // The price a person (or policy) authorized is not the price any more: a person confirms
        // the re-quoted plan before it goes back to approval or to booking.
        Confirmation c =
            awaitConfirmation(
                tenant,
                tripId,
                components,
                plan,
                bookingDecision,
                optimized.getOptimizationRunId(),
                null,
                "revalidation raised the total to " + money(plan.getTotal()) + "; confirm again",
                attempt,
                false);
        if (c.outcome() != null) {
          return c.outcome();
        }
      }
      if (approvalId != null || bookingDecision.getRequiresApproval()) {
        host.stage(TripPlanning.Stage.AWAITING_APPROVAL);
        PolicyDecision again = bookingDecision;
        String role = again.getApproversCount() > 0 ? again.getApprovers(0).getRole() : "MANAGER";
        host.forgetDecision();
        Trip awaiting =
            host.transition(
                tenant,
                tripId,
                TripStatus.AWAITING_APPROVAL,
                b ->
                    b.setSelectedBundleId(plan.getBundleId())
                        .setPolicyDecisionId(again.getDecisionId())
                        .setTotal(plan.getTotal())
                        .setApproverRole(role)
                        .addAllApprovalChain(Governed.chain(again, role))
                        .setApprovalExpiresAfterSeconds(again.getApprovalExpiresAfterSeconds())
                        .setAutonomousPurchase(!Purchase.confirmRequired(trip, again))
                        .setConditions(Purchase.conditions(plan))
                        .setReplanReason(
                            requotedOnly(changedComponents, plan, planned)
                                ? "QUOTE_EXPIRED"
                                : "PRICE_CHANGED")
                        .setReason(
                            "revalidation changed the total to "
                                + money(plan.getTotal())
                                + " ("
                                + String.join(", ", changedComponents)
                                + "); the earlier approval is stale"));
        approvalId = awaiting.getApprovalId();
        String verdict = host.awaitDecision(tenant, tripId);
        if (verdict == null) {
          return host.fail(
              tenant,
              tripId,
              "APPROVAL",
              "APPROVAL_TIMED_OUT",
              "no decision on the re-quoted plan");
        }
        if ("CANCELLED".equals(verdict)) {
          return withdrawn(tripId);
        }
        if (!"APPROVED".equals(verdict)) {
          return rejected(tenant, tripId);
        }
        String by = host.decision() == null ? "a manager" : host.decision().decidedBy();
        host.transition(
            tenant,
            tripId,
            TripStatus.APPROVED,
            b -> b.setReason("re-quoted plan approved by " + by));
      }
    } else if (!changedComponents.isEmpty()) {
      log.info(
          "trip {}: re-quoted {} without a higher total; booking as re-quoted",
          tripId,
          changedComponents);
    }

    // ---- book: one command, one order, N components in dependency order; only while the first
    // departure is still ahead and nobody withdrew the trip while it waited
    host.stage(TripPlanning.Stage.BOOKING);
    Instant firstDeadline = instant(itinerary.getLegs(0).getArrivalDeadline());
    if (!firstDeadline.isAfter(Instant.ofEpochMilli(Workflow.currentTimeMillis()))) {
      return host.fail(
          tenant,
          tripId,
          "REVALIDATION",
          "DEPARTURE_PASSED",
          "the first leg's window closed at "
              + firstDeadline
              + " while the trip waited; nothing was booked");
    }
    // Phase 7: the scope's budget, reserved under its lock; a hard budget that does not fit stops
    // the purchase here (a soft one was judged by policy and, when required, approved).
    io.travelos.contracts.policy.v1.BudgetReservation reserved = null;
    try {
      reserved =
          activities.reserveBudget(
              io.travelos.contracts.policy.v1.ReserveBudgetRequest.newBuilder()
                  .setCtx(host.ctx(tenant, tripId, tripId + ":RESERVE-BUDGET:1"))
                  .setTripId(tripId)
                  .setTravelerId(trip.getTravelerId())
                  .setScope(Governed.scopeOf(trip))
                  .setAmount(plan.getTotal())
                  .build());
    } catch (ActivityFailure e) {
      log.warn("trip {}: budget could not be reserved; proceeding without", tripId);
    }
    if (reserved != null
        && reserved.getStatus() == io.travelos.contracts.policy.v1.BudgetStatus.EXCEEDED
        && reserved.getHard()) {
      return host.fail(tenant, tripId, "BOOKING", "BUDGET_EXCEEDED", reserved.getMessage());
    }
    PolicyDecision finalDecision = bookingDecision;
    Trip bookable;
    while (true) {
      try {
        bookable =
            host.beginBooking(
                tenant,
                tripId,
                b ->
                    b.setReason("booking")
                        .setSelectedBundleId(plan.getBundleId())
                        .setPolicyDecisionId(finalDecision.getDecisionId())
                        .setTotal(plan.getTotal())
                        .setAutonomousPurchase(!Purchase.confirmRequired(trip, finalDecision))
                        .setConditions(Purchase.conditions(plan)));
        break;
      } catch (ActivityFailure e) {
        if (!Purchase.notAuthorized(e)) {
          throw e;
        }
        // The authorization lapsed (expired, withdrawn) between confirmation and booking: the
        // person confirms again; the approval stands, the price is unchanged.
        Confirmation c =
            awaitConfirmation(
                tenant,
                tripId,
                components,
                plan,
                finalDecision,
                optimized.getOptimizationRunId(),
                null,
                "the purchase authorization lapsed before booking; confirm again",
                attempt,
                false);
        if (c.outcome() != null) {
          return c.outcome();
        }
        host.transition(
            tenant, tripId, TripStatus.APPROVED, b -> b.setReason("purchase re-authorized"));
        host.stage(TripPlanning.Stage.BOOKING);
      }
    }
    if (bookable == null) {
      return withdrawn(tripId);
    }
    Map<String, Offer> booked = byComponent(plan);
    report(
        tenant,
        tripId,
        components,
        c ->
            booked.containsKey(c.id())
                ? state(c, "BOOKING", booked.get(c.id()), null)
                : state(c, "SKIPPED", null, null));
    CreateOrderCommand.Builder command =
        CreateOrderCommand.newBuilder()
            .setCtx(host.ctx(tenant, tripId, tripId + ":CREATE-ORDER:1"))
            .setTripId(tripId)
            .setTravelerId(trip.getTravelerId())
            .setBundle(plan)
            .setPolicyDecisionId(finalDecision.getDecisionId())
            .setOptimizationRunId(optimized.getOptimizationRunId())
            .addPassengers(host.passenger(tenant, tripId, trip))
            .setPaymentToken(host.paymentToken());
    if (approvalId != null) {
      command.setApprovalId(approvalId);
    }
    Order order = booking.createOrder(command.build());
    Map<String, OrderItem> items = itemsByComponent(order);
    report(tenant, tripId, components, c -> fromOrder(c, items.get(c.id()), booked.get(c.id())));
    if (order.getStatus() != OrderStatus.CONFIRMED) {
      boolean compensating =
          order.getItemsList().stream()
              .anyMatch(
                  i ->
                      i.getStatus().name().endsWith("CANCELLED")
                          || i.getStatus().name().endsWith("CANCEL_FAILED"));
      if (compensating) {
        host.stage(TripPlanning.Stage.COMPENSATING);
      }
      String code =
          !order.getCompensated() && order.getExposuresCount() > 0
              ? "COMPENSATION_INCOMPLETE"
              : order.getFailureCode().isBlank()
                  ? "ORDER_" + order.getStatus().name()
                  : order.getFailureCode();
      return host.fail(
          tenant,
          tripId,
          !order.getCompensated() && order.getExposuresCount() > 0 ? "COMPENSATION" : "BOOKING",
          code,
          "order "
              + order.getOrderId()
              + " ended "
              + order.getStatus()
              + (order.getCompensated()
                  ? " (compensated)"
                  : order.getExposuresCount() > 0
                      ? " (" + order.getExposuresCount() + " exposure(s) need a person)"
                      : ""));
    }
    host.transition(
        tenant,
        tripId,
        TripStatus.BOOKED,
        b ->
            b.setOrderId(order.getOrderId())
                .setTotal(order.getTotal())
                .setReason("order confirmed"));
    host.stage(TripPlanning.Stage.BOOKED);
    return new TripWorkflow.Outcome(tripId, "BOOKED", order.getOrderId(), null, null);
  }

  // ------------------------------------------------------------------ Phase 3: purchase

  /** What waiting for a person ended with: an outcome to return, or the plan to go on with. */
  record Confirmation(TripWorkflow.@Nullable Outcome outcome, Bundle plan) {}

  /**
   * QUOTED until a person authorizes the purchase. A refresh re-quotes every offer (when allowed;
   * an offer gone for good re-plans like revalidation does); a selection means nothing here (the
   * itinerary optimizer yields one composition), so the quote is simply stated again.
   */
  private Confirmation awaitConfirmation(
      String tenant,
      String tripId,
      List<Component> components,
      Bundle initial,
      PolicyDecision decision,
      String optimizationRunId,
      @Nullable String explanation,
      String reason,
      int attempt,
      boolean allowRefresh) {
    Bundle current = initial;
    String why = reason;
    while (true) {
      host.stage(TripPlanning.Stage.AWAITING_PURCHASE);
      final Bundle quoted = current;
      final String quotedReason = why;
      host.transition(
          tenant,
          tripId,
          TripStatus.QUOTED,
          b -> {
            b.setSelectedBundleId(quoted.getBundleId())
                .setOptimizationRunId(optimizationRunId)
                .setPolicyDecisionId(decision.getDecisionId())
                .setTotal(quoted.getTotal())
                .setReason(quotedReason)
                .setQuoteExpiresAt(Purchase.quoteExpiry(quoted, Workflow.currentTimeMillis()))
                .setConditions(Purchase.conditions(quoted))
                .addAlternatives(Purchase.alternative(quoted, 1));
            if (explanation != null) {
              b.setExplanation(explanation);
            }
          });
      String verdict = host.awaitPurchase(tenant, tripId, current.getBundleId());
      if (verdict == null) {
        return new Confirmation(
            host.fail(
                tenant,
                tripId,
                "PURCHASE",
                "PURCHASE_TIMED_OUT",
                "no purchase authorization in time"),
            current);
      }
      if ("CANCELLED".equals(verdict)) {
        return new Confirmation(withdrawn(tripId), current);
      }
      if ("AUTHORIZED".equals(verdict)) {
        return new Confirmation(null, current);
      }
      if ("REFRESH".equals(verdict) && allowRefresh) {
        Bundle fresh = requoteAll(tenant, tripId, components, current);
        if (fresh == null) {
          if (attempt < MAX_PLANS) {
            host.forgetDecision();
            host.transition(
                tenant,
                tripId,
                TripStatus.PLANNING,
                b ->
                    b.setReason("an offer of the quoted plan is gone; re-planning")
                        .setReplanReason("QUOTE_EXPIRED"));
            return new Confirmation(REPLAN, current);
          }
          return new Confirmation(
              host.fail(
                  tenant,
                  tripId,
                  "REVALIDATION",
                  "OFFER_GONE",
                  "the quoted plan can no longer be priced"),
              current);
        }
        current = fresh;
        why = "quote refreshed";
        continue;
      }
      why = "the quote stands";
    }
  }

  /** Every offer priced again, or null when one can no longer be quoted at all. */
  private @Nullable Bundle requoteAll(
      String tenant, String tripId, List<Component> components, Bundle current) {
    Bundle.Builder fresh = Bundle.newBuilder().setBundleId(current.getBundleId());
    long total = 0;
    for (Offer o : current.getOffersList()) {
      QuoteOfferResponse quote;
      try {
        quote =
            activities.quote(
                QuoteOfferRequest.newBuilder()
                    .setCtx(host.ctx(tenant, tripId, ""))
                    .setProvider(o.getProvider())
                    .setProviderOfferId(o.getProviderOfferId())
                    .build());
      } catch (ActivityFailure e) {
        if (isFinal(e)) {
          log.warn(
              "trip {}: {} can no longer be quoted",
              tripId,
              describe(componentOf(components, o.getComponentId())));
          return null;
        }
        throw e;
      }
      Offer requoted = requoted(o, quote.getOffer());
      total += requoted.getTotal().getAmountMinor();
      fresh.addOffers(requoted);
    }
    Map<String, Offer> requotedOffers = byComponent(fresh.build());
    report(
        tenant,
        tripId,
        components,
        c ->
            requotedOffers.containsKey(c.id())
                ? state(c, "QUOTED", requotedOffers.get(c.id()), null)
                : state(c, "SKIPPED", null, null));
    return fresh
        .setTotal(
            Money.newBuilder().setCurrency(current.getTotal().getCurrency()).setAmountMinor(total))
        .build();
  }

  // ------------------------------------------------------------------ steps

  private List<Offer> search(
      String tenant, String tripId, TravelIntent intent, Component c, Map<String, Leg> legs) {
    List<Offer> out = new ArrayList<>();
    switch (c.type()) {
      case AIR -> {
        Leg leg = c.leg();
        SearchAirResponse found =
            activities.search(
                SearchAirRequest.newBuilder()
                    .setCtx(host.ctx(tenant, tripId, ""))
                    .setOrigin(leg.getOrigin())
                    .setDestination(leg.getDestination())
                    .setPassengers(Math.max(1, intent.getTravelers()))
                    .addAllCabins(Purchase.cabins(intent))
                    .addAllNegotiatedRates(Governed.airRates(governance))
                    .setOutboundDeparture(
                        TimeWindow.newBuilder()
                            .setNotBefore(leg.getEarliestDeparture())
                            .setNotAfter(leg.getArrivalDeadline()))
                    .build());
        Purchase.filter(found.getOffersList(), intent)
            .forEach(o -> out.add(o.toBuilder().setComponentId(c.id()).build()));
      }
      case HOTEL -> {
        Stay stay = c.stay();
        SearchHotelsResponse found =
            activities.searchHotels(
                SearchHotelsRequest.newBuilder()
                    .setCtx(host.ctx(tenant, tripId, ""))
                    .setCity(stay.getCity())
                    .setCheckInDate(stay.getCheckInDate())
                    .setCheckOutDate(stay.getCheckOutDate())
                    .setGuests(Math.max(1, intent.getTravelers()))
                    .addAllNegotiatedRates(Governed.hotelRates(governance))
                    .build());
        found.getOffersList().forEach(o -> out.add(o.toBuilder().setComponentId(c.id()).build()));
      }
      case GROUND -> {
        Transfer t = c.transfer();
        TimeWindow window = pickupWindow(t, legs);
        if (window == null) {
          return out;
        }
        SearchGroundResponse found =
            activities.searchGround(
                SearchGroundRequest.newBuilder()
                    .setCtx(host.ctx(tenant, tripId, ""))
                    .setCity(t.getCity())
                    .setKind(t.getKind())
                    .setFromLocation(t.getFromLocation())
                    .setToLocation(t.getToLocation())
                    .setPickup(window)
                    .setPassengers(Math.max(1, intent.getTravelers()))
                    .build());
        found.getOffersList().forEach(o -> out.add(o.toBuilder().setComponentId(c.id()).build()));
      }
      default -> {}
    }
    return out;
  }

  /** The window a transfer's pickup may fall in, wide enough for any flight in the leg's window. */
  static @Nullable TimeWindow pickupWindow(Transfer t, Map<String, Leg> legs) {
    if ("POINT_TO_POINT".equals(t.getKind())) {
      if (!t.hasPickup()) {
        return null;
      }
      Instant p = instant(t.getPickup());
      return window(p.minus(Duration.ofHours(1)), p.plus(Duration.ofHours(1)));
    }
    Leg leg = t.getDependsOnCount() == 0 ? null : legs.get(t.getDependsOn(0));
    if (leg == null) {
      return null;
    }
    Instant earliest = instant(leg.getEarliestDeparture());
    Instant deadline = instant(leg.getArrivalDeadline());
    if ("AIRPORT_TO_HOTEL".equals(t.getKind())) {
      return window(earliest, deadline.plus(TRANSFER_REACH));
    }
    return window(earliest.minus(TRANSFER_EARLIEST_BEFORE), deadline);
  }

  private PolicyDecision judge(
      String tenant, String tripId, Trip trip, TravelIntent intent, Bundle bundle) {
    EvaluateTripResponse verdict =
        activities.evaluatePolicy(
            EvaluateTripRequest.newBuilder()
                .setCtx(host.ctx(tenant, tripId, ""))
                .setTripId(tripId)
                .setTravelerId(trip.getTravelerId())
                .setIntent(intent)
                .setScope(Governed.scopeOf(trip))
                .addCandidates(bundle)
                .build());
    return verdict.getCandidatesCount() == 0
        ? PolicyDecision.newBuilder().setOutcome(Outcome.DENY).build()
        : verdict.getCandidates(0).getDecision();
  }

  /** The requester withdrew the trip while it waited; Travel Core already recorded CANCELLED. */
  private TripWorkflow.Outcome withdrawn(String tripId) {
    host.stage(TripPlanning.Stage.CANCELLED);
    return new TripWorkflow.Outcome(tripId, "CANCELLED", null, null, null);
  }

  private TripWorkflow.Outcome rejected(String tenant, String tripId) {
    String by = host.decision() == null ? "a manager" : host.decision().decidedBy();
    host.transition(tenant, tripId, TripStatus.CANCELLED, b -> b.setReason("rejected by " + by));
    host.stage(TripPlanning.Stage.CANCELLED);
    return new TripWorkflow.Outcome(tripId, "CANCELLED", null, null, null);
  }

  private void report(
      String tenant,
      String tripId,
      List<Component> components,
      java.util.function.Function<Component, ComponentState> state) {
    UpdateComponentsRequest.Builder b =
        UpdateComponentsRequest.newBuilder().setCtx(host.ctx(tenant, tripId, "")).setTripId(tripId);
    components.forEach(c -> b.addComponents(state.apply(c)));
    activities.updateComponents(b.build());
  }

  // ------------------------------------------------------------------ shapes

  static List<Component> components(Itinerary it) {
    List<Component> out = new ArrayList<>();
    for (Leg l : it.getLegsList()) {
      out.add(
          new Component(
              l.getComponentId(),
              OfferType.AIR,
              l.getSequence(),
              true,
              l.getDependsOnList(),
              l,
              null,
              null));
    }
    int seq = it.getLegsCount();
    for (Stay s : it.getStaysList()) {
      out.add(
          new Component(
              s.getComponentId(),
              OfferType.HOTEL,
              ++seq,
              s.getRequired(),
              s.getDependsOnList(),
              null,
              s,
              null));
    }
    for (Transfer t : it.getTransfersList()) {
      out.add(
          new Component(
              t.getComponentId(),
              OfferType.GROUND,
              ++seq,
              t.getRequired(),
              t.getDependsOnList(),
              null,
              null,
              t));
    }
    return out;
  }

  static ComponentCandidates candidates(Component c, List<Offer> offers, Map<String, Leg> legs) {
    ComponentCandidates.Builder b =
        ComponentCandidates.newBuilder()
            .setComponentId(c.id())
            .setType(c.type())
            .setSequence(c.sequence())
            .setRequired(c.required())
            .addAllDependsOn(c.dependsOn())
            .addAllOffers(offers);
    if (c.leg() != null) {
      b.setNotBefore(c.leg().getEarliestDeparture()).setNotAfter(c.leg().getArrivalDeadline());
    }
    if (c.stay() != null) {
      b.setCheckInDate(c.stay().getCheckInDate())
          .setCheckOutDate(c.stay().getCheckOutDate())
          .setTimeZone(c.stay().getTimeZone());
      if (c.stay().getDependsOnCount() > 0) {
        b.setArrivalLegId(c.stay().getDependsOn(0));
      }
      if (c.stay().getDependsOnCount() > 1) {
        b.setDepartureLegId(c.stay().getDependsOn(1));
      }
    }
    if (c.transfer() != null) {
      Transfer t = c.transfer();
      TimeWindow w = pickupWindow(t, legs);
      if (w != null) {
        b.setNotBefore(w.getNotBefore()).setNotAfter(w.getNotAfter());
      }
      if (t.getDependsOnCount() > 0) {
        if ("AIRPORT_TO_HOTEL".equals(t.getKind())) {
          b.setArrivalLegId(t.getDependsOn(0));
        } else if ("HOTEL_TO_AIRPORT".equals(t.getKind())) {
          b.setDepartureLegId(t.getDependsOn(0));
        }
      }
    }
    return b.build();
  }

  static Bundle single(Offer o) {
    return Bundle.newBuilder()
        .setBundleId("bdl_" + o.getOfferId().substring(Math.min(4, o.getOfferId().length())))
        .addOffers(o)
        .setTotal(o.getTotal())
        .build();
  }

  static RankedCandidate rankedFor(Bundle selected, OptimizeItineraryResponse optimized) {
    return RankedCandidate.newBuilder()
        .setBundleId(selected.getBundleId())
        .setScore(optimized.getScore())
        .setBreakdown(optimized.getBreakdown())
        .setFeasible(true)
        .setRank(1)
        .build();
  }

  static Map<String, Offer> byComponent(Bundle b) {
    Map<String, Offer> out = new LinkedHashMap<>();
    b.getOffersList().forEach(o -> out.put(o.getComponentId(), o));
    return out;
  }

  static Map<String, OrderItem> itemsByComponent(Order order) {
    Map<String, OrderItem> out = new LinkedHashMap<>();
    for (OrderItem i : order.getItemsList()) {
      if (!i.getComponentId().isBlank()) {
        out.put(i.getComponentId(), i);
      }
    }
    return out;
  }

  static ComponentState fromOrder(Component c, @Nullable OrderItem item, @Nullable Offer offer) {
    if (item == null) {
      return state(
          c, offer == null ? "SKIPPED" : "FAILED", offer, offer == null ? null : "NOT_BOOKED");
    }
    String status = item.getStatus().name().replace("ITEM_", "");
    ComponentState.Builder b =
        state(
            c,
            status,
            item.getOffer(),
            item.getFailureCode().isBlank() ? null : item.getFailureCode())
            .toBuilder();
    if (!item.getExternalRef().isBlank()) {
      b.setExternalRef(item.getExternalRef());
    }
    if (item.hasTotal()) {
      b.setTotal(item.getTotal());
    }
    return b.build();
  }

  static ComponentState state(
      Component c, String status, @Nullable Offer o, @Nullable String code) {
    ComponentState.Builder b =
        ComponentState.newBuilder()
            .setComponentId(c.id())
            .setType(c.type().name())
            .setStatus(status)
            .setSummary(describe(c));
    if (o != null) {
      b.setOfferId(o.getOfferId()).setProvider(o.getProvider()).setTotal(o.getTotal());
      b.setSummary(summary(c, o));
    }
    if (code != null) {
      b.setFailureCode(code);
    }
    return b.build();
  }

  static String describe(Component c) {
    return switch (c.type()) {
      case AIR -> "leg " + c.leg().getOrigin() + "-" + c.leg().getDestination();
      case HOTEL ->
          "stay in "
              + c.stay().getCity()
              + " "
              + c.stay().getCheckInDate()
              + " to "
              + c.stay().getCheckOutDate();
      case GROUND ->
          c.transfer().getKind().toLowerCase().replace('_', ' ')
              + " transfer in "
              + c.transfer().getCity();
      default -> c.id();
    };
  }

  static String summary(Component c, Offer o) {
    if (o.hasAir() && o.getAir().getOutbound().getSegmentsCount() > 0) {
      FlightSegment s = o.getAir().getOutbound().getSegments(0);
      FlightSegment last =
          o.getAir().getOutbound().getSegments(o.getAir().getOutbound().getSegmentsCount() - 1);
      return s.getFlightNumber()
          + " "
          + s.getOrigin()
          + "-"
          + last.getDestination()
          + " "
          + instant(s.getDeparture());
    }
    if (o.hasHotel()) {
      return o.getHotel().getName()
          + " "
          + o.getHotel().getCity()
          + " "
          + o.getHotel().getCheckInDate()
          + " to "
          + o.getHotel().getCheckOutDate()
          + " ("
          + o.getHotel().getNights()
          + " nights)";
    }
    if (o.hasGround()) {
      return o.getGround().getVendorName()
          + " "
          + o.getGround().getPickupLocation()
          + " to "
          + o.getGround().getDropoffLocation()
          + " "
          + instant(o.getGround().getPickup());
    }
    return describe(c);
  }

  /**
   * The offer as re-quoted: the supplier's current price, expiry and reference on top of what we
   * selected. A quote that carries no detail keeps the selected offer's flights/room/transfer.
   */
  static Offer requoted(Offer selected, Offer quote) {
    if (quote.getDetailCase() == Offer.DetailCase.DETAIL_NOT_SET) {
      Offer.Builder b = selected.toBuilder();
      if (quote.hasTotal()) {
        b.setTotal(quote.getTotal());
      }
      if (quote.hasExpiresAt()) {
        b.setExpiresAt(quote.getExpiresAt());
      }
      if (!quote.getProviderOfferId().isBlank()) {
        b.setProviderOfferId(quote.getProviderOfferId());
      }
      return b.build();
    }
    return quote.toBuilder()
        .setComponentId(selected.getComponentId())
        .setOfferId(selected.getOfferId())
        .build();
  }

  static Component componentOf(List<Component> components, String id) {
    return components.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
  }

  static boolean requotedOnly(List<String> changed, Bundle plan, Bundle previous) {
    Map<String, Offer> before = byComponent(previous);
    for (Offer o : plan.getOffersList()) {
      Offer b = before.get(o.getComponentId());
      if (b != null && b.getTotal().getAmountMinor() != o.getTotal().getAmountMinor()) {
        return false;
      }
    }
    return !changed.isEmpty();
  }

  static String reasonSummary(PolicyDecision decision) {
    if (decision.getReasonsCount() == 0) {
      return decision.getRequiresApproval() ? "policy requires approval" : "in policy";
    }
    return decision.getReasons(0).getMessage().isBlank()
        ? decision.getReasons(0).getCode()
        : decision.getReasons(0).getMessage();
  }

  static boolean isFinal(ActivityFailure e) {
    return e.getCause() instanceof ApplicationFailure af && af.isNonRetryable();
  }

  static String money(Money m) {
    return m.getCurrency()
        + " "
        + (m.getAmountMinor() / 100)
        + "."
        + String.format("%02d", m.getAmountMinor() % 100);
  }

  static Instant instant(Timestamp ts) {
    return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
  }

  static TimeWindow window(Instant from, Instant to) {
    return TimeWindow.newBuilder()
        .setNotBefore(Timestamp.newBuilder().setSeconds(from.getEpochSecond()))
        .setNotAfter(Timestamp.newBuilder().setSeconds(to.getEpochSecond()))
        .build();
  }
}
