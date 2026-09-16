package io.travelos.learning.profile;

import static org.assertj.core.api.Assertions.assertThat;

import io.travelos.common.tenant.TenantId;
import io.travelos.learning.LearningProperties;
import io.travelos.learning.model.Decision;
import io.travelos.learning.model.EvidenceClass;
import io.travelos.learning.model.Outcome;
import io.travelos.learning.model.OutcomeKind;
import io.travelos.learning.model.Profile;
import io.travelos.learning.model.ProfileStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The evaluator sees only the past of each decision, never the same trip, never the future. */
class ProfileEvaluatorTest {
  static final TenantId ACME = TenantId.of("acme");
  static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
  final LearningProperties p =
      new LearningProperties(
          EvidenceClass.SANDBOX,
          null,
          null,
          8.0,
          2.0,
          3,
          Duration.ofDays(180),
          40.0,
          10.0,
          5.0,
          Duration.ofDays(30),
          0.3,
          2,
          0.01);
  final ProfileEvaluator evaluator = new ProfileEvaluator(p);

  @Test
  void evidenceAfterTheDecisionOrFromItsOwnTripIsNotUsed() {
    Decision d = decision("opt_1", "trip_1", T0.plus(Duration.ofDays(10)), "air:DL");
    List<Outcome> outcomes =
        List.of(
            outcome(
                "trip_0", "air:DL", OutcomeKind.SUPPLIER_DISRUPTION, T0.plus(Duration.ofDays(1))),
            outcome(
                "trip_1", "air:DL", OutcomeKind.SUPPLIER_DISRUPTION, T0.plus(Duration.ofDays(2))),
            outcome(
                "trip_9", "air:DL", OutcomeKind.SUPPLIER_DISRUPTION, T0.plus(Duration.ofDays(20))));
    List<Outcome> causal = evaluator.causalEvidence(d, outcomes);
    assertThat(causal).extracting(Outcome::tripId).containsExactly("trip_0");
  }

  @Test
  void labelComesFromTheSelectedSupplierAfterTheDecisionOnly() {
    Decision d = decision("opt_1", "trip_1", T0.plus(Duration.ofDays(10)), "air:DL");
    assertThat(ProfileEvaluator.label(d, List.of())).isNull();
    assertThat(
            ProfileEvaluator.label(
                d,
                List.of(
                    outcome(
                        "trip_1",
                        "air:DL",
                        OutcomeKind.BOOKING_CONFIRMED,
                        T0.plus(Duration.ofDays(10)).plusSeconds(5)))))
        .isEqualTo(0);
    assertThat(
            ProfileEvaluator.label(
                d,
                List.of(
                    outcome(
                        "trip_1",
                        "air:DL",
                        OutcomeKind.BOOKING_CONFIRMED,
                        T0.plus(Duration.ofDays(10)).plusSeconds(5)),
                    outcome(
                        "trip_1",
                        "air:DL",
                        OutcomeKind.SUPPLIER_DISRUPTION,
                        T0.plus(Duration.ofDays(12))))))
        .isEqualTo(1);
    // a failure of another supplier on the same trip says nothing about the one selected
    assertThat(
            ProfileEvaluator.label(
                d,
                List.of(
                    outcome(
                        "trip_1",
                        "hotel:H1",
                        OutcomeKind.BOOKING_FAILED_SUPPLIER,
                        T0.plus(Duration.ofDays(12))))))
        .isNull();
    // and a failure before the decision is not its consequence
    assertThat(
            ProfileEvaluator.label(
                d,
                List.of(
                    outcome(
                        "trip_1",
                        "air:DL",
                        OutcomeKind.SUPPLIER_DISRUPTION,
                        T0.plus(Duration.ofDays(1))))))
        .isNull();
  }

  @Test
  void laterRevisionsSupersedeEarlierOnesAsOfTheirRecordingTime() {
    Outcome r1 = outcome("trip_1", "air:DL", OutcomeKind.SUPPLIER_DISRUPTION, T0);
    Outcome r2 =
        new Outcome(
            "out_2",
            ACME,
            r1.logicalKey(),
            2,
            OutcomeKind.SUPPLIER_DISRUPTION,
            OutcomeKind.Quality.FAILURE,
            "air:DL",
            "sandbox-air",
            EvidenceClass.SANDBOX,
            "trip_1",
            "ord_1",
            null,
            null,
            "dsr_1",
            null,
            T0,
            T0.plus(Duration.ofDays(5)),
            "EVENT",
            "evt_2",
            Map.of());
    assertThat(ProfileEvaluator.current(List.of(r1, r2), T0.plus(Duration.ofDays(1))))
        .containsExactly(r1);
    assertThat(ProfileEvaluator.current(List.of(r1, r2), null)).containsExactly(r2);
  }

