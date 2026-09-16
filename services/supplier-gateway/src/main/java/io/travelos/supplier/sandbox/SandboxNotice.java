package io.travelos.supplier.sandbox;

import org.jspecify.annotations.Nullable;

/**
 * The sandbox airline's webhook body. A vendor shape, deliberately: it is what {@code
 * SandboxAirSupplier.normalizeNotification} maps into the platform's vocabulary.
 *
 * @param reaccommodation sandbox-only knob: how the airline reprices the alternatives, so a test
 *     can say "the best replacement costs +$73" and mean it
 */
record SandboxNotice(
    String eventId,
    String type,
    String externalOrderId,
    @Nullable String flightNumber,
    @Nullable String date,
    @Nullable String reason,
    @Nullable String severity,
    @Nullable Reaccommodation reaccommodation) {

  /**
   * @param fareDeltaMinor what the designated replacement costs on top of the original fare
   * @param nextDay when true the airline has nothing left today for this passenger: every flight on
   *     the cancelled date is full, and the reaccommodation fares apply to the next date (a
   *     controllable fixture for recoveries that move a hotel night)
   */
  record Reaccommodation(long fareDeltaMinor, @Nullable Boolean nextDay) {
    boolean movesToNextDay() {
      return nextDay != null && nextDay;
    }
  }
}
