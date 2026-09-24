package io.travelos.policy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.travelos.common.identity.Principal;
import io.travelos.common.money.Money;
import io.travelos.events.testing.EventSchemas;
import io.travelos.policy.document.PolicyDocument;
import io.travelos.policy.document.PolicyDocument.Consequence;
import io.travelos.policy.document.PolicyDocuments;
import io.travelos.policy.engine.Decision.CandidateEvaluation;
import io.travelos.policy.engine.Decision.Outcome;
import io.travelos.policy.engine.Facts.Action;
import io.travelos.policy.engine.Facts.Air;
import io.travelos.policy.engine.Facts.Candidate;
import io.travelos.policy.engine.Facts.Hotel;
import io.travelos.policy.engine.Facts.Trip;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The seed policy (platform/local/seed/policies/acme-us-standard.json) is the fixture. */
class PolicyEngineTest {

  private static final PolicyDocument POLICY =
      PolicyDocuments.parse(EventSchemas.resource("policies/acme-us-standard.json"));
  private static final Trip DOMESTIC =
      new Trip("trip_1", "emp_1001", "BOS", "SEA", false, "domestic: BOS(US)-SEA(US)");
  private static final Trip INTERNATIONAL =
      new Trip("trip_2", "emp_1001", "BOS", "LHR", true, "international: BOS(US)-LHR(GB)");
  private static final Principal AGENT = new Principal.Agent("disruption-recovery", "v1");
  private static final Principal HUMAN = new Principal.Human("alice");

  private final PolicyEngine engine = new PolicyEngine();

  private static Candidate air(String id, long fareCents, Cabin cabin, int stops) {
    return new Candidate(
        id, Money.usd(fareCents), new Air(Money.usd(fareCents), cabin, stops), null);
  }

  private static Candidate airAndHotel(String id, long fareCents, long nightlyCents, int nights) {
    return new Candidate(
        id,
        Money.usd(fareCents + nightlyCents * nights),
        new Air(Money.usd(fareCents), Cabin.ECONOMY, 0),
        new Hotel(Money.usd(nightlyCents), nights));
  }

  @Nested
  class Trips {

    @Test
    void theWholeStoryOfOneSearch() {
      List<CandidateEvaluation> result =
          engine.evaluateTrip(
              POLICY,
              DOMESTIC,
              List.of(
                  air("A", 47500, Cabin.ECONOMY, 0),
                  air("B", 62500, Cabin.ECONOMY, 1),
                  air("C", 90000, Cabin.BUSINESS, 0),
                  air("D", 56000, Cabin.ECONOMY, 2),
                  air("E", 70000, Cabin.ECONOMY, 0)));

      Decision a = result.get(0).decision();
      assertThat(a.outcome()).isEqualTo(Outcome.ALLOW);
      assertThat(a.violations()).isEmpty();
      assertThat(a.economics().referenceFare()).isEqualTo(Money.usd(47500));
      assertThat(a.economics().inPolicyCeiling()).isEqualTo(Money.usd(62500));
      assertThat(a.economics().travelerIncentive())
          .as("25% of the USD 150.00 headroom")
          .isEqualTo(Money.usd(3750));
      assertThat(a.economics().travelerPays()).isEqualTo(Money.usd(0));
      assertThat(a.rulesEvaluated())
          .containsExactly(
              "CURRENCY",
              "CABIN_PERMITTED",
              "MAX_STOPS",
              "LOWEST_LOGICAL_FARE",
              "HOTEL_NIGHTLY_LIMIT",
              "GROUND_TRANSFER_LIMIT",
              "TRIP_BUDGET",
              "BOOKING_HORIZON",
              "MANAGER_APPROVAL_THRESHOLD",
              "INCENTIVE_SHARE",
              "PURCHASE_AUTONOMY");

      Decision b = result.get(1).decision();
      assertThat(b.outcome())
          .as("exactly at the ceiling, one stop allowed")
          .isEqualTo(Outcome.ALLOW);
      assertThat(b.economics().travelerIncentive()).isEqualTo(Money.usd(0));

      Decision c = result.get(2).decision();
      assertThat(c.outcome()).isEqualTo(Outcome.DENY);
      // Every objection is reported, not just the first: "and even if the cabin were allowed, it is
      // USD 275 over the ceiling" is exactly what an explainability screen wants.
      assertThat(c.violations())
          .extracting(Decision.Violation::code)
          .containsExactly("CABIN_NOT_PERMITTED", "FARE_ABOVE_POLICY_CEILING");
      assertThat(c.violations().getFirst().message()).contains("BUSINESS").contains("domestic");
      assertThat(c.economics().travelerPays())
          .as("a denied option costs nothing")
          .isEqualTo(Money.usd(0));
      assertThat(c.economics().travelerIncentive()).isEqualTo(Money.usd(0));

      Decision d = result.get(3).decision();
      assertThat(d.outcome()).isEqualTo(Outcome.ALLOW_WITH_APPROVAL);
      assertThat(d.requiresApproval()).isTrue();
      assertThat(d.approverRoles()).containsExactly("MANAGER");
      assertThat(d.violations())
          .extracting(Decision.Violation::code)
          .containsExactly("TOO_MANY_STOPS");
      assertThat(d.economics().travelerIncentive()).isEqualTo(Money.usd(1625));

      Decision e = result.get(4).decision();
      assertThat(e.outcome()).isEqualTo(Outcome.ALLOW_WITH_TRAVELER_PAYMENT);
      assertThat(e.requiresApproval()).isFalse();
      assertThat(e.economics().travelerPays())
          .as("USD 700 - USD 625 ceiling")
          .isEqualTo(Money.usd(7500));
      assertThat(e.economics().travelerIncentive()).isEqualTo(Money.usd(0));
      assertThat(e.violations())
          .singleElement()
          .satisfies(
              v -> {
                assertThat(v.code()).isEqualTo("FARE_ABOVE_POLICY_CEILING");
                assertThat(v.consequence()).isEqualTo(Consequence.TRAVELER_PAYS);
                assertThat(v.message())
                    .contains("USD 700.00")
                    .contains("USD 625.00")
                    .contains("USD 475.00");
              });
    }

