package io.travelos.assistance.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * Phase 6 metrics. Labels are fixed vocabularies (a case kind, a queue, an action, a result); no
 * tenant, trip, traveler, owner or free text is a label.
 *
 * <ul>
 *   <li>{@code travelos_assistance_cases_total{kind,queue,result}} OPENED | LINKED | AUTO_RESOLVED
 *   <li>{@code travelos_assistance_actions_total{action,result}} ASSIGN | NOTE | ESCALATE | RESOLVE
 *       | CLOSE | STATUS x OK | REFUSED
 *   <li>{@code travelos_assistance_escalations_total{level}} by the sweep, when a case is overdue
 * </ul>
 */
@Component
public class AssistanceMetrics {
  private final MeterRegistry registry;

  public AssistanceMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void caseOutcome(String kind, String queue, String result) {
    count("travelos.assistance.cases", Tags.of("kind", kind, "queue", queue, "result", result));
  }

  public void action(String action, String result) {
    count("travelos.assistance.actions", Tags.of("action", action, "result", result));
  }

  /** Phase 8: {@code travelos_assistance_notifications_total{category,channel,result}}. */
  public void notification(String category, String channel, String result) {
    count(
        "travelos.assistance.notifications",
        Tags.of("category", category, "channel", channel, "result", result));
  }

  public void escalation(int level) {
    count("travelos.assistance.escalations", Tags.of("level", String.valueOf(level)));
  }

  private void count(String name, Tags tags) {
    Counter.builder(name).tags(tags).register(registry).increment();
  }
}
