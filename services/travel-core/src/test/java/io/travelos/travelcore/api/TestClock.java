package io.travelos.travelcore.api;

import io.travelos.common.time.Clocks;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The platform clock under test: it runs at real speed but starts at a pinned instant, so the
 * fixtures' travel dates stay in the future whenever the suite runs, and a test can move it forward
 * to make them the past ({@link #advance}) and back ({@link #reset}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClock {

  public static final Instant PINNED_START = Instant.parse("2026-09-23T10:00:00Z");
  private static final Instant REAL_START = Clocks.micros().instant();
  private static volatile Duration extra = Duration.ZERO;

  public static void advance(Duration by) {
    extra = by;
  }

  public static void reset() {
    extra = Duration.ZERO;
  }

  public static Instant now() {
    return clock().instant();
  }

  static Clock clock() {
    Duration shift = Duration.between(REAL_START, PINNED_START);
    return new Clock() {
      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        return Clocks.micros().instant().plus(shift).plus(extra);
      }
    };
  }

  @Bean
  @Primary
  Clock pinnedClock() {
    return clock();
  }
}
