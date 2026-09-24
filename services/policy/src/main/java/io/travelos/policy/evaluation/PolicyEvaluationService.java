package io.travelos.policy.evaluation;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Status;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.money.Money;
import io.travelos.contracts.offer.v1.Bundle;
import io.travelos.contracts.policy.v1.CandidateDecision;
import io.travelos.contracts.policy.v1.EvaluateActionRequest;
import io.travelos.contracts.policy.v1.EvaluateTripRequest;
import io.travelos.contracts.policy.v1.EvaluateTripResponse;
import io.travelos.contracts.policy.v1.PolicyDecision;
import io.travelos.contracts.trip.v1.TravelIntent;
import io.travelos.policy.engine.Decision;
import io.travelos.policy.engine.Facts;
import io.travelos.policy.engine.PolicyEngine;
import io.travelos.policy.engine.RouteClassifier;
import io.travelos.policy.events.PolicyEvents;
import io.travelos.policy.store.DecisionRepository;
import io.travelos.policy.store.PolicyRepository;
import io.travelos.policy.store.PolicyVersion;
import io.travelos.spring.grpc.RequestContexts;
import io.travelos.spring.outbox.Outbox;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the tenant's policy, runs the engine, and turns every verdict into evidence: a row in
 * policy_decision and a travel.policy.* event, in the same transaction as the response is built.
 */
@Service
public class PolicyEvaluationService {

  static final String NO_POLICY_RULE = "POLICY_ASSIGNMENT";
  static final String DEFAULT_CURRENCY = "USD";

  private final PolicyRepository policies;
  private final DecisionRepository decisions;
  private final PolicyEngine engine;
  private final RouteClassifier routes;
  private final Outbox outbox;
  private final Clock clock;

  public PolicyEvaluationService(
      PolicyRepository policies,
      DecisionRepository decisions,
      PolicyEngine engine,
      RouteClassifier routes,
      Outbox outbox,
      Clock clock) {
    this.policies = policies;
    this.decisions = decisions;
    this.engine = engine;
    this.routes = routes;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional
  public EvaluateTripResponse evaluateTrip(EvaluateTripRequest request) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    if (request.getTripId().isBlank() || request.getTravelerId().isBlank()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("trip_id and traveler_id are required")
          .asRuntimeException();
    }
    Instant now = clock.instant();
    String evaluationId = Ids.newId(IdPrefix.DECISION);
    Optional<PolicyVersion> policy = policies.defaultPolicy(ctx.tenant());
    TravelIntent intent = request.getIntent();

    EvaluateTripResponse.Builder response =
        EvaluateTripResponse.newBuilder().setEvaluationId(evaluationId);
    List<Bundle> bundles = request.getCandidatesList();
    if (policy.isEmpty()) {
      for (Bundle bundle : bundles) {
        PolicyDecision proto =
            record(
                evaluationId,
                ctx,
                request.getTripId(),
                request.getTravelerId(),
                bundle.getBundleId(),
                null,
                "",
                0,
                noPolicy(ctx.tenant().value()),
                now);
        response.addCandidates(
            CandidateDecision.newBuilder().setBundleId(bundle.getBundleId()).setDecision(proto));
      }
      return response.build();
    }
    PolicyVersion pv = policy.get();
    // One benchmark for the whole search: the lowest logical fare is only meaningful across the
    // candidates that compete with each other.
    List<String[]> legs = new ArrayList<>();
    for (Bundle bundle : bundles) {
      legs.addAll(ProtoMapping.legs(bundle));
    }
    Instant referenceTime =
        request.hasReferenceTime() ? ProtoMapping.instant(request.getReferenceTime()) : now;
    Facts.Trip trip =
        tripFacts(request.getTripId(), request.getTravelerId(), intent, legs, referenceTime);
    List<Facts.Candidate> candidates =
        bundles.stream().map(b -> ProtoMapping.candidate(b, pv.document().currency())).toList();
    List<Decision.CandidateEvaluation> evaluations =
        engine.evaluateTrip(pv.document(), trip, candidates);
    for (int i = 0; i < bundles.size(); i++) {
      Bundle bundle = bundles.get(i);
      PolicyDecision proto =
          record(
              evaluationId,
              ctx,
              request.getTripId(),
              request.getTravelerId(),
              bundle.getBundleId(),
              null,
              pv.policyId(),
              pv.version(),
              evaluations.get(i).decision(),
              now);
      response.addCandidates(
          CandidateDecision.newBuilder().setBundleId(bundle.getBundleId()).setDecision(proto));
    }
    return response.build();
  }

