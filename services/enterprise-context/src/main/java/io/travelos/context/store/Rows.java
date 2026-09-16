package io.travelos.context.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

final class Rows {
  private Rows() {}

  static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  static @Nullable Timestamp ts(@Nullable Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  static @Nullable LocalDate date(ResultSet rs, String column) throws SQLException {
    java.sql.Date d = rs.getDate(column);
    return d == null ? null : d.toLocalDate();
  }

  static java.sql.@Nullable Date date(@Nullable LocalDate date) {
    return date == null ? null : java.sql.Date.valueOf(date);
  }
}
