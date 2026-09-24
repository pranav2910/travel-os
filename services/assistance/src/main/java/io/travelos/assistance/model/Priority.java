package io.travelos.assistance.model;

public enum Priority {
  CRITICAL,
  HIGH,
  NORMAL,
  LOW;

  /** One step more urgent; CRITICAL stays CRITICAL. */
  public Priority raised() {
    return this == LOW ? NORMAL : this == NORMAL ? HIGH : CRITICAL;
  }
}
