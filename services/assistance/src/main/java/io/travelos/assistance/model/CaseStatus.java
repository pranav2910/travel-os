package io.travelos.assistance.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum CaseStatus {
  OPEN,
  IN_PROGRESS,
  WAITING,
  ESCALATED,
  RESOLVED,
  CLOSED;

  private static final Map<CaseStatus, Set<CaseStatus>> TRANSITIONS =
      Map.of(
          OPEN, EnumSet.of(IN_PROGRESS, WAITING, ESCALATED, RESOLVED),
          IN_PROGRESS, EnumSet.of(WAITING, ESCALATED, RESOLVED),
          WAITING, EnumSet.of(IN_PROGRESS, ESCALATED, RESOLVED),
          ESCALATED, EnumSet.of(IN_PROGRESS, WAITING, ESCALATED, RESOLVED),
          RESOLVED, EnumSet.of(CLOSED, OPEN),
          CLOSED, EnumSet.noneOf(CaseStatus.class));

  public boolean canMoveTo(CaseStatus to) {
    return TRANSITIONS.get(this).contains(to);
  }

  public boolean open() {
    return this != RESOLVED && this != CLOSED;
  }
}
