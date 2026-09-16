package io.travelos.learning.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * Slice 5 metrics. Every label is a fixed vocabulary (an outcome kind, an evidence class, a mode, a
 * fallback reason); no tenant, trip, traveler, supplier key, profile id or free text is a label.
 *
 * <ul>
 *   <li>{@code travelos_learning_outcomes_total{kind,class,result}} RECORDED | DUPLICATE_EVENT |
 *       DUPLICATE_REPRESENTATION | REVISED | STALE_REVISION
 *   <li>{@code travelos_learning_decisions_total{result}} RECORDED | DUPLICATE
 *   <li>{@code travelos_learning_builds_total{result}} BUILT | REJECTED | FAILED
 *   <li>{@code travelos_learning_evaluations_total{verdict}}
 *   <li>{@code travelos_learning_resolutions_total{mode,result}} APPLIED | SHADOW | the fallback
 *       reason
 *   <li>{@code travelos_learning_activations_total{action,result}} ACTIVATED | ROLLED_BACK |
 *       MODE_CHANGED x OK | REFUSED | CONFLICT
 *   <li>{@code travelos_learning_feedback_total{result}} RECORDED | REVISED | UNCHANGED | REFUSED
 * </ul>
 */
@Component
public class LearningMetrics {
  private final MeterRegistry registry;

  public LearningMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void outcome(String kind, String evidenceClass, String result) {
    count(
        "travelos.learning.outcomes",
        Tags.of("kind", kind, "class", evidenceClass, "result", result));
  }

  public void decision(String result) {
    count("travelos.learning.decisions", Tags.of("result", result));
  }

  public void build(String result) {
    count("travelos.learning.builds", Tags.of("result", result));
  }

  public void evaluation(String verdict) {
    count("travelos.learning.evaluations", Tags.of("verdict", verdict));
  }

  public void resolution(String mode, String result) {
    count("travelos.learning.resolutions", Tags.of("mode", mode, "result", result));
  }

  public void activation(String action, String result) {
    count("travelos.learning.activations", Tags.of("action", action, "result", result));
  }

  public void feedback(String result) {
    count("travelos.learning.feedback", Tags.of("result", result));
  }

  private void count(String name, Tags tags) {
    Counter.builder(name).tags(tags).register(registry).increment();
  }
}