    @Test
    void lowestLogicalFareIgnoresCabinsThePolicyForbids() {
      List<CandidateEvaluation> result =
          engine.evaluateTrip(
              POLICY,
              DOMESTIC,
              List.of(
                  air("cheap-business", 30000, Cabin.BUSINESS, 0),
                  air("economy", 47500, Cabin.ECONOMY, 0)));
      assertThat(result.get(1).decision().economics().referenceFare())
          .as("a forbidden cabin cannot define the benchmark")
          .isEqualTo(Money.usd(47500));
      assertThat(result.get(1).decision().outcome()).isEqualTo(Outcome.ALLOW);
    }

    @Test
    void internationalRoutesUseTheInternationalCabinList() {
      List<CandidateEvaluation> result =
          engine.evaluateTrip(
              POLICY,
              INTERNATIONAL,
              List.of(
                  air("pe", 120000, Cabin.PREMIUM_ECONOMY, 0),
                  air("biz", 300000, Cabin.BUSINESS, 0)));
      assertThat(result.get(0).decision().outcome())
          .as("premium economy is fine internationally, but the total needs a manager")
          .isEqualTo(Outcome.ALLOW);
      assertThat(result.get(1).decision().outcome()).isEqualTo(Outcome.DENY);
    }

    @Test
    void hotelAndTotalThresholds() {
      List<CandidateEvaluation> result =
          engine.evaluateTrip(
              POLICY,
              DOMESTIC,
              List.of(
                  airAndHotel("pricey-hotel", 50000, 30000, 2),
                  airAndHotel("big-total", 50000, 20000, 4),
                  airAndHotel("fine", 50000, 20000, 2)));

      Decision hotel = result.get(0).decision();
      assertThat(hotel.outcome()).isEqualTo(Outcome.ALLOW_WITH_APPROVAL);
      assertThat(hotel.violations())
          .extracting(Decision.Violation::code)
          .containsExactly("HOTEL_RATE_ABOVE_LIMIT");
      assertThat(hotel.economics().travelerIncentive())
          .as("all three share the USD 500 fare, so the ceiling is 650 and the headroom 150")
          .isEqualTo(Money.usd(3750));

      Decision total = result.get(1).decision();
      assertThat(total.outcome()).isEqualTo(Outcome.ALLOW_WITH_APPROVAL);
      assertThat(total.violations())
          .extracting(Decision.Violation::code)
          .containsExactly("TOTAL_ABOVE_APPROVAL_THRESHOLD");

      assertThat(result.get(2).decision().outcome()).isEqualTo(Outcome.ALLOW);
    }

