package io.travelos.learning.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

final class Rows {
  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> MAPS = new TypeReference<>() {};

  private Rows() {}

  static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  static Instant instantOrThrow(ResultSet rs, String column) throws SQLException {
    Instant i = instant(rs, column);
    if (i == null) {
      throw new SQLException(column + " is null");
    }
    return i;
  }

  static @Nullable Timestamp ts(@Nullable Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  static Map<String, Object> map(ResultSet rs, String column) throws SQLException {
    String s = rs.getString(column);
    return s == null ? Map.of() : JSON.readValue(s, MAP);
  }

  static @Nullable Map<String, Object> mapOrNull(ResultSet rs, String column) throws SQLException {
    String s = rs.getString(column);
    return s == null ? null : JSON.readValue(s, MAP);
  }

  static List<String> strings(ResultSet rs, String column) throws SQLException {
    String s = rs.getString(column);
    return s == null ? List.of() : JSON.readValue(s, STRINGS);
  }

  static List<Map<String, Object>> maps(ResultSet rs, String column) throws SQLException {
    String s = rs.getString(column);
    return s == null ? List.of() : JSON.readValue(s, MAPS);
  }

  static String json(@Nullable Object value) {
    return JSON.writeValueAsString(value == null ? Map.of() : value);
  }
}