  @Test
  void rejectsWithoutEvidenceAndPassesWhenNotWorseThanThePrior() {
    Profile empty = profile(Map.of("suppliers", Map.of()));
    ProfileEvaluator.Report r = evaluator.evaluate(empty, List.of(), List.of());
    assertThat(r.eligible()).isFalse();
    assertThat(r.verdict()).isEqualTo("INSUFFICIENT_EVIDENCE");

    // DL fails often; a profile that knows it predicts the holdout's failures better than the prior
    List<Decision> decisions = new ArrayList<>();
    List<Outcome> outcomes = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      Instant at = T0.plus(Duration.ofDays(i));
      decisions.add(decision("opt_" + i, "trip_" + i, at, "air:DL"));
      outcomes.add(
          outcome(
              "trip_" + i,
              "air:DL",
              i % 2 == 0 ? OutcomeKind.SUPPLIER_DISRUPTION : OutcomeKind.BOOKING_CONFIRMED,
              at.plusSeconds(60)));
    }
    Profile p =
        profile(
            Map.of(
                "suppliers",
                Map.of("air:DL", Map.of("samples", 8, "adjustment", -7.0, "estimate", 0.61))));
    r = evaluator.evaluate(p, decisions, outcomes);
    assertThat(r.details().get("holdoutDecisions")).isEqualTo(3);
    assertThat(r.details().get("decisionsLabeled")).isEqualTo(3);
    assertThat((Double) r.details().get("brierCandidate"))
        .isLessThanOrEqualTo((Double) r.details().get("brierBaseline") + 0.01);
    assertThat(r.eligible()).as(r.details().toString()).isTrue();
    assertThat(r.verdict()).isEqualTo("PASSED");

    // the same profile is not eligible in a LIVE deployment
    LearningProperties live =
        new LearningProperties(
            EvidenceClass.LIVE,
            null,
            null,
            8.0,
            2.0,
            3,
            Duration.ofDays(180),
            40.0,
            10.0,
            5.0,
            Duration.ofDays(30),
            0.3,
            2,
            0.01);
    ProfileEvaluator.Report mismatch = new ProfileEvaluator(live).evaluate(p, decisions, outcomes);
    assertThat(mismatch.eligible()).isFalse();
    assertThat(mismatch.verdict()).isEqualTo("CLASS_MISMATCH");

    // an unbounded adjustment is refused whatever the numbers say
    Profile wild =
        profile(
            Map.of(
                "suppliers",
                Map.of("air:DL", Map.of("samples", 8, "adjustment", 40.0, "estimate", 0.1))));
    assertThat(evaluator.evaluate(wild, decisions, outcomes).verdict())
        .isEqualTo("UNBOUNDED_ADJUSTMENT");
  }

  static Decision decision(String id, String trip, Instant at, String key) {
    return new Decision(
        id,
        ACME,
        trip,
        EvidenceClass.SANDBOX,
        at,
        "bdl_A",
        List.of(key),
        List.of(
            Map.of(
                "candidateId",
                "bdl_A",
                "feasible",
                true,
                "score",
                90.0,
                "supplierKeys",
                List.of(key)),
            Map.of(
                "candidateId",
                "bdl_B",
                "feasible",
                true,
                "score",
                85.0,
                "supplierKeys",
                List.of("air:UA"))),
        null,
        "evt_" + id);
  }

  static Outcome outcome(String trip, String key, OutcomeKind kind, Instant observed) {
    return new Outcome(
        "out_" + trip + kind + observed.getEpochSecond(),
        ACME,
        kind + ":" + trip + ":" + key,
        1,
        kind,
        kind.quality(),
        key,
        "sandbox-air",
        EvidenceClass.SANDBOX,
        trip,
        "ord_" + trip,
        null,
        null,
        null,
        null,
        observed,
        observed,
        "EVENT",
        "evt",
        Map.of());
  }

  static Profile profile(Map<String, Object> body) {
    return new Profile(
        "lp_01ARZ3NDEKTSV4RRFFQ69G5FAV",
        ACME,
        ProfileStatus.BUILT,
        "reliability-v1",
        EvidenceClass.SANDBOX,
        T0.plus(Duration.ofDays(30)),
        T0.minus(Duration.ofDays(150)),
        Map.of(),
        "fp",
        8,
        0,
        1,
        body,
        null,
        null,
        null,
        null,
        "human/carol",
        T0,
        T0,
        null,
        null);
  }
}