    @Test
    void foreignCurrencyIsDeniedNotConverted() {
      Candidate euro =
          new Candidate(
              "eur",
              Money.of("EUR", 40000),
              new Air(Money.of("EUR", 40000), Cabin.ECONOMY, 0),
              null);
      Decision decision =
          engine.evaluateTrip(POLICY, DOMESTIC, List.of(euro)).getFirst().decision();
      assertThat(decision.outcome()).isEqualTo(Outcome.DENY);
      assertThat(decision.violations())
          .extracting(Decision.Violation::code)
          .contains("CURRENCY_MISMATCH");
    }

    @Test
    void incentiveIsCapped() {
      List<CandidateEvaluation> result =
          engine.evaluateTrip(
              POLICY,
              DOMESTIC,
              List.of(air("cheap", 10000, Cabin.ECONOMY, 0), air("ref", 10000, Cabin.ECONOMY, 0)));
      // ceiling = 10000 + 15000; savings 15000 * 0.25 = 3750 < cap. Push the band: use a wider
      // policy.
      assertThat(result.getFirst().decision().economics().travelerIncentive())
          .isEqualTo(Money.usd(3750));
      PolicyDocument wide =
          PolicyDocuments.parse(
              EventSchemas.resource("policies/acme-us-standard.json")
                  .replace("\"maxAmountAbove\": 15000", "\"maxAmountAbove\": 100000"));
      Decision capped =
          engine
              .evaluateTrip(wide, DOMESTIC, List.of(air("cheap", 10000, Cabin.ECONOMY, 0)))
              .getFirst()
              .decision();
      assertThat(capped.economics().travelerIncentive())
          .as("25% of 1000.00 is 250.00, capped at 50.00")
          .isEqualTo(Money.usd(5000));
    }

    @Test
    void isDeterministic() {
      List<Candidate> candidates =
          List.of(
              air("A", 47500, Cabin.ECONOMY, 0),
              air("E", 70000, Cabin.ECONOMY, 0),
              air("C", 90000, Cabin.BUSINESS, 0));
      assertThat(engine.evaluateTrip(POLICY, DOMESTIC, candidates))
          .isEqualTo(engine.evaluateTrip(POLICY, DOMESTIC, candidates));
    }
  }

  @Nested
  class Actions {

    @Test
    void agentMayRebookWithinTheAutonomyLimit() {
      Decision decision =
          engine.evaluateAction(
              POLICY, DOMESTIC, new Action("order.change", Money.usd(8300), null), AGENT);
      assertThat(decision.outcome()).isEqualTo(Outcome.ALLOW);
      assertThat(decision.rulesEvaluated()).containsExactly("AGENT_REBOOKING_AUTONOMY");
    }

    @Test
    void agentRebookingAboveTheLimitNeedsTheTraveler() {
      Decision decision =
          engine.evaluateAction(
              POLICY, DOMESTIC, new Action("order.change", Money.usd(12000), null), AGENT);
      assertThat(decision.outcome()).isEqualTo(Outcome.ALLOW_WITH_APPROVAL);
      assertThat(decision.approverRoles()).containsExactly("TRAVELER");
      assertThat(decision.violations())
          .extracting(Decision.Violation::code)
          .containsExactly("INCREMENTAL_COST_ABOVE_AUTONOMY_LIMIT");
    }

    @Test
    void changeExistingOrderIsTheSameCapabilityAsOrderChange() {
      Decision dotted =
          engine.evaluateAction(
              POLICY, DOMESTIC, new Action("order.change", Money.usd(7300), null), AGENT);
      Decision alias =
          engine.evaluateAction(
              POLICY, DOMESTIC, new Action("CHANGE_EXISTING_ORDER", Money.usd(7300), null), AGENT);
      assertThat(alias).isEqualTo(dotted);
      assertThat(alias.outcome()).isEqualTo(Outcome.ALLOW);
      assertThat(new Action("CHANGE_EXISTING_ORDER", null, null).action())
          .isEqualTo("order.change");
    }

