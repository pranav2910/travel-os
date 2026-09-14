package io.travelos.disruption.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The recovery metrics, named so Prometheus renders them as the Slice 2 brief asks:
 * disruptions_detected_total, recovery_attempts_total, recovery_success_total,
 * recovery_failure_total, autonomous_recovery_total, human_escalation_total,
 * recovery_duration_seconds, incremental_rebooking_cost, duplicate_disruption_events_total.
 */
@Component
public class RecoveryMetrics {

  private final Counter detected;
  private final Counter attempts;
  private final Counter success;
  private final Counter failure;
  private final Counter autonomous;
  private final Counter escalated;
  private final Counter duplicates;
  private final Timer duration;
  private final DistributionSummary incrementalCost;

  public RecoveryMetrics(MeterRegistry registry) {
    this.detected = Counter.builder("disruptions.detected").register(registry);
    this.attempts = Counter.builder("recovery.attempts").register(registry);
    this.success = Counter.builder("recovery.success").register(registry);
    this.failure = Counter.builder("recovery.failure").register(registry);
    this.autonomous = Counter.builder("autonomous.recovery").register(registry);
    this.escalated = Counter.builder("human.escalation").register(registry);
    this.duplicates = Counter.builder("duplicate.disruption.events").register(registry);
    this.duration =
        Timer.builder("recovery.duration")
            .description("detected -> resolved, per disruption")
            .publishPercentileHistogram()
            .register(registry);
    this.incrementalCost =
        DistributionSummary.builder("incremental.rebooking.cost")
            .description(
                "incremental cost of each applied rebooking, in minor units of the order's currency")
            .register(registry);
  }

  public void detected() {
    detected.increment();
  }

  public void duplicate() {
    duplicates.increment();
  }

  public void attempt() {
    attempts.increment();
  }

  public void autonomous() {
    autonomous.increment();
  }

  public void escalated() {
    escalated.increment();
  }

  public void resolved(Duration took, long incrementalMinor) {
    success.increment();
    duration.record(took);
    incrementalCost.record(incrementalMinor);
  }

  public void failed() {
    failure.increment();
  }
}
