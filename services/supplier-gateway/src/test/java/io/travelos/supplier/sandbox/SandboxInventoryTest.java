package io.travelos.supplier.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.travelos.contracts.common.v1.Cabin;
import io.travelos.supplier.AirSupplier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxInventoryTest {

  private static final LocalDate OUT = LocalDate.of(2026, 10, 6);
  private static final LocalDate BACK = LocalDate.of(2026, 10, 7);

  @Test
  void sameInputsSameInventory() {
    List<SandboxInventory.Schedule> a =
        SandboxInventory.schedules("BOS", "SEA", OUT, BACK, EnumSet.of(Cabin.ECONOMY));
    List<SandboxInventory.Schedule> b =
        SandboxInventory.schedules("BOS", "SEA", OUT, BACK, EnumSet.of(Cabin.ECONOMY));
    assertThat(a).isEqualTo(b).isNotEmpty();
    assertThat(a).extracting(SandboxInventory.Schedule::slot).doesNotHaveDuplicates();
  }

  @Test
  void roundTripsPairOutboundWithInboundOnTheSameCarrier() {
    for (SandboxInventory.Schedule s :
        SandboxInventory.schedules("BOS", "SEA", OUT, BACK, EnumSet.of(Cabin.ECONOMY))) {
      assertThat(s.outbound().getFirst().origin()).isEqualTo("BOS");
      assertThat(s.outbound().getLast().destination()).isEqualTo("SEA");
      assertThat(s.inbound().getFirst().origin()).isEqualTo("SEA");
      assertThat(s.inbound().getLast().destination()).isEqualTo("BOS");
      assertThat(s.inbound()).allSatisfy(leg -> assertThat(leg.carrier()).isEqualTo(s.carrier()));
      assertThat(s.fareMinor()).isPositive();
      assertThat(s.outbound().getFirst().departure()).isBefore(s.outbound().getLast().arrival());
    }
  }

  @Test
  void includesOneStopOptionsThatAreCheaper() {
    List<SandboxInventory.Schedule> all =
        SandboxInventory.schedules("BOS", "SEA", OUT, null, EnumSet.of(Cabin.ECONOMY));
    List<SandboxInventory.Schedule> oneStops =
        all.stream().filter(s -> s.outbound().size() == 2).toList();
    assertThat(oneStops).hasSize(2);
    for (SandboxInventory.Schedule stop : oneStops) {
      long cheapestNonstopSameCarrier =
          all.stream()
              .filter(s -> s.outbound().size() == 1 && s.carrier().equals(stop.carrier()))
              .mapToLong(SandboxInventory.Schedule::fareMinor)
              .min()
              .orElseThrow();
      assertThat(stop.fareMinor()).isLessThan(cheapestNonstopSameCarrier);
      assertThat(stop.outbound().get(0).arrival()).isBefore(stop.outbound().get(1).departure());
    }
  }

  @Test
  void businessCostsMoreThanEconomy() {
    long economy =
        SandboxInventory.schedules("BOS", "SEA", OUT, null, EnumSet.of(Cabin.ECONOMY))
            .getFirst()
            .fareMinor();
    long business =
        SandboxInventory.schedules("BOS", "SEA", OUT, null, EnumSet.of(Cabin.BUSINESS))
            .getFirst()
            .fareMinor();
    assertThat(business).isGreaterThan(economy * 2);
  }

  @Test
  void offerIdsRoundTripAndRejectGarbage() {
    SandboxOfferId id =
        new SandboxOfferId(
            "BOS",
            "SEA",
            OUT,
            BACK,
            7,
            "ECONOMY",
            Instant.parse("2026-09-09T20:00:00Z").getEpochSecond());
    String encoded = id.encode();
    assertThat(encoded).startsWith("SBX-");
    assertThat(SandboxOfferId.decode(encoded)).isEqualTo(id);
    assertThat(
            SandboxOfferId.decode(
                    new SandboxOfferId("BOS", "SEA", OUT, null, 1, "BUSINESS", 1).encode())
                .inboundDate())
        .isNull();
    assertThatThrownBy(() -> SandboxOfferId.decode("AMADEUS-123"))
        .isInstanceOf(AirSupplier.SupplierException.class)
        .hasMessageContaining("not a sandbox offer id");
    assertThatThrownBy(() -> SandboxOfferId.decode("SBX-!!!"))
        .isInstanceOf(AirSupplier.SupplierException.class);
  }
}