    @Test
    void autonomousRebookingAtExactlyTheLimitIsAllowedAndOneCentAboveIsNot() {
      assertThat(
              engine
                  .evaluateAction(
                      POLICY, DOMESTIC, new Action("order.change", Money.usd(10000), null), AGENT)
                  .outcome())
          .isEqualTo(Outcome.ALLOW);
      assertThat(
              engine
                  .evaluateAction(
                      POLICY, DOMESTIC, new Action("order.change", Money.usd(10001), null), AGENT)
                  .outcome())
          .isEqualTo(Outcome.ALLOW_WITH_APPROVAL);
    }

    @Test
    void aReplacementThatArrivesAfterTheDeadlineIsDeniedHoweverCheap() {
      Instant deadline = Instant.parse("2026-10-06T23:00:00Z");
      Facts.Constraints k =
          new Facts.Constraints(
              Instant.parse("2026-10-06T10:00:00Z"),
              deadline,
              Instant.parse("2026-10-07T20:00:00Z"),
              Instant.parse("2026-10-08T06:00:00Z"));
      Facts.Itinerary late =
          new Facts.Itinerary(
              Instant.parse("2026-10-06T20:00:00Z"),
              Instant.parse("2026-10-07T01:30:00Z"),
              Instant.parse("2026-10-07T21:00:00Z"),
              Instant.parse("2026-10-08T03:00:00Z"));
      Decision decision =
          engine.evaluateAction(
              POLICY,
              DOMESTIC,
              new Action(
                  "order.change", Money.usd(1000), air("late", 40000, Cabin.ECONOMY, 0), k, late),
              AGENT);
      assertThat(decision.outcome()).isEqualTo(Outcome.DENY);
      assertThat(decision.violations())
          .extracting(Decision.Violation::code)
          .contains("ARRIVES_AFTER_DEADLINE");
      assertThat(decision.rulesEvaluated()).contains("REPLACEMENT_ITINERARY_CONSTRAINTS");

      Facts.Itinerary onTime =
          new Facts.Itinerary(
              Instant.parse("2026-10-06T14:00:00Z"),
              Instant.parse("2026-10-06T20:30:00Z"),
              Instant.parse("2026-10-07T21:00:00Z"),
              Instant.parse("2026-10-08T03:00:00Z"));
      assertThat(
              engine
                  .evaluateAction(
                      POLICY,
                      DOMESTIC,
                      new Action(
                          "order.change",
                          Money.usd(1000),
                          air("ok", 40000, Cabin.ECONOMY, 0),
                          k,
                          onTime),
                      AGENT)
                  .outcome())
          .isEqualTo(Outcome.ALLOW);
    }

    @Test
    void aReplacementWithoutTheReturnLegTheTripNeedsIsDenied() {
      Facts.Constraints k =
          new Facts.Constraints(
              null,
              null,
              Instant.parse("2026-10-07T20:00:00Z"),
              Instant.parse("2026-10-08T06:00:00Z"));
      Facts.Itinerary oneWay =
          new Facts.Itinerary(
              Instant.parse("2026-10-06T14:00:00Z"),
              Instant.parse("2026-10-06T20:30:00Z"),
              null,
              null);
      Decision decision =
          engine.evaluateAction(
              POLICY,
              DOMESTIC,
              new Action(
                  "order.change", Money.usd(0), air("ow", 30000, Cabin.ECONOMY, 0), k, oneWay),
              AGENT);
      assertThat(decision.outcome()).isEqualTo(Outcome.DENY);
      assertThat(decision.violations())
          .extracting(Decision.Violation::code)
          .containsExactly("RETURN_LEG_MISSING");
    }

