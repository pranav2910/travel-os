package io.travelos.assistance.model;

import java.time.Instant;
import java.util.Map;

/** One entry of a case's history. */
public record CaseEvent(
    String caseEventId,
    String caseId,
    Kind kind,
    String actor,
    String message,
    Map<String, Object> data,
    Instant occurredAt) {
  public enum Kind {
    OPENED,
    LINKED,
    NOTE,
    ASSIGNED,
    STATUS,
    ESCALATED,
    RESOLVED,
    REOPENED,
    CLOSED
  }
}
