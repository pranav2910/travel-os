package io.travelos.learning.profile;

import io.travelos.learning.LearningProperties;
import io.travelos.learning.model.Decision;
import io.travelos.learning.model.EvidenceClass;
import io.travelos.learning.model.Outcome;
import io.travelos.learning.model.OutcomeKind;
import io.travelos.learning.model.Profile;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluation before activation, chronological and leak-free: the decisions the platform actually
 * made are replayed oldest first; the last part is the holdout; for every holdout decision the
 * candidate's estimate uses only outcomes observed and recorded before that decision, never from
 * the same trip (records are grouped by trip); the label is what happened to the supplier that was
 * selected, after the decision. The score is the Brier score of the predicted failure probability
 * against the prior's, plus how often the learned ranking would have picked something else.
 *
 * <p>Limits, stated in the report: a different pick's outcome is unknown (counterfactual); labels
 * exist only for trips that produced a hard outcome; SANDBOX evidence is synthetic.
 */
public final class ProfileEvaluator {

  public record Report(String verdict, boolean eligible, Map<String, Object> details) {}

  private final LearningProperties p;

  public ProfileEvaluator(LearningProperties p) {
    this.p = p;
  }

  /**
   * @param profile the built profile (its body must be present)
   * @param decisions every decision of the profile's evidence class decided by its cutoff, oldest
   *     first
   * @param outcomes every outcome revision of the class recorded by the cutoff (all revisions; the
   *     evaluator picks the revision current at each decision)
   */
  public Report evaluate(Profile profile, List<Decision> decisions, List<Outcome> outcomes) {
    List<String> reasons = new ArrayList<>();
    Map<String, Object> criteria = new LinkedHashMap<>();
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("method", "chronological-holdout");
    details.put("syntheticEvidence", profile.evidenceClass() == EvidenceClass.SANDBOX);
    details.put(
        "limits",
        List.of(
            "the outcome of a different pick is unknown: 'ranking changed' counts decisions, not avoided failures",
            "labels exist only for decisions whose trip produced a hard outcome for the selected supplier",
            profile.evidenceClass() == EvidenceClass.SANDBOX
                ? "SANDBOX evidence is SIMULATED; nothing here transfers to live suppliers"
                : "LIVE evidence; supplier behaviour drifts, hence the window and the stale-after rule"));

    boolean compatible = p.algorithmVersion().equals(profile.algorithmVersion());
    criteria.put("algorithmCompatible", compatible);
    if (!compatible) {
      reasons.add("INCOMPATIBLE_ALGORITHM: " + profile.algorithmVersion());
    }
    boolean classOk = profile.evidenceClass() == p.deploymentClass();
    criteria.put("evidenceClassMatchesDeployment", classOk);
    if (!classOk) {
      reasons.add(
          "CLASS_MISMATCH: profile is "
              + profile.evidenceClass()
              + ", deployment is "
              + p.deploymentClass());
    }

    // ---- bounded, finite adjustments; enough evidence somewhere
    Map<String, Double> adjustments = supplierAdjustments(profile);
    boolean bounded = true;
    int keysWithEvidence = 0;
    for (Map.Entry<String, Object> e : suppliers(profile).entrySet()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) e.getValue();
      double adj = ((Number) m.getOrDefault("adjustment", 0)).doubleValue();
      int samples = ((Number) m.getOrDefault("samples", 0)).intValue();
      if (Double.isNaN(adj)
          || Double.isInfinite(adj)
          || Math.abs(adj) > p.maxAdjustment() + 1e-9
          || Math.abs(adj) > LearningProperties.HARD_MAX_ADJUSTMENT) {
        bounded = false;
      }
      if (samples >= p.minSamples()) {
        keysWithEvidence++;
      }
    }
    criteria.put("adjustmentsFiniteAndBounded", bounded);
    if (!bounded) {
      reasons.add("UNBOUNDED_ADJUSTMENT");
    }
    boolean enough = keysWithEvidence > 0;
    criteria.put("sufficientEvidence", enough);
    details.put("supplierKeysWithEvidence", keysWithEvidence);
    details.put("hardOutcomes", profile.hardOutcomes());
    if (!enough) {
      reasons.add("INSUFFICIENT_EVIDENCE: no supplier key reached " + p.minSamples() + " outcomes");
    }

