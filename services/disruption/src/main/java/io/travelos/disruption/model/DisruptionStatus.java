package io.travelos.disruption.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * DETECTED -> IMPACT_CONFIRMED -> SEARCHING_ALTERNATIVES -> OPTIMIZING -> DECISION_READY ->
 * AUTO_ALLOWED | HUMAN_REQUIRED -> CHANGING -> RESOLVED, with NO_ALTERNATIVE, FAILED and
 * MANUAL_INTERVENTION_REQUIRED terminal on the side. Anything not listed is a bug, not a feature.
 */
public enum DisruptionStatus {
  DETECTED,
  IMPACT_CONFIRMED,
  SEARCHING_ALTERNATIVES,
  OPTIMIZING,
  DECISION_READY,
  AUTO_ALLOWED,
  HUMAN_REQUIRED,
  CHANGING,
  RESOLVED,
  NO_ALTERNATIVE,
  FAILED,
  MANUAL_INTERVENTION_REQUIRED;

  private static final Set<DisruptionStatus> TERMINAL_FAILURES =
      EnumSet.of(NO_ALTERNATIVE, FAILED, MANUAL_INTERVENTION_REQUIRED);

  private static final Map<DisruptionStatus, Set<DisruptionStatus>> TRANSITIONS =
      Map.ofEntries(
          Map.entry(DETECTED, with(IMPACT_CONFIRMED)),
          Map.entry(IMPACT_CONFIRMED, with(SEARCHING_ALTERNATIVES)),
          Map.entry(SEARCHING_ALTERNATIVES, with(OPTIMIZING, DECISION_READY)),
          Map.entry(OPTIMIZING, with(DECISION_READY)),
          Map.entry(DECISION_READY, with(AUTO_ALLOWED, HUMAN_REQUIRED)),
          Map.entry(AUTO_ALLOWED, with(CHANGING)),
          Map.entry(HUMAN_REQUIRED, with(CHANGING)),
          Map.entry(CHANGING, with(RESOLVED)),
          Map.entry(RESOLVED, EnumSet.noneOf(DisruptionStatus.class)),
          Map.entry(NO_ALTERNATIVE, EnumSet.noneOf(DisruptionStatus.class)),
          Map.entry(FAILED, EnumSet.noneOf(DisruptionStatus.class)),
          Map.entry(MANUAL_INTERVENTION_REQUIRED, EnumSet.noneOf(DisruptionStatus.class)));

  /** Every non-terminal state may end in one of the terminal failures. */
  private static Set<DisruptionStatus> with(DisruptionStatus... next) {
    Set<DisruptionStatus> s = EnumSet.copyOf(TERMINAL_FAILURES);
    for (DisruptionStatus n : next) {
      s.add(n);
    }
    return s;
  }

  public boolean canTransitionTo(DisruptionStatus next) {
    return TRANSITIONS.get(this).contains(next);
  }

  public boolean isTerminal() {
    return TRANSITIONS.get(this).isEmpty();
  }

  public boolean isTerminalFailure() {
    return TERMINAL_FAILURES.contains(this);
  }
}
