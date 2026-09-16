package io.travelos.learning.profile;

import java.util.List;

/**
 * reliability-v1, the whole model (ADR-0013):
 *
 * <ul>
 *   <li>Supplier reliability per supplier key: a Beta(priorSuccesses, priorFailures) posterior mean
 *       over SUCCESS / FAILURE outcomes in the window. Fewer than {@code minSamples} observations
 *       leave the key at the prior: no observations is not "unreliable".
 *   <li>Adjustment: {@code clamp(scale * (estimate - priorMean), -max, +max)} score points, a soft
 *       preference among feasible, policy-permitted options. Never money.
 *   <li>Traveler preference per (traveler, supplier key): shrunk mean of feedback ratings mapped to
 *       [-1, 1], times a smaller bound.
 * </ul>
 */
public final class ReliabilityModel {
  private ReliabilityModel() {}

  public static double estimate(int successes, int failures, double priorS, double priorF) {
    return (successes + priorS) / (successes + failures + priorS + priorF);
  }

  public static double adjustment(double estimate, double priorMean, double scale, double max) {
    double raw = scale * (estimate - priorMean);
    if (Double.isNaN(raw) || Double.isInfinite(raw)) {
      return 0.0;
    }
    return round(Math.max(-max, Math.min(max, raw)));
  }

  /** Ratings 1..5 -> [-1, 1], shrunk by one pseudo-observation, bounded by {@code max}. */
  public static double preference(List<Integer> ratings, double max) {
    if (ratings.isEmpty()) {
      return 0.0;
    }
    double sum = 0;
    for (int r : ratings) {
      sum += (r - 3) / 2.0;
    }
    double shrunk = sum / (ratings.size() + 1);
    return round(Math.max(-max, Math.min(max, max * shrunk)));
  }

  public static double preferenceEstimate(List<Integer> ratings) {
    if (ratings.isEmpty()) {
      return 0.0;
    }
    double sum = 0;
    for (int r : ratings) {
      sum += r;
    }
    return round(sum / ratings.size());
  }

  public static double round(double v) {
    return Math.round(v * 1000.0) / 1000.0;
  }
}
