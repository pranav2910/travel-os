package io.travelos.learning.profile;

import io.travelos.learning.LearningProperties;
import io.travelos.learning.model.Outcome;
import io.travelos.learning.model.OutcomeKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Turns the dataset at a cutoff into the profile artifact, deterministically. */
public final class ProfileBuilder {

  /** The artifact plus the dataset facts recorded with it. */
  public record Built(
      String fingerprint,
      int hardOutcomes,
      int feedbackOutcomes,
      int supplierKeys,
      Map<String, Object> body) {}

  private final LearningProperties p;

  public ProfileBuilder(LearningProperties p) {
    this.p = p;
  }

  public Built build(List<Outcome> dataset) {
    Map<String, int[]> counts = new TreeMap<>(); // key -> [successes, failures]
    Map<String, Map<String, Integer>> kinds = new TreeMap<>();
    Map<String, Map<String, List<Integer>>> ratings = new TreeMap<>(); // traveler -> key -> ratings
    int hard = 0;
    int feedback = 0;
    List<String> lines = new ArrayList<>();
    for (Outcome o : dataset) {
      lines.add(
          o.logicalKey()
              + "|"
              + o.revision()
              + "|"
              + o.kind()
              + "|"
              + o.quality()
              + "|"
              + (o.supplierKey() == null ? "" : o.supplierKey())
              + "|"
              + o.observedAt());
      if (o.kind() == OutcomeKind.FEEDBACK) {
        feedback++;
        Object rating = o.provenance().get("rating");
        if (o.travelerId() != null && o.supplierKey() != null && rating instanceof Number n) {
          ratings
              .computeIfAbsent(o.travelerId(), k -> new TreeMap<>())
              .computeIfAbsent(o.supplierKey(), k -> new ArrayList<>())
              .add(n.intValue());
        }
        continue;
      }
      if (o.quality() == OutcomeKind.Quality.NEUTRAL || o.supplierKey() == null) {
        continue;
      }
      hard++;
      int[] c = counts.computeIfAbsent(o.supplierKey(), k -> new int[2]);
      if (o.quality() == OutcomeKind.Quality.SUCCESS) {
        c[0]++;
      } else {
        c[1]++;
      }
      kinds
          .computeIfAbsent(o.supplierKey(), k -> new TreeMap<>())
          .merge(o.kind().name(), 1, Integer::sum);
    }
    Map<String, Object> suppliers = new LinkedHashMap<>();
    for (Map.Entry<String, int[]> e : counts.entrySet()) {
      int s = e.getValue()[0];
      int f = e.getValue()[1];
      int n = s + f;
      double estimate =
          ReliabilityModel.round(
              ReliabilityModel.estimate(s, f, p.priorSuccesses(), p.priorFailures()));
      boolean enough = n >= p.minSamples();
      double adjustment =
          enough
              ? ReliabilityModel.adjustment(estimate, p.priorMean(), p.scale(), p.maxAdjustment())
              : 0.0;
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("successes", s);
      m.put("failures", f);
      m.put("samples", n);
      m.put("estimate", estimate);
      m.put("adjustment", adjustment);
      m.put("kinds", kinds.get(e.getKey()));
      m.put(
          "reason",
          enough
              ? String.format(
                  "%s reliability %.2f over %d outcomes (prior %.2f)",
                  e.getKey(), estimate, n, p.priorMean())
              : String.format(
                  "%s: %d outcomes, fewer than %d; stays at the prior %.2f",
                  e.getKey(), n, p.minSamples(), p.priorMean()));
      suppliers.put(e.getKey(), m);
    }
    Map<String, Object> travelers = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, List<Integer>>> t : ratings.entrySet()) {
      Map<String, Object> keys = new LinkedHashMap<>();
      for (Map.Entry<String, List<Integer>> k : t.getValue().entrySet()) {
        Map<String, Object> m = new LinkedHashMap<>();
        double adj = ReliabilityModel.preference(k.getValue(), p.feedbackMaxAdjustment());
        m.put("samples", k.getValue().size());
        m.put("estimate", ReliabilityModel.preferenceEstimate(k.getValue()));
        m.put("adjustment", adj);
        m.put(
            "reason",
            String.format(
                "%s rated %.1f/5 by the traveler over %d feedback revision(s)",
                k.getKey(),
                ReliabilityModel.preferenceEstimate(k.getValue()),
                k.getValue().size()));
        keys.put(k.getKey(), m);
      }
      travelers.put(t.getKey(), keys);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("algorithmVersion", p.algorithmVersion());
    body.put("priorMean", ReliabilityModel.round(p.priorMean()));
    body.put("maxAdjustment", p.maxAdjustment());
    body.put("feedbackMaxAdjustment", p.feedbackMaxAdjustment());
    body.put("suppliers", suppliers);
    body.put("travelers", travelers);
    return new Built(fingerprint(lines), hard, feedback, suppliers.size(), body);
  }

  public Map<String, Object> parameters(java.time.Duration window) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("algorithmVersion", p.algorithmVersion());
    m.put("priorSuccesses", p.priorSuccesses());
    m.put("priorFailures", p.priorFailures());
    m.put("priorMean", ReliabilityModel.round(p.priorMean()));
    m.put("minSamples", p.minSamples());
    m.put("window", window.toString());
    m.put("windowMaxDays", p.window().toDays());
    m.put("scale", p.scale());
    m.put("maxAdjustment", p.maxAdjustment());
    m.put("feedbackMaxAdjustment", p.feedbackMaxAdjustment());
    m.put("holdoutFraction", p.holdoutFraction());
    m.put("minLabeledDecisions", p.minLabeledDecisions());
    m.put("brierTolerance", p.brierTolerance());
    return m;
  }

  static String fingerprint(List<String> lines) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (String line : lines) {
        md.update(line.getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\n');
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