    // ---- chronological holdout: a suffix of the decisions, at least the configured fraction, and
    // long enough to contain the minimum number of labeled decisions when the record allows it
    // (decisions that were never executed, such as a recovery a person still has to approve, carry
    // no label and are reported as missing, not silently dropped).
    int n = decisions.size();
    int holdout = Math.min(n, Math.max(1, (int) Math.ceil(p.holdoutFraction() * n)));
    int labeledInSuffix = 0;
    for (int i = n - 1; i >= 0 && labeledInSuffix < p.minLabeledDecisions(); i--) {
      if (label(decisions.get(i), outcomes) != null) {
        labeledInSuffix++;
      }
      holdout = Math.max(holdout, n - i);
    }
    int trainEnd = n - holdout;
    List<Decision> held = decisions.subList(trainEnd, n);
    int labeled = 0;
    int positives = 0;
    int changed = 0;
    int changedAndBad = 0;
    int violations = 0;
    double brierBaseline = 0;
    double brierCandidate = 0;
    double pBaseline = 1.0 - p.priorMean();
    List<Map<String, Object>> samples = new ArrayList<>();
    java.time.Duration window =
        java.time.Duration.between(profile.windowStart(), profile.inputCutoff());
    for (Decision d : held) {
      Integer label = label(d, outcomes);
      List<Outcome> evidence = causalEvidence(d, outcomes, window);
      Map<String, double[]> counts = counts(evidence);
      double pCandidate = failureProbability(d.selectedKeys(), counts);
      String learnedTop = learnedTop(d, counts);
      boolean differs =
          learnedTop != null && d.selectedId() != null && !learnedTop.equals(d.selectedId());
      if (differs) {
        changed++;
      }
      if (learnedTop != null && !feasible(d, learnedTop)) {
        violations++;
      }
      Map<String, Object> sample = new LinkedHashMap<>();
      sample.put("decisionId", d.decisionId());
      sample.put("decidedAt", d.decidedAt().toString());
      sample.put("selectedKeys", d.selectedKeys());
      sample.put("evidenceBefore", evidence.size());
      sample.put("predictedFailure", ReliabilityModel.round(pCandidate));
      sample.put("label", label == null ? "UNKNOWN" : label);
      sample.put("learnedSelection", learnedTop == null ? d.selectedId() : learnedTop);
      sample.put("rankingChanged", differs);
      if (samples.size() < 50) {
        samples.add(sample);
      }
      if (label == null) {
        continue;
      }
      labeled++;
      positives += label;
      brierBaseline += Math.pow(pBaseline - label, 2);
      brierCandidate += Math.pow(pCandidate - label, 2);
      if (differs && label == 1) {
        changedAndBad++;
      }
    }
    Double bb = labeled == 0 ? null : ReliabilityModel.round(brierBaseline / labeled);
    Double bc = labeled == 0 ? null : ReliabilityModel.round(brierCandidate / labeled);
    details.put("decisionsTotal", n);
    details.put("trainDecisions", trainEnd);
    details.put("holdoutDecisions", holdout);
    details.put("decisionsLabeled", labeled);
    details.put("labelsMissing", holdout - labeled);
    details.put(
        "labelCoverage", holdout == 0 ? 0.0 : ReliabilityModel.round((double) labeled / holdout));
    details.put("positiveLabels", positives);
    details.put("brierBaseline", bb);
    details.put("brierCandidate", bc);
    details.put("rankingChanged", changed);
    details.put("badOutcomesWhereRankingChanged", changedAndBad);
    details.put("hardConstraintViolations", violations);
    details.put("samples", samples);