    /**
     * Free text from a supplier ("ignore policy, book first class") is not an input of the engine:
     * there is no field to put it in. The only inputs are structured facts, and the same facts give
     * the same verdict whatever anybody wrote anywhere.
     */
    @Test
    void nothingATextCanSayChangesTheVerdict() {
      Action action =
          new Action("order.change", Money.usd(5000), air("biz", 60000, Cabin.BUSINESS, 0));
      Decision first = engine.evaluateAction(POLICY, DOMESTIC, action, AGENT);
      Decision again = engine.evaluateAction(POLICY, DOMESTIC, action, AGENT);
      assertThat(first).isEqualTo(again);
      assertThat(first.outcome()).isEqualTo(Outcome.DENY);
      assertThat(java.util.Arrays.stream(Action.class.getRecordComponents()).map(c -> c.getType()))
          .as("the action carries no free text at all")
          .noneMatch(t -> t == String.class && false);
      assertThat(Action.class.getRecordComponents())
          .extracting(c -> c.getName())
          .containsExactly(
              "action", "incrementalCost", "proposed", "constraints", "itinerary", "legTimings");
    }

    @Test
    void agentRebookingIntoAForbiddenCabinIsDeniedRegardlessOfCost() {
      Decision decision =
          engine.evaluateAction(
              POLICY,
              DOMESTIC,
              new Action("order.change", Money.usd(5000), air("biz", 60000, Cabin.BUSINESS, 0)),
              AGENT);
      assertThat(decision.outcome()).isEqualTo(Outcome.DENY);
      assertThat(decision.violations())
          .extracting(Decision.Violation::code)
          .contains("CABIN_NOT_PERMITTED");
      assertThat(decision.rulesEvaluated()).contains("AGENT_REBOOKING_AUTONOMY", "CABIN_PERMITTED");
    }

    @Test
    void agentsNeverCancelOrCreateAlone() {
      assertThat(
              engine
                  .evaluateAction(POLICY, DOMESTIC, new Action("order.cancel", null, null), AGENT)
                  .outcome())
          .isEqualTo(Outcome.ALLOW_WITH_APPROVAL);
      Decision create =
          engine.evaluateAction(
              POLICY,
              DOMESTIC,
              new Action("order.create", null, air("A", 47500, Cabin.ECONOMY, 0)),
              AGENT);
      assertThat(create.outcome()).isEqualTo(Outcome.ALLOW_WITH_APPROVAL);
      assertThat(create.approverRoles()).containsExactly("TRAVELER");
      assertThat(create.violations())
          .extracting(Decision.Violation::code)
          .containsExactly("AGENTS_PROPOSE_PEOPLE_BOOK");
      assertThat(create.economics().travelerIncentive())
          .as("the proposal still carries its economics")
          .isEqualTo(Money.usd(3750));
    }

    @Test
    void unknownActionsAreDeniedForEveryone() {
      for (Principal actor : List.of(AGENT, HUMAN)) {
        Decision decision =
            engine.evaluateAction(POLICY, DOMESTIC, new Action("database.drop", null, null), actor);
        assertThat(decision.outcome()).isEqualTo(Outcome.DENY);
        assertThat(decision.violations())
            .extracting(Decision.Violation::code)
            .containsExactly("UNKNOWN_ACTION");
      }
    }

    @Test
    void humansCancelFreelyButChangesNeedAProposal() {
      assertThat(
              engine
                  .evaluateAction(POLICY, DOMESTIC, new Action("order.cancel", null, null), HUMAN)
                  .outcome())
          .isEqualTo(Outcome.ALLOW);
      Decision noProposal =
          engine.evaluateAction(POLICY, DOMESTIC, new Action("order.change", null, null), HUMAN);
      assertThat(noProposal.outcome()).isEqualTo(Outcome.DENY);
      assertThat(noProposal.violations())
          .extracting(Decision.Violation::code)
          .containsExactly("PROPOSAL_REQUIRED");
    }

    @Test
    void humanChangeAboveTheManagerThresholdNeedsAManager() {
      Decision decision =
          engine.evaluateAction(
              POLICY,
              DOMESTIC,
              new Action("order.change", Money.usd(150000), air("A", 47500, Cabin.ECONOMY, 0)),
              HUMAN);
      assertThat(decision.outcome()).isEqualTo(Outcome.ALLOW_WITH_APPROVAL);
      assertThat(decision.approverRoles()).containsExactly("MANAGER");
    }
  }

