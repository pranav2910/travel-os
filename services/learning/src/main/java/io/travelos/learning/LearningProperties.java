package io.travelos.learning;

import io.travelos.learning.model.EvidenceClass;
import io.travelos.learning.model.LearningMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * travelos.learning.*: the learning objective's documented parameters (ADR-0013). Every number here
 * is recorded in a profile's {@code parameters} when it is built, so a profile can be reproduced.
 */
@ConfigurationProperties(prefix = "travelos.learning")
public record LearningProperties(
    EvidenceClass deploymentClass,
    LearningMode defaultMode,
    String algorithmVersion,
    Double priorSuccesses,
    Double priorFailures,
    Integer minSamples,
    Duration window,
    Double scale,
    Double maxAdjustment,
    Double feedbackMaxAdjustment,
    Duration staleAfter,
    Double holdoutFraction,
    Integer minLabeledDecisions,
    Double brierTolerance) {

  /** The optimizer refuses more than this whatever a profile says (learning.py HARD_MAX). */
  public static final double HARD_MAX_ADJUSTMENT = 25.0;

  public LearningProperties {
    deploymentClass = deploymentClass == null ? EvidenceClass.SANDBOX : deploymentClass;
    defaultMode = defaultMode == null ? LearningMode.SHADOW : defaultMode;
    algorithmVersion = algorithmVersion == null ? "reliability-v1" : algorithmVersion;
    priorSuccesses = priorSuccesses == null ? 8.0 : priorSuccesses;
    priorFailures = priorFailures == null ? 2.0 : priorFailures;
    minSamples = minSamples == null ? 3 : minSamples;
    window = window == null ? Duration.ofDays(180) : window;
    scale = scale == null ? 40.0 : scale;
    maxAdjustment =
        Math.min(HARD_MAX_ADJUSTMENT, Math.abs(maxAdjustment == null ? 10.0 : maxAdjustment));
    feedbackMaxAdjustment =
        Math.min(
            HARD_MAX_ADJUSTMENT,
            Math.abs(feedbackMaxAdjustment == null ? 5.0 : feedbackMaxAdjustment));
    staleAfter = staleAfter == null ? Duration.ofDays(30) : staleAfter;
    holdoutFraction = holdoutFraction == null ? 0.3 : Math.max(0.1, Math.min(0.5, holdoutFraction));
    minLabeledDecisions = minLabeledDecisions == null ? 3 : Math.max(1, minLabeledDecisions);
    brierTolerance = brierTolerance == null ? 0.01 : Math.max(0.0, brierTolerance);
    if (priorSuccesses <= 0 || priorFailures <= 0) {
      throw new IllegalArgumentException("priors must be positive");
    }
  }

  public double priorMean() {
    return priorSuccesses / (priorSuccesses + priorFailures);
  }
}