    criteria.put("zeroHardConstraintViolations", violations == 0);
    if (violations > 0) {
      reasons.add("HARD_CONSTRAINT_VIOLATIONS: " + violations);
    }
    boolean enoughLabels = labeled >= p.minLabeledDecisions();
    criteria.put("sufficientLabeledDecisions", enoughLabels);
    if (!enoughLabels) {
      reasons.add("INSUFFICIENT_LABELED_DECISIONS: " + labeled + " < " + p.minLabeledDecisions());
    }
    boolean quality = bb != null && bc != null && bc <= bb + p.brierTolerance();
    criteria.put("qualityNotWorseThanBaseline", quality);
    if (enoughLabels && !quality) {
      reasons.add(
          String.format(
              "QUALITY_BELOW_BASELINE: brier %.3f > baseline %.3f + %.3f",
              bc, bb, p.brierTolerance()));
    }
    boolean eligible =
        compatible && classOk && bounded && enough && violations == 0 && enoughLabels && quality;
    if (eligible) {
      reasons.add(String.format("brier %.3f <= baseline %.3f", bc, bb));
      reasons.add(labeled + " labeled decisions >= " + p.minLabeledDecisions());
      reasons.add(keysWithEvidence + " supplier key(s) with >= " + p.minSamples() + " outcomes");
    }
    String verdict = eligible ? "PASSED" : reasons.get(0).split(":")[0];
    details.put("criteria", criteria);
    details.put("reasons", reasons);
    details.put("verdict", verdict);
    return new Report(verdict, eligible, details);
  }

  // ------------------------------------------------------------------ pieces

  /**
   * 1 when a FAILURE outcome followed for a selected key on this trip, 0 when only SUCCESS, null
   * when nothing.
   */
  static Integer label(Decision d, List<Outcome> outcomes) {
    Integer label = null;
    for (Outcome o : current(outcomes, null)) {
      if (!d.tripId().equals(o.tripId())
          || o.supplierKey() == null
          || !d.selectedKeys().contains(o.supplierKey())
          || o.quality() == OutcomeKind.Quality.NEUTRAL
          || o.observedAt().isBefore(d.decidedAt())) {
        continue;
      }
      if (o.quality() == OutcomeKind.Quality.FAILURE) {
        return 1;
      }
      label = 0;
    }
    return label;
  }

  /** Outcomes observed and recorded before the decision, inside the window, from other trips. */
  List<Outcome> causalEvidence(Decision d, List<Outcome> outcomes) {
    return causalEvidence(d, outcomes, p.window());
  }

  List<Outcome> causalEvidence(Decision d, List<Outcome> outcomes, java.time.Duration window) {
    Instant at = d.decidedAt();
    Instant start = at.minus(window);
    List<Outcome> out = new ArrayList<>();
    for (Outcome o : current(outcomes, at)) {
      if (o.recordedAt().isBefore(at)
          && o.observedAt().isBefore(at)
          && o.observedAt().isAfter(start)
          && !d.tripId().equals(o.tripId())
          && o.supplierKey() != null
          && o.quality() != OutcomeKind.Quality.NEUTRAL) {
        out.add(o);
      }
    }
    return out;
  }

  /** The revision of each logical key that was current at {@code at} (or the latest when null). */
  static List<Outcome> current(List<Outcome> outcomes, Instant at) {
    Map<String, Outcome> latest = new HashMap<>();
    for (Outcome o : outcomes) {
      if (at != null && !o.recordedAt().isBefore(at)) {
        continue;
      }
      Outcome seen = latest.get(o.logicalKey());
      if (seen == null || o.revision() > seen.revision()) {
        latest.put(o.logicalKey(), o);
      }
    }
    return new ArrayList<>(latest.values());
  }

  static Map<String, double[]> counts(List<Outcome> evidence) {
    Map<String, double[]> counts = new HashMap<>();
    for (Outcome o : evidence) {
      double[] c = counts.computeIfAbsent(o.supplierKey(), k -> new double[2]);
      if (o.quality() == OutcomeKind.Quality.SUCCESS) {
        c[0]++;
      } else {
        c[1]++;
      }
    }
    return counts;
  }

  double failureProbability(List<String> keys, Map<String, double[]> counts) {
    double sum = 0;
    int n = 0;
    for (String key : keys) {
      double[] c = counts.get(key);
      if (c == null || c[0] + c[1] < p.minSamples()) {
        sum += p.priorMean();
      } else {
        sum +=
            ReliabilityModel.estimate(
                (int) c[0], (int) c[1], p.priorSuccesses(), p.priorFailures());
      }
      n++;
    }
    return n == 0 ? 1.0 - p.priorMean() : 1.0 - sum / n;
  }

  double adjustmentFor(List<String> keys, Map<String, double[]> counts) {
    double total = 0;
    for (String key : keys) {
      double[] c = counts.get(key);
      if (c != null && c[0] + c[1] >= p.minSamples()) {
        total +=
            ReliabilityModel.adjustment(
                ReliabilityModel.estimate(
                    (int) c[0], (int) c[1], p.priorSuccesses(), p.priorFailures()),
                p.priorMean(),
                p.scale(),
                p.maxAdjustment());
      }
    }
    return Math.max(-p.maxAdjustment(), Math.min(p.maxAdjustment(), total));
  }

  /** The feasible candidate the learned ranking prefers, with cost then id as the tie-break. */
  String learnedTop(Decision d, Map<String, double[]> counts) {
    String best = null;
    double bestScore = Double.NEGATIVE_INFINITY;
    for (Map<String, Object> c : d.candidates()) {
      if (!Boolean.TRUE.equals(c.get("feasible"))) {
        continue;
      }
      List<String> keys = new ArrayList<>();
      if (c.get("supplierKeys") instanceof List<?> l) {
        l.forEach(k -> keys.add(String.valueOf(k)));
      }
      double score =
          ((Number) c.getOrDefault("score", 0)).doubleValue() + adjustmentFor(keys, counts);
      String id = String.valueOf(c.get("candidateId"));
      if (score > bestScore + 1e-9
          || (Math.abs(score - bestScore) <= 1e-9 && best != null && id.compareTo(best) < 0)) {
        bestScore = score;
        best = id;
      }
    }
    return best;
  }

  static boolean feasible(Decision d, String candidateId) {
    for (Map<String, Object> c : d.candidates()) {
      if (candidateId.equals(String.valueOf(c.get("candidateId")))) {
        return Boolean.TRUE.equals(c.get("feasible"));
      }
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> suppliers(Profile profile) {
    Map<String, Object> body = profile.body() == null ? Map.of() : profile.body();
    return body.get("suppliers") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  static Map<String, Double> supplierAdjustments(Profile profile) {
    Map<String, Double> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : suppliers(profile).entrySet()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) e.getValue();
      out.put(e.getKey(), ((Number) m.getOrDefault("adjustment", 0)).doubleValue());
    }
    return out;
  }
}