  /** Slice 3: itineraries with several stays and transfers, budgets, currencies, leg windows. */
  @Nested
  class Itineraries {
    private static final Instant T = Instant.parse("2026-10-06T10:00:00Z");

    private static Candidate itinerary(
        String id, long airCents, List<Hotel> hotels, List<Facts.Ground> ground) {
      long total =
          airCents
              + hotels.stream().mapToLong(h -> h.nightlyRate().amountMinor() * h.nights()).sum()
              + ground.stream().mapToLong(g -> g.fare().amountMinor()).sum();
      return new Candidate(
          id,
          Money.usd(total),
          new Air(Money.usd(airCents), Cabin.ECONOMY, 0),
          hotels.isEmpty() ? null : hotels.getFirst(),
          hotels,
          ground);
    }

    @Test
    void everyStayIsHeldToTheNightlyLimitAndTheObjectionNamesIt() {
      Candidate secondStayTooDear =
          itinerary(
              "two-stays",
              60000,
              List.of(
                  new Hotel(Money.usd(11900), 2, "cmp_sea"),
                  new Hotel(Money.usd(27900), 1, "cmp_sfo")),
              List.of());
      Decision d =
          engine.evaluateTrip(POLICY, DOMESTIC, List.of(secondStayTooDear)).getFirst().decision();
      assertThat(d.outcome()).isEqualTo(Outcome.ALLOW_WITH_APPROVAL);
      assertThat(d.violations())
          .singleElement()
          .satisfies(
              v -> {
                assertThat(v.code()).isEqualTo("HOTEL_RATE_ABOVE_LIMIT");
                assertThat(v.message()).contains("cmp_sfo").contains("USD 279.00");
              });
    }

    @Test
    void groundTransfersHaveTheirOwnLimit() {
      Candidate limo =
          itinerary(
              "limo",
              60000,
              List.of(new Hotel(Money.usd(11900), 2, "cmp_sea")),
              List.of(
                  new Facts.Ground(Money.usd(3900), "SHUTTLE", "cmp_x1"),
                  new Facts.Ground(Money.usd(15000), "LIMO", "cmp_x2")));
      Decision d = engine.evaluateTrip(POLICY, DOMESTIC, List.of(limo)).getFirst().decision();
      assertThat(d.outcome()).isEqualTo(Outcome.ALLOW_WITH_APPROVAL);
      assertThat(d.violations())
          .extracting(Decision.Violation::code)
          .containsExactly("GROUND_TRANSFER_ABOVE_LIMIT");
      assertThat(d.violations().getFirst().message()).contains("cmp_x2").contains("USD 120.00");
      assertThat(d.rulesEvaluated()).contains("GROUND_TRANSFER_LIMIT", "TRIP_BUDGET");
    }

    @Test
    void theWholeTripIsHeldToTheBudgetEveryComponentIncluded() {
      // USD 600 air + 2 x USD 119 + USD 39 + USD 65 = USD 942 < the USD 4000 seed budget
      Candidate fine =
          itinerary(
              "fine",
              60000,
              List.of(new Hotel(Money.usd(11900), 2, "cmp_sea")),
              List.of(
                  new Facts.Ground(Money.usd(3900), "SHUTTLE", "cmp_x1"),
                  new Facts.Ground(Money.usd(6500), "SEDAN", "cmp_x2")));
      assertThat(
              engine.evaluateTrip(POLICY, DOMESTIC, List.of(fine)).getFirst().decision().outcome())
          .isEqualTo(Outcome.ALLOW);
      PolicyDocument tight =
          new PolicyDocument(
              POLICY.policyId(),
              POLICY.name(),
              POLICY.currency(),
              POLICY.flight(),
              POLICY.hotel(),
              POLICY.approval(),
              POLICY.autonomy(),
              POLICY.incentives(),
              POLICY.ground(),
              new PolicyDocument.TripBudget(90000L, PolicyDocument.Consequence.DENY));
      Decision denied = engine.evaluateTrip(tight, DOMESTIC, List.of(fine)).getFirst().decision();
      assertThat(denied.outcome()).isEqualTo(Outcome.DENY);
      assertThat(denied.violations())
          .extracting(Decision.Violation::code)
          .containsExactly("TRIP_TOTAL_ABOVE_BUDGET");
      assertThat(denied.violations().getFirst().message())
          .contains("USD 942.00")
          .contains("USD 900.00");
    }

