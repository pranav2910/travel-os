package io.travelos.common.ids;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdsTest {

  @Test
  void newIdHasPrefixAndUlid() {
    String id = Ids.newId(IdPrefix.TRIP);
    assertThat(id).startsWith("trip_").hasSize(5 + 26);
    assertThat(Ids.isValid(IdPrefix.TRIP, id)).isTrue();
    assertThat(Ids.isValid(IdPrefix.ORDER, id)).isFalse();
  }

  @Test
  void idsAreMonotonicWithinAProcess() {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 1_000; i++) {
      ids.add(Ids.newId(IdPrefix.EVENT));
    }
    List<String> sorted = new ArrayList<>(ids);
    sorted.sort(String::compareTo);
    assertThat(ids).containsExactlyElementsOf(sorted);
    assertThat(ids).doesNotHaveDuplicates();
  }

  @Test
  void prefixOfRecognisesEveryPrefix() {
    for (IdPrefix prefix : IdPrefix.values()) {
      assertThat(Ids.prefixOf(Ids.newId(prefix))).contains(prefix);
    }
    assertThat(Ids.prefixOf("nope_01ARZ3NDEKTSV4RRFFQ69G5FAV")).isEmpty();
    assertThat(Ids.prefixOf("trip_tooShort")).isEmpty();
    assertThat(Ids.prefixOf(null)).isEmpty();
  }

  @Test
  void rejectsNonCrockfordCharacters() {
    // I, L, O and U are excluded from Crockford base32.
    assertThat(Ids.isValid(IdPrefix.TRIP, "trip_01ARZ3NDEKTSV4RRFFQ69G5FAI")).isFalse();
    assertThat(Ids.isValid(IdPrefix.TRIP, "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV")).isTrue();
  }

  @Test
  void requireFailsLoudly() {
    assertThatThrownBy(() -> Ids.require(IdPrefix.ORDER, "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ord_<ULID>");
  }
}
