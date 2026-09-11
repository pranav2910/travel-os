package io.travelos.order.store;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Mirrors travelos.order.v1.OrderStatus. Transitions are explicit. */
public enum OrderStatus {
  CREATING,
  HELD,
  PAYMENT_PENDING,
  CONFIRMING,
  CONFIRMED,
  CHANGE_PENDING,
  CHANGED,
  CANCELLATION_PENDING,
  CANCELLED,
  FAILED,
  PARTIALLY_FAILED;

  private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS =
      Map.ofEntries(
          Map.entry(CREATING, EnumSet.of(CONFIRMING, CONFIRMED, FAILED, PARTIALLY_FAILED)),
          Map.entry(HELD, EnumSet.of(PAYMENT_PENDING, CONFIRMING, FAILED)),
          Map.entry(PAYMENT_PENDING, EnumSet.of(CONFIRMING, FAILED)),
          Map.entry(CONFIRMING, EnumSet.of(CONFIRMED, FAILED, PARTIALLY_FAILED)),
          Map.entry(CONFIRMED, EnumSet.of(CHANGE_PENDING, CANCELLATION_PENDING, CANCELLED)),
          Map.entry(CHANGE_PENDING, EnumSet.of(CHANGED, CONFIRMED)),
          Map.entry(CHANGED, EnumSet.of(CHANGE_PENDING, CANCELLATION_PENDING, CANCELLED)),
          Map.entry(CANCELLATION_PENDING, EnumSet.of(CANCELLED, CONFIRMED)),
          Map.entry(CANCELLED, EnumSet.noneOf(OrderStatus.class)),
          Map.entry(FAILED, EnumSet.noneOf(OrderStatus.class)),
          Map.entry(PARTIALLY_FAILED, EnumSet.of(FAILED)));

  public boolean canTransitionTo(OrderStatus next) {
    return TRANSITIONS.get(this).contains(next);
  }

  public boolean isTerminal() {
    return TRANSITIONS.get(this).isEmpty();
  }
}