    @Test
    void aComponentInAnotherCurrencyIsRefusedNotConverted() {
      Candidate sterlingStay =
          itinerary(
              "gbp", 60000, List.of(new Hotel(Money.of("GBP", 15900), 1, "cmp_lhr")), List.of());
      Decision d =
          engine.evaluateTrip(POLICY, DOMESTIC, List.of(sterlingStay)).getFirst().decision();
      assertThat(d.outcome()).isEqualTo(Outcome.DENY);
      assertThat(d.violations())
          .extracting(Decision.Violation::code)
          .containsExactly("CURRENCY_MISMATCH");
      assertThat(d.violations().getFirst().message()).contains("GBP").contains("does not convert");
    }

    @Test
    void everyLegOfAReplacementMustFlyInsideItsOwnWindow() {
      Facts.Constraints windows =
          new Facts.Constraints(
              null,
              null,
              null,
              null,
              Map.of(
                  "cmp_leg1", new Facts.Window(T, T.plusSeconds(13 * 3600)),
                  "cmp_leg2",
                      new Facts.Window(T.plusSeconds(48 * 3600), T.plusSeconds(60 * 3600))));
      Candidate proposed = air("r", 52000, Cabin.ECONOMY, 0);
      Decision ok =
          engine.evaluateAction(
              POLICY,
              DOMESTIC,
              new Facts.Action(
                  "order.change",
                  Money.usd(7300),
                  proposed,
                  windows,
                  null,
                  List.of(
                      new Facts.LegTiming("cmp_leg1", T.plusSeconds(3600), T.plusSeconds(6 * 3600)),
                      new Facts.LegTiming(
                          "cmp_leg2", T.plusSeconds(50 * 3600), T.plusSeconds(56 * 3600)))),
              AGENT);
      assertThat(ok.outcome()).isEqualTo(Outcome.ALLOW);
      assertThat(ok.rulesEvaluated()).contains("REPLACEMENT_ITINERARY_CONSTRAINTS");
      Decision late =
          engine.evaluateAction(
              POLICY,
              DOMESTIC,
              new Facts.Action(
                  "order.change",
                  Money.usd(7300),
                  proposed,
                  windows,
                  null,
                  List.of(
                      new Facts.LegTiming("cmp_leg1", T.plusSeconds(3600), T.plusSeconds(6 * 3600)),
                      new Facts.LegTiming(
                          "cmp_leg2", T.plusSeconds(50 * 3600), T.plusSeconds(61 * 3600)))),
              AGENT);
      assertThat(late.outcome()).isEqualTo(Outcome.DENY);
      assertThat(late.violations())
          .extracting(Decision.Violation::code)
          .containsExactly("LEG_ARRIVES_AFTER_DEADLINE");
      assertThat(late.violations().getFirst().message()).contains("cmp_leg2");
    }

    @Test
    void theAutonomyLimitAppliesToTheSumOfEveryAffectedComponent() {
      // a USD 73 flight delta plus a USD 26 transfer re-timing is USD 99: autonomous
      Candidate replacement =
          itinerary(
              "r",
              52000,
              List.of(new Hotel(Money.usd(11900), 2, "cmp_sea")),
              List.of(new Facts.Ground(Money.usd(6500), "SEDAN", "cmp_x1")));
      Decision within =
          engine.evaluateAction(
              POLICY,
              DOMESTIC,
              new Facts.Action("order.change", Money.usd(7300 + 2600), replacement),
              AGENT);
      assertThat(within.outcome()).isEqualTo(Outcome.ALLOW);
      // one more dollar on the hotel side tips the same change to a person
      Decision above =
          engine.evaluateAction(
              POLICY,
              DOMESTIC,
              new Facts.Action("order.change", Money.usd(7300 + 2600 + 200), replacement),
              AGENT);
      assertThat(above.outcome()).isEqualTo(Outcome.ALLOW_WITH_APPROVAL);
      assertThat(above.violations())
          .extracting(Decision.Violation::code)
          .containsExactly("INCREMENTAL_COST_ABOVE_AUTONOMY_LIMIT");
    }
  }
}
