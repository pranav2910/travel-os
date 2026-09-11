package io.travelos.common.time;

import java.time.Clock;
import java.time.Duration;

/**
 * The platform clock. Ticks at microseconds because Postgres {@code timestamptz} stores
 * microseconds: an {@code Instant} taken with nanosecond precision (Linux) would differ from its
 * own stored copy, and "what we returned" must always equal "what we persisted".
 */
public final class Clocks {

  private Clocks() {}

  public static Clock micros() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
  }
}
