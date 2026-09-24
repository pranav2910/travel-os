package io.travelos.travelcore.trip;

import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON columns of the trip row: quoted alternatives and search preferences. */
final class TripJson {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private TripJson() {}

  static @Nullable String alternatives(List<TripAlternative> alternatives) {
    return alternatives == null || alternatives.isEmpty()
        ? null
        : JSON.writeValueAsString(alternatives);
  }

  static List<TripAlternative> alternatives(@Nullable String json) {
    return json == null || json.isBlank()
        ? List.of()
        : JSON.readValue(json, new TypeReference<List<TripAlternative>>() {});
  }

  static @Nullable String preferences(TravelIntent.@Nullable SearchPreferences p) {
    return p == null ? null : JSON.writeValueAsString(p);
  }

  static TravelIntent.@Nullable SearchPreferences preferences(@Nullable String json) {
    return json == null || json.isBlank()
        ? null
        : JSON.readValue(json, TravelIntent.SearchPreferences.class);
  }
}
