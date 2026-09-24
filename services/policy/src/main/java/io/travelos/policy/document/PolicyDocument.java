package io.travelos.policy.document;

import io.travelos.policy.engine.Cabin;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A travel policy as an administrator publishes it: structured rules, not code. All amounts are in
 * the policy's currency, in minor units (ADR-0007). A document is immutable once published; a
 * change is a new version.
 *
 * <p>Every section is required so that "we forgot to configure hotels" cannot silently mean "no
 * hotel rule". Optional limits inside a section are explicitly nullable.
 */
public record PolicyDocument(
    String policyId,
    String name,
    String currency,
    Flight flight,
    Hotel hotel,
    Approval approval,
    Autonomy autonomy,
    Incentives incentives,
    @Nullable Ground ground,
    @Nullable TripBudget trip) {

  private static final Pattern POLICY_ID = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

  /** Slice 1/2 documents have no ground or trip section: both mean "unconstrained". */
  public PolicyDocument {
    ground = ground == null ? new Ground(null, null) : ground;
    trip = trip == null ? new TripBudget(null, null) : trip;
  }

  public PolicyDocument(
      String policyId,
      String name,
      String currency,
      Flight flight,
      Hotel hotel,
      Approval approval,
      Autonomy autonomy,
      Incentives incentives) {
    this(policyId, name, currency, flight, hotel, approval, autonomy, incentives, null, null);
  }

  /** What happens when a rule is violated. */
  public enum Consequence {
    /** Not bookable through the platform. */
    DENY,
    /** Bookable after a human with the named role approves. */
    REQUIRE_APPROVAL,
    /** Bookable without approval; the traveler pays the excess personally. */
    TRAVELER_PAYS
  }

  /**
   * @param domesticCabins cabins permitted when origin and destination are in the same country
   * @param internationalCabins cabins permitted otherwise (or when an airport is unknown)
   * @param lowestLogicalFare the band above the lowest logical fare that is still in policy
   * @param maxStops maximum stops per journey; null = unconstrained
   * @param maxStopsOnViolation defaults to REQUIRE_APPROVAL
   */
  public record Flight(
      List<Cabin> domesticCabins,
      List<Cabin> internationalCabins,
      FareBand lowestLogicalFare,
      @Nullable Integer maxStops,
      @Nullable Consequence maxStopsOnViolation) {

    public Consequence maxStopsConsequence() {
      return maxStopsOnViolation == null ? Consequence.REQUIRE_APPROVAL : maxStopsOnViolation;
    }
  }

  /**
   * @param maxAmountAbove how far above the lowest logical fare a fare may be and still be in
   *     policy
   * @param onViolation what happens above the band
   */
  public record FareBand(long maxAmountAbove, Consequence onViolation) {}

  /**
   * @param nightlyLimit maximum nightly rate; null = unconstrained
   * @param onViolation defaults to REQUIRE_APPROVAL
   */
  public record Hotel(@Nullable Long nightlyLimit, @Nullable Consequence onViolation) {

    public Consequence consequence() {
      return onViolation == null ? Consequence.REQUIRE_APPROVAL : onViolation;
    }
  }

  /**
   * @param managerRequiredAbove trip total above which a MANAGER must approve; null = never
   */
  public record Approval(@Nullable Long managerRequiredAbove) {}

  /**
   * Slice 3: ground transport.
   *
   * @param perTransferLimit maximum price of one transfer; null = unconstrained
   * @param onViolation defaults to REQUIRE_APPROVAL
   */
  public record Ground(@Nullable Long perTransferLimit, @Nullable Consequence onViolation) {
    public Consequence consequence() {
      return onViolation == null ? Consequence.REQUIRE_APPROVAL : onViolation;
    }
  }

  /**
   * Slice 3: the whole trip, every component included.
   *
   * @param maxTotal budget for the whole itinerary; null = unconstrained
   * @param onViolation defaults to REQUIRE_APPROVAL
   */
  /**
   * @param maxTotal the trip budget; null = unconstrained
   * @param maxAdvanceDays Phase 3: how far ahead travel may be requested; null = unconstrained
   * @param minLeadHours Phase 3: the least notice before departure; null = unconstrained
   * @param maxDurationDays Phase 3: the longest trip (first departure to last return)
   * @param onHorizonViolation what a horizon or length breach means; defaults to REQUIRE_APPROVAL
   */
  public record TripBudget(
      @Nullable Long maxTotal,
      @Nullable Consequence onViolation,
      @Nullable Integer maxAdvanceDays,
      @Nullable Integer minLeadHours,
      @Nullable Integer maxDurationDays,
      @Nullable Consequence onHorizonViolation) {
    public TripBudget(@Nullable Long maxTotal, @Nullable Consequence onViolation) {
      this(maxTotal, onViolation, null, null, null, null);
    }

    public Consequence consequence() {
      return onViolation == null ? Consequence.REQUIRE_APPROVAL : onViolation;
    }

    public Consequence horizonConsequence() {
      return onHorizonViolation == null ? Consequence.REQUIRE_APPROVAL : onHorizonViolation;
    }
  }

  /**
   * What autonomous agents may do without a human. Rebooking and cancellation default to closed.
   *
   * @param purchase Phase 3: may the platform purchase a planned, in-policy trip without a person's
   *     confirmation? Absent in documents from before Phase 3, which means the behaviour those
   *     tenants already had: yes, without an amount limit (submitting booked). New documents should
   *     say so explicitly, or disable it so every trip is confirmed by a person.
   */
  public record Autonomy(
      Rebooking flightRebooking, Toggle cancellation, @Nullable Purchase purchase) {
    public Autonomy {
      purchase = purchase == null ? new Purchase(true, null) : purchase;
    }

    public Autonomy(Rebooking flightRebooking, Toggle cancellation) {
      this(flightRebooking, cancellation, null);
    }
  }

  /**
   * @param enabled whether policy alone may authorize a purchase (a person still approves when a
   *     rule requires approval)
   * @param maxTotal the most a plan may cost to be purchased on policy's authority alone; null = no
   *     amount limit
   */
  public record Purchase(boolean enabled, @Nullable Long maxTotal) {}

  /**
   * @param enabled may an agent change a flight on its own
   * @param maxIncrementalCost the most an autonomous change may add to the trip cost
   */
  public record Rebooking(boolean enabled, long maxIncrementalCost) {}

  public record Toggle(boolean enabled) {}

  /**
   * @param enabled whether in-policy choices earn rewards
   * @param shareOfSavings fraction of (ceiling - fare) paid to the traveler, 0..1
   * @param maxReward cap per booking
   */
  public record Incentives(boolean enabled, double shareOfSavings, long maxReward) {}

  /** Semantic validation beyond what JSON parsing checks. Empty list means valid. */
  public List<String> validate() {
    List<String> problems = new ArrayList<>();
    if (policyId == null || !POLICY_ID.matcher(policyId).matches()) {
      problems.add("policyId must match [A-Z][A-Z0-9_]{0,63}");
    }
    if (name == null || name.isBlank() || name.length() > 200) {
      problems.add("name is required (max 200 chars)");
    }
    if (currency == null) {
      problems.add("currency is required");
    } else {
      try {
        Currency.getInstance(currency);
      } catch (IllegalArgumentException e) {
        problems.add("currency must be an ISO 4217 code");
      }
    }
    if (flight == null) {
      problems.add("flight section is required");
    } else {
      if (flight.domesticCabins() == null || flight.domesticCabins().isEmpty()) {
        problems.add("flight.domesticCabins must list at least one cabin");
      }
      if (flight.internationalCabins() == null || flight.internationalCabins().isEmpty()) {
        problems.add("flight.internationalCabins must list at least one cabin");
      }
      if (flight.lowestLogicalFare() == null) {
        problems.add("flight.lowestLogicalFare is required");
      } else {
        if (flight.lowestLogicalFare().maxAmountAbove() < 0) {
          problems.add("flight.lowestLogicalFare.maxAmountAbove must be >= 0");
        }
        if (flight.lowestLogicalFare().onViolation() == null) {
          problems.add("flight.lowestLogicalFare.onViolation is required");
        }
      }
      if (flight.maxStops() != null && flight.maxStops() < 0) {
        problems.add("flight.maxStops must be >= 0");
      }
    }
    if (hotel == null) {
      problems.add("hotel section is required");
    } else if (hotel.nightlyLimit() != null && hotel.nightlyLimit() < 0) {
      problems.add("hotel.nightlyLimit must be >= 0");
    }
    if (ground.perTransferLimit() != null && ground.perTransferLimit() < 0) {
      problems.add("ground.perTransferLimit must be >= 0");
    }
    if (trip.maxTotal() != null && trip.maxTotal() < 0) {
      problems.add("trip.maxTotal must be >= 0");
    }
    if (trip.maxAdvanceDays() != null && trip.maxAdvanceDays() < 0) {
      problems.add("trip.maxAdvanceDays must be >= 0");
    }
    if (trip.minLeadHours() != null && trip.minLeadHours() < 0) {
      problems.add("trip.minLeadHours must be >= 0");
    }
    if (trip.maxDurationDays() != null && trip.maxDurationDays() < 1) {
      problems.add("trip.maxDurationDays must be >= 1");
    }
    if (autonomy != null
        && autonomy.purchase().maxTotal() != null
        && autonomy.purchase().maxTotal() < 0) {
      problems.add("autonomy.purchase.maxTotal must be >= 0");
    }
    if (approval == null) {
      problems.add("approval section is required");
    } else if (approval.managerRequiredAbove() != null && approval.managerRequiredAbove() < 0) {
      problems.add("approval.managerRequiredAbove must be >= 0");
    }
    if (autonomy == null) {
      problems.add("autonomy section is required");
    } else {
      if (autonomy.flightRebooking() == null) {
        problems.add("autonomy.flightRebooking is required");
      } else if (autonomy.flightRebooking().maxIncrementalCost() < 0) {
        problems.add("autonomy.flightRebooking.maxIncrementalCost must be >= 0");
      }
      if (autonomy.cancellation() == null) {
        problems.add("autonomy.cancellation is required");
      }
    }
    if (incentives == null) {
      problems.add("incentives section is required");
    } else {
      if (incentives.shareOfSavings() < 0 || incentives.shareOfSavings() > 1) {
        problems.add("incentives.shareOfSavings must be between 0 and 1");
      }
      if (incentives.maxReward() < 0) {
        problems.add("incentives.maxReward must be >= 0");
      }
    }
    return problems;
  }
}
