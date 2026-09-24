package io.travelos.assistance.model;

/** Who works the case first. Escalation moves a case up, never sideways. */
public enum Queue {
  TRAVEL_OPS,
  FINANCE,
  APPROVALS,
  SAFETY
}