  @Transactional
  public PolicyDecision evaluateAction(EvaluateActionRequest request) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    if (request.getTripId().isBlank()
        || request.getTravelerId().isBlank()
        || request.getAction().isBlank()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("trip_id, traveler_id and action are required")
          .asRuntimeException();
    }
    Instant now = clock.instant();
    String evaluationId = Ids.newId(IdPrefix.DECISION);
    Optional<PolicyVersion> policy = policies.defaultPolicy(ctx.tenant());
    Decision decision;
    String policyId = "";
    int version = 0;
    if (policy.isEmpty()) {
      decision = noPolicy(ctx.tenant().value());
    } else {
      PolicyVersion pv = policy.get();
      policyId = pv.policyId();
      version = pv.version();
      Facts.Candidate proposed =
          request.hasProposed()
              ? ProtoMapping.candidate(request.getProposed(), pv.document().currency())
              : null;
      Facts.Trip trip =
          tripFacts(
              request.getTripId(),
              request.getTravelerId(),
              null,
              request.hasProposed() ? ProtoMapping.legs(request.getProposed()) : List.of());
      Money incremental =
          request.hasIncrementalCost() ? ProtoMapping.money(request.getIncrementalCost()) : null;
      Facts.Constraints constraints =
          request.hasIntent() ? ProtoMapping.constraints(request.getIntent()) : null;
      Facts.Itinerary itinerary =
          request.hasProposed() ? ProtoMapping.itinerary(request.getProposed()) : null;
      decision =
          engine.evaluateAction(
              pv.document(),
              trip,
              new Facts.Action(
                  request.getAction(),
                  incremental,
                  proposed,
                  constraints,
                  itinerary,
                  request.hasProposed()
                      ? ProtoMapping.legTimings(request.getProposed())
                      : List.of()),
              ctx.principal());
    }
    String bundleId = request.hasProposed() ? request.getProposed().getBundleId() : null;
    return record(
        evaluationId,
        ctx,
        request.getTripId(),
        request.getTravelerId(),
        bundleId,
        Facts.Action.canonical(request.getAction()),
        policyId,
        version,
        decision,
        now);
  }

  private Facts.Trip tripFacts(
      String tripId, String travelerId, @Nullable TravelIntent intent, List<String[]> legs) {
    return tripFacts(tripId, travelerId, intent, legs, null);
  }

  private Facts.Trip tripFacts(
      String tripId,
      String travelerId,
      @Nullable TravelIntent intent,
      List<String[]> legs,
      @Nullable Instant referenceTime) {
    RouteClassifier.Classification route;
    String origin = intent == null ? "" : intent.getOrigin();
    String destination = intent == null ? "" : intent.getDestination();
    if (!legs.isEmpty()) {
      route = routes.classify(legs);
      if (origin.isBlank()) {
        origin = legs.getFirst()[0];
        destination = legs.getLast()[1];
      }
    } else if (!origin.isBlank() && !destination.isBlank()) {
      route = routes.classify(origin, destination);
    } else {
      route =
          new RouteClassifier.Classification(
              true, "no route information; treated as international");
    }
    Instant earliest = null;
    Instant latest = null;
    if (intent != null) {
      if (intent.hasItinerary() && intent.getItinerary().getLegsCount() > 0) {
        var legsList = intent.getItinerary().getLegsList();
        earliest = ProtoMapping.instant(legsList.getFirst().getEarliestDeparture());
        latest = ProtoMapping.instant(legsList.getLast().getArrivalDeadline());
      } else {
        earliest = ProtoMapping.instant(intent.getEarliestDeparture());
        latest =
            intent.hasLatestReturn()
                ? ProtoMapping.instant(intent.getLatestReturn())
                : ProtoMapping.instant(intent.getArrivalDeadline());
      }
    }
    return ProtoMapping.trip(
        tripId, travelerId, origin, destination, route, referenceTime, earliest, latest);
  }

  private static Decision noPolicy(String tenant) {
    return Decision.deny(
        NO_POLICY_RULE,
        "NO_POLICY",
        "tenant "
            + tenant
            + " has no default travel policy; nothing can be booked until one is published",
        DEFAULT_CURRENCY);
  }

  /** Persist + publish, then hand the proto back. One row and one event per decision, always. */
  private PolicyDecision record(
      String evaluationId,
      RequestContexts.Validated ctx,
      String tripId,
      String travelerId,
      @Nullable String bundleId,
      @Nullable String action,
      String policyId,
      int policyVersion,
      Decision decision,
      Instant now) {
    String decisionId = Ids.newId(IdPrefix.POLICY_DECISION);
    PolicyDecision proto =
        ProtoMapping.decision(decisionId, policyId, policyVersion, decision, now);
    decisions.insert(
        new DecisionRepository.Record(
            decisionId,
            ctx.tenant(),
            evaluationId,
            tripId,
            travelerId,
            bundleId,
            action,
            policyId,
            policyVersion,
            decision.outcome().name(),
            decision.requiresApproval(),
            ctx.principal(),
            toJson(proto),
            now));
    outbox.append(
        PolicyEvents.evaluated(
            ctx,
            tripId,
            travelerId,
            bundleId,
            action,
            policyId,
            policyVersion,
            decisionId,
            decision,
            clock));
    if (decision.outcome() == Decision.Outcome.DENY) {
      outbox.append(
          PolicyEvents.violation(
              ctx, tripId, policyId, policyVersion, decisionId, decision, clock));
    }
    return proto;
  }

  static String toJson(PolicyDecision decision) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(decision);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException("cannot serialize a PolicyDecision", e);
    }
  }
}
