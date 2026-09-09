package io.travelos.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-09T20:11:52Z"), ZoneOffset.UTC);

  @Test
  void createFillsInIdentityAndTime() {
    EventEnvelope event =
        EventEnvelope.create(
            "travel.trip.created",
            1,
            TenantId.of("acme"),
            "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV",
            null,
            "travel-core",
            Map.of("tripId", "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV"),
            CLOCK);

    assertThat(Ids.isValid(IdPrefix.EVENT, event.eventId())).isTrue();
    assertThat(event.occurredAt()).isEqualTo(CLOCK.instant());
    assertThat(event.topic()).isEqualTo(Topics.TRIP);
    assertThat(event.partitionKey()).isEqualTo("trip_01ARZ3NDEKTSV4RRFFQ69G5FAV");
    assertThat(event.causationId()).isNull();
  }

  @Test
  void dataIsDefensivelyCopiedAndImmutable() {
    Map<String, Object> data = new HashMap<>();
    data.put("tripId", "trip_1");
    EventEnvelope event = envelope("travel.trip.created", data);
    data.put("injected", true);

    assertThat(event.data()).containsOnlyKeys("tripId");
    assertThatThrownBy(() -> event.data().put("x", 1))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsMalformedEnvelopes() {
    assertThatThrownBy(() -> envelope("Travel.Trip.Created", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> envelope("travel.payments.captured", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unregistered");
    assertThatThrownBy(
            () ->
                new EventEnvelope(
                    "ord_01ARZ3NDEKTSV4RRFFQ69G5FAV",
                    "travel.trip.created",
                    1,
                    CLOCK.instant(),
                    "acme",
                    "trip_1",
                    null,
                    "travel-core",
                    Map.of()))
        .as("event id must carry the evt_ prefix")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new EventEnvelope(
                    Ids.newId(IdPrefix.EVENT),
                    "travel.trip.created",
                    0,
                    CLOCK.instant(),
                    "acme",
                    "trip_1",
                    null,
                    "travel-core",
                    Map.of()))
        .as("version 0")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new EventEnvelope(
                    Ids.newId(IdPrefix.EVENT),
                    "travel.trip.created",
                    1,
                    CLOCK.instant(),
                    "Acme Corp",
                    "trip_1",
                    null,
                    "travel-core",
                    Map.of()))
        .as("tenant slug")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new EventEnvelope(
                    Ids.newId(IdPrefix.EVENT),
                    "travel.trip.created",
                    1,
                    CLOCK.instant(),
                    "acme",
                    "trip_1",
                    null,
                    "TravelCore",
                    Map.of()))
        .as("producer")
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static EventEnvelope envelope(String type, Map<String, Object> data) {
    return EventEnvelope.create(
        type, 1, TenantId.of("acme"), "trip_1", "cmd_1", "travel-core", data, CLOCK);
  }
}
