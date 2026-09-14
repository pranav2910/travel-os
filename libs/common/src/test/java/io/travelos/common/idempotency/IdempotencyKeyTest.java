package io.travelos.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyKeyTest {

  @Test
  void buildsAndDecomposes() {
    IdempotencyKey key = IdempotencyKey.of("trip_01ARZ3NDEKTSV4RRFFQ69G5FAV", "CREATE-ORDER", 1);
    assertThat(key.value()).isEqualTo("trip_01ARZ3NDEKTSV4RRFFQ69G5FAV:CREATE-ORDER:1");
    assertThat(key.scope()).isEqualTo("trip_01ARZ3NDEKTSV4RRFFQ69G5FAV");
    assertThat(key.command()).isEqualTo("CREATE-ORDER");
    assertThat(key.attempt()).isEqualTo(1);
    assertThat(IdempotencyKey.parse(key.value())).isEqualTo(key);
  }

  @Test
  void scopeMayBeAColonSeparatedBusinessPath() {
    IdempotencyKey key =
        IdempotencyKey.parse(
            "TRIP:trip_01ARZ3NDEKTSV4RRFFQ69G5FAV:DISRUPTION:dsr_01ARZ3NDEKTSV4RRFFQ69G5FAW:CHANGE:1");
    assertThat(key.scope())
        .isEqualTo(
            "TRIP:trip_01ARZ3NDEKTSV4RRFFQ69G5FAV:DISRUPTION:dsr_01ARZ3NDEKTSV4RRFFQ69G5FAW");
    assertThat(key.command()).isEqualTo("CHANGE");
    assertThat(key.attempt()).isEqualTo(1);
  }

  @Test
  void keysLongerThanTheColumnAreRejected() {
    String scope = "x".repeat(64) + ":" + "y".repeat(64) + ":" + "z".repeat(64);
    assertThatThrownBy(() -> IdempotencyKey.parse(scope + ":CHANGE:1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("longer than 200");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "trip_1:create-order:1",
        "trip_1:CREATE-ORDER",
        "trip_1:CREATE-ORDER:one",
        ":CREATE-ORDER:1",
        "trip 1:CREATE-ORDER:1",
        "trip_1:CREATE-ORDER:1:extra",
        "TRIP::DISRUPTION:dsr_1:CHANGE:1",
        "TRIP:trip_1:change:1"
      })
  void rejectsMalformedKeys(String value) {
    assertThatThrownBy(() -> IdempotencyKey.parse(value))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
