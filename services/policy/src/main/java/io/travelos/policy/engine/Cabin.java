package io.travelos.policy.engine;

/** Cabin classes in ascending order of comfort (and, normally, price). */
public enum Cabin {
  ECONOMY,
  PREMIUM_ECONOMY,
  BUSINESS,
  FIRST;

  public boolean isAbove(Cabin other) {
    return ordinal() > other.ordinal();
  }
}
