package io.travelos.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.travelos.common.tenant.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventCodecTest {

  private final EventCodec codec = new EventCodec();

  @Test
  void roundTripsIncludingNestedData() {
    EventEnvelope original =
        EventEnvelope.create(
            "travel.order.confirmed",
            1,
            TenantId.of("acme"),
            "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV",
            "cmd_01ARZ3NDEKTSV4RRFFQ69G5FAV",
            "order",
            Map.of(
                "orderId", "ord_01ARZ3NDEKTSV4RRFFQ69G5FAV",
                "total", Map.of("currency", "USD", "amountMinor", 82000),
                "items", List.of(Map.of("type", "AIR"))),
            Clock.fixed(Instant.parse("2026-09-09T20:11:52Z"), ZoneOffset.UTC));

    String json = codec.toJson(original);
    assertThat(json).contains("\"occurredAt\":\"2026-09-09T20:11:52Z\"");
    assertThat(codec.fromJson(json)).isEqualTo(original);
  }

  @Test
  void omitsCausationIdWhenAbsent() {
    EventEnvelope event =
        EventEnvelope.create(
            "travel.trip.created",
            1,
            TenantId.of("acme"),
            "trip_1",
            null,
            "travel-core",
            Map.of(),
            Clock.systemUTC());
    assertThat(codec.toJson(event)).doesNotContain("causationId");
    assertThat(codec.fromJson(codec.toJson(event)).causationId()).isNull();
  }

  @Test
  void rejectsUnknownTopLevelFields() {
    String json =
        """
        {"eventId":"evt_01ARZ3NDEKTSV4RRFFQ69G5FAV","eventType":"travel.trip.created",
         "eventVersion":1,"occurredAt":"2026-09-09T20:11:52Z","tenantId":"acme",
         "correlationId":"trip_1","producer":"travel-core","data":{},"smuggled":true}
        """;
    assertThatThrownBy(() -> codec.fromJson(json)).isInstanceOf(RuntimeException.class);
  }

  @Test
  void validatesOnRead() {
    String json =
        """
        {"eventId":"evt_01ARZ3NDEKTSV4RRFFQ69G5FAV","eventType":"travel.nope.created",
         "eventVersion":1,"occurredAt":"2026-09-09T20:11:52Z","tenantId":"acme",
         "correlationId":"trip_1","producer":"travel-core","data":{}}
        """;
    assertThatThrownBy(() -> codec.fromJson(json)).hasMessageContaining("unregistered");
  }
}
