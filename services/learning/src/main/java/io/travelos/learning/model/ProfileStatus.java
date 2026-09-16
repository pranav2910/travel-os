package io.travelos.learning.model;

public enum ProfileStatus {
  BUILDING,
  BUILT,
  ELIGIBLE,
  REJECTED,
  FAILED;

  public boolean isFinal() {
    return this == ELIGIBLE || this == REJECTED || this == FAILED;
  }
}
