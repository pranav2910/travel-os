package io.travelos.policy.evaluation;

import com.google.protobuf.Timestamp;
import io.travelos.common.money.Money;
import io.travelos.contracts.offer.v1.Bundle;
import io.travelos.contracts.offer.v1.FlightSegment;
import io.travelos.contracts.offer.v1.Journey;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.offer.v1.OfferType;
import io.travelos.contracts.policy.v1.ApproverRequirement;
import io.travelos.contracts.policy.v1.Economics;
import io.travelos.contracts.policy.v1.Outcome;
import io.travelos.contracts.policy.v1.PolicyDecision;
import io.travelos.contracts.policy.v1.ReasonCode;
import io.travelos.policy.engine.Cabin;
import io.travelos.policy.engine.Decision;
import io.travelos.policy.engine.Facts;
import io.travelos.policy.engine.RouteClassifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The only place protobuf and the engine's vocabulary meet. */
final class ProtoMapping {

  private ProtoMapping() {}

  static Money money(io.travelos.contracts.common.v1.Money money) {
    return Money.of(money.getCurrency(), money.getAmountMinor());
  }

  static io.travelos.contracts.common.v1.Money money(Money money) {
    return io.travelos.contracts.common.v1.Money.newBuilder()
        .setCurrency(money.currency())
        .setAmountMinor(money.amountMinor())
        .build();
  }

  static @Nullable Money moneyOrNull(io.travelos.contracts.common.v1.Money money, boolean present) {
    return present ? money(money) : null;
  }

  static Cabin cabin(io.travelos.contracts.common.v1.Cabin cabin) {
    return switch (cabin) {
      case PREMIUM_ECONOMY -> Cabin.PREMIUM_ECONOMY;
      case BUSINESS -> Cabin.BUSINESS;
      case FIRST -> Cabin.FIRST;
      case ECONOMY, CABIN_UNSPECIFIED, UNRECOGNIZED -> Cabin.ECONOMY;
    };
  }

  /** Every leg of every air offer in the bundle, for route classification. */
  static List<String[]> legs(Bundle bundle) {
    List<String[]> legs = new ArrayList<>();
    for (Offer offer : bundle.getOffersList()) {
      if (!offer.hasAir()) {
        continue;
      }
      for (Journey journey : List.of(offer.getAir().getOutbound(), offer.getAir().getInbound())) {
        for (FlightSegment segment : journey.getSegmentsList()) {
          legs.add(new String[] {segment.getOrigin(), segment.getDestination()});
        }
      }
    }
    return legs;
  }

  static Facts.Candidate candidate(Bundle bundle, String currencyFallback) {
    Money airFare = null;
    Cabin highest = Cabin.ECONOMY;
    int maxStops = 0;
    Facts.Hotel hotel = null;
    for (Offer offer : bundle.getOffersList()) {
      if (offer.getType() == OfferType.AIR && offer.hasAir()) {
        Money fare = money(offer.getTotal());
        airFare = airFare == null ? fare : airFare.plus(fare);
        for (Journey journey : List.of(offer.getAir().getOutbound(), offer.getAir().getInbound())) {
          if (journey.getSegmentsCount() > 0) {
            maxStops = Math.max(maxStops, journey.getSegmentsCount() - 1);
          }
          for (FlightSegment segment : journey.getSegmentsList()) {
            Cabin cabin = cabin(segment.getCabin());
            if (cabin.isAbove(highest)) {
              highest = cabin;
            }
          }
        }
      } else if (offer.getType() == OfferType.HOTEL && offer.hasHotel() && hotel == null) {
        hotel =
            new Facts.Hotel(money(offer.getHotel().getNightlyRate()), offer.getHotel().getNights());
      }
    }
    Money total =
        bundle.hasTotal()
            ? money(bundle.getTotal())
            : bundle.getOffersList().stream()
                .map(o -> money(o.getTotal()))
                .reduce(Money::plus)
                .orElse(Money.zero(currencyFallback));
    Facts.Air air = airFare == null ? null : new Facts.Air(airFare, highest, maxStops);
    return new Facts.Candidate(bundle.getBundleId(), total, air, hotel);
  }

  static Facts.Trip trip(
      String tripId,
      String travelerId,
      String origin,
      String destination,
      RouteClassifier.Classification route) {
    return new Facts.Trip(
        tripId, travelerId, origin, destination, route.international(), route.note());
  }

  static PolicyDecision decision(
      String decisionId, String policyId, int policyVersion, Decision decision, Instant at) {
    PolicyDecision.Builder builder =
        PolicyDecision.newBuilder()
            .setDecisionId(decisionId)
            .setPolicyId(policyId)
            .setPolicyVersion(policyVersion)
            .setOutcome(outcome(decision.outcome()))
            .addAllRulesEvaluated(decision.rulesEvaluated())
            .setRequiresApproval(decision.requiresApproval())
            .setEvaluatedAt(
                Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()))
            .setEconomics(
                Economics.newBuilder()
                    .setReferenceFare(money(decision.economics().referenceFare()))
                    .setInPolicyCeiling(money(decision.economics().inPolicyCeiling()))
                    .setTravelerIncentive(money(decision.economics().travelerIncentive()))
                    .setTravelerPays(money(decision.economics().travelerPays())));
    for (Decision.Violation v : decision.violations()) {
      builder.addReasons(
          ReasonCode.newBuilder().setCode(v.code()).setRuleId(v.ruleId()).setMessage(v.message()));
    }
    for (String role : decision.approverRoles()) {
      String reason =
          decision.violations().stream()
              .filter(v -> role.equals(v.approverRole()))
              .map(Decision.Violation::code)
              .findFirst()
              .orElse("");
      builder.addApprovers(ApproverRequirement.newBuilder().setRole(role).setReasonCode(reason));
    }
    return builder.build();
  }

  static Outcome outcome(Decision.Outcome outcome) {
    return switch (outcome) {
      case ALLOW -> Outcome.ALLOW;
      case ALLOW_WITH_APPROVAL -> Outcome.ALLOW_WITH_APPROVAL;
      case DENY -> Outcome.DENY;
      case ALLOW_WITH_TRAVELER_PAYMENT -> Outcome.ALLOW_WITH_TRAVELER_PAYMENT;
    };
  }
}
