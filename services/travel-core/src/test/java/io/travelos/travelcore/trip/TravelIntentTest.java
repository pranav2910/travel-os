package io.travelos.travelcore.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TravelIntentTest {

  private static final Instant T0 = Instant.parse("2026-10-06T10:00:00Z");

  @Test
  void roundTripIsValid() {
    TravelIntent intent =
        new TravelIntent(
            "BOS",
            "SEA",
            T0,
            T0.plusSeconds(6 * 3600),
            T0.plusSeconds(86400),
            T0.plusSeconds(2 * 86400),
            "customer meeting",
            true,
            1);
    assertThat(intent.isRoundTrip()).isTrue();
  }

  @Test
  void oneWayIsValid() {
    assertThat(
            new TravelIntent("BOS", "SEA", T0, T0.plusSeconds(3600), null, null, null, false, 1)
                .isRoundTrip())
        .isFalse();
  }

  @Test
  void rejectsNonsense() {
    assertThatThrownBy(
            () -> new TravelIntent("bos", "SEA", T0, T0.plusSeconds(1), null, null, null, false, 1))
        .hasMessageContaining("IATA");
    assertThatThrownBy(
            () -> new TravelIntent("BOS", "BOS", T0, T0.plusSeconds(1), null, null, null, false, 1))
        .hasMessageContaining("differ");
    assertThatThrownBy(() -> new TravelIntent("BOS", "SEA", T0, T0, null, null, null, false, 1))
        .hasMessageContaining("before");
    assertThatThrownBy(
            () ->
                new TravelIntent(
                    "BOS", "SEA", T0, T0.plusSeconds(1), T0.plusSeconds(2), null, null, false, 1))
        .hasMessageContaining("together");
    assertThatThrownBy(
            () ->
                new TravelIntent(
                    "BOS",
                    "SEA",
                    T0,
                    T0.plusSeconds(10),
                    T0.plusSeconds(5),
                    T0.plusSeconds(20),
                    null,
                    false,
                    1))
        .hasMessageContaining("returnAfter");
    assertThatThrownBy(
            () -> new TravelIntent("BOS", "SEA", T0, T0.plusSeconds(1), null, null, null, false, 0))
        .hasMessageContaining("travelers");
  }
}
