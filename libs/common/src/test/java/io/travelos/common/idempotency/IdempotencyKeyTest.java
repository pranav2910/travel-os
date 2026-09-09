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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "trip_1:create-order:1",
        "trip_1:CREATE-ORDER",
        "trip_1:CREATE-ORDER:one",
        ":CREATE-ORDER:1",
        "trip 1:CREATE-ORDER:1",
        "trip_1:CREATE-ORDER:1:extra"
      })
  void rejectsMalformedKeys(String value) {
    assertThatThrownBy(() -> IdempotencyKey.parse(value))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
