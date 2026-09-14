package io.travelos.supplier.sandbox;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * What the sandbox airline does after it cancels a flight: the cancelled schedule slot disappears
 * from inventory for that route and date for everyone (every schedule flying the cancelled flight
 * number, since one outbound serves several inbound variants); the remaining schedules are repriced
 * as reaccommodation fares for the disrupted passenger only (the correlation id, i.e. the trip).
 * The designated replacement (same carrier, next departure, nonstop when one exists) costs exactly
 * {@code originalFareMinor + deltaMinor}; every other option costs more, in a fixed order, so tests
 * know precisely which itinerary the optimizer will pick and what it costs.
 *
 * <p>This is the synthetic supplier being deliberately deterministic (the brief for Slice 2); a
 * real airline's reaccommodation is whatever their revenue management says that day.
 */
record SandboxReaccommodation(
    String tenantId,
    String correlationId,
    String origin,
    String destination,
    LocalDate outboundDate,
    @Nullable LocalDate inboundDate,
    String cabin,
    int cancelledSlot,
    String cancelledFlight,
    int replacementSlot,
    long originalFareMinor,
    long deltaMinor) {

  /** Whether this search/price/change is the disrupted trip's own, and therefore sees the fares. */
  boolean repricesFor(String correlationId) {
    return correlationId != null && correlationId.equals(this.correlationId);
  }

  /**
   * Price a surviving schedule under this reaccommodation: every nonstop costs exactly the original
   * fare plus the delta (so whichever nonstop the optimizer finds feasible costs the same, known
   * amount), one-stops cost strictly more in a fixed order, and nothing ever gets cheaper.
   */
  long fareFor(boolean nonstop, long generatedFare, int rankAmongOneStops) {
    if (nonstop) {
      return originalFareMinor + deltaMinor;
    }
    return Math.max(
        generatedFare, originalFareMinor + deltaMinor + 2500L * (1 + rankAmongOneStops));
  }
}
