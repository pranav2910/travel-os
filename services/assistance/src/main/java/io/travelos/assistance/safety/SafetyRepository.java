package io.travelos.assistance.safety;

import io.travelos.assistance.safety.SafetyRecords.Advisory;
import io.travelos.assistance.safety.SafetyRecords.Affected;
import io.travelos.assistance.safety.SafetyRecords.Checkin;
import io.travelos.assistance.safety.SafetyRecords.CheckinStatus;
import io.travelos.assistance.safety.SafetyRecords.Severity;
import io.travelos.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SafetyRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
  private final JdbcClient jdbc;

  public SafetyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Advisory a) {
    jdbc.sql(
            """
            INSERT INTO safety_advisory (advisory_id, tenant_id, title, severity, countries, cities, starts_at, ends_at, text, source,
              active, issued_by, issued_at, checkin_due_at)
            VALUES (:id, :tenant, :title, :severity, CAST(:countries AS jsonb), CAST(:cities AS jsonb), :starts, :ends, :text, :source,
              TRUE, :by, :at, :due)
            """)
        .param("id", a.advisoryId())
        .param("tenant", a.tenant().value())
        .param("title", a.title())
        .param("severity", a.severity().name())
        .param("countries", JSON.writeValueAsString(a.countries()))
        .param("cities", JSON.writeValueAsString(a.cities()))
        .param("starts", Timestamp.from(a.startsAt()))
        .param("ends", Timestamp.from(a.endsAt()))
        .param("text", a.text())
        .param("source", a.source())
        .param("by", a.issuedBy())
        .param("at", Timestamp.from(a.issuedAt()))
        .param("due", a.checkinDueAt() == null ? null : Timestamp.from(a.checkinDueAt()))
        .update();
  }

  public Optional<Advisory> find(TenantId tenant, String advisoryId) {
    return jdbc.sql("SELECT * FROM safety_advisory WHERE tenant_id = :t AND advisory_id = :id")
        .param("t", tenant.value())
        .param("id", advisoryId)
        .query(SafetyRepository::advisory)
        .optional();
  }

  public List<Advisory> list(TenantId tenant, boolean activeOnly) {
    return jdbc.sql(
            "SELECT * FROM safety_advisory WHERE tenant_id = :t"
                + (activeOnly ? " AND active" : "")
                + " ORDER BY issued_at DESC")
        .param("t", tenant.value())
        .query(SafetyRepository::advisory)
        .list();
  }

  public boolean close(TenantId tenant, String advisoryId) {
    return jdbc.sql(
                "UPDATE safety_advisory SET active = FALSE WHERE tenant_id = :t AND advisory_id = :id AND active")
            .param("t", tenant.value())
            .param("id", advisoryId)
            .update()
        == 1;
  }

  /** Advisories whose check-in deadline passed, for the sweep (locked). */
  public List<Advisory> checkinsDue(Instant now) {
    return jdbc.sql(
            "SELECT * FROM safety_advisory WHERE active AND checkin_due_at IS NOT NULL AND checkin_due_at <= :now FOR UPDATE SKIP LOCKED")
        .param("now", Timestamp.from(now))
        .query(SafetyRepository::advisory)
        .list();
  }

  public void markCheckinsSwept(String advisoryId) {
    jdbc.sql("UPDATE safety_advisory SET checkin_due_at = NULL WHERE advisory_id = :id")
        .param("id", advisoryId)
        .update();
  }

  public void insertAffected(Affected a) {
    jdbc.sql(
            """
            INSERT INTO safety_affected (advisory_id, tenant_id, traveler_id, trip_id, notified_at)
            VALUES (:a, :t, :traveler, :trip, :at) ON CONFLICT DO NOTHING
            """)
        .param("a", a.advisoryId())
        .param("t", a.tenant().value())
        .param("traveler", a.travelerId())
        .param("trip", a.tripId())
        .param("at", Timestamp.from(a.notifiedAt()))
        .update();
  }

  public void linkCase(String advisoryId, String travelerId, String caseId) {
    jdbc.sql("UPDATE safety_affected SET case_id = :c WHERE advisory_id = :a AND traveler_id = :tr")
        .param("c", caseId)
        .param("a", advisoryId)
        .param("tr", travelerId)
        .update();
  }

  public List<Affected> affected(TenantId tenant, String advisoryId) {
    return jdbc.sql(
            "SELECT * FROM safety_affected WHERE tenant_id = :t AND advisory_id = :a ORDER BY traveler_id, trip_id")
        .param("t", tenant.value())
        .param("a", advisoryId)
        .query(SafetyRepository::affected)
        .list();
  }

  public List<Advisory> affecting(TenantId tenant, String travelerId) {
    return jdbc.sql(
            "SELECT s.* FROM safety_advisory s JOIN safety_affected f ON f.advisory_id = s.advisory_id"
                + " WHERE s.tenant_id = :t AND f.traveler_id = :tr AND s.active ORDER BY s.issued_at DESC")
        .param("t", tenant.value())
        .param("tr", travelerId)
        .query(SafetyRepository::advisory)
        .list();
  }

  public void upsertCheckin(Checkin c) {
    jdbc.sql(
            """
            INSERT INTO safety_checkin (checkin_id, advisory_id, tenant_id, traveler_id, status, note, recorded_at)
            VALUES (:id, :a, :t, :tr, :s, :note, :at)
            ON CONFLICT (advisory_id, traveler_id) DO UPDATE SET status = EXCLUDED.status, note = EXCLUDED.note, recorded_at = EXCLUDED.recorded_at
            """)
        .param("id", c.checkinId())
        .param("a", c.advisoryId())
        .param("t", c.tenant().value())
        .param("tr", c.travelerId())
        .param("s", c.status().name())
        .param("note", c.note())
        .param("at", Timestamp.from(c.recordedAt()))
        .update();
  }

  public List<Checkin> checkins(TenantId tenant, String advisoryId) {
    return jdbc.sql(
            "SELECT * FROM safety_checkin WHERE tenant_id = :t AND advisory_id = :a ORDER BY recorded_at")
        .param("t", tenant.value())
        .param("a", advisoryId)
        .query(SafetyRepository::checkin)
        .list();
  }

  private static Advisory advisory(ResultSet rs, int i) throws SQLException {
    OffsetDateTime due = rs.getObject("checkin_due_at", OffsetDateTime.class);
    return new Advisory(
        rs.getString("advisory_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("title"),
        Severity.valueOf(rs.getString("severity")),
        JSON.readValue(rs.getString("countries"), STRINGS),
        JSON.readValue(rs.getString("cities"), STRINGS),
        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
        rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
        rs.getString("text"),
        rs.getString("source"),
        rs.getBoolean("active"),
        rs.getString("issued_by"),
        rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
        due == null ? null : due.toInstant());
  }

  private static Affected affected(ResultSet rs, int i) throws SQLException {
    return new Affected(
        rs.getString("advisory_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("traveler_id"),
        rs.getString("trip_id"),
        rs.getObject("notified_at", OffsetDateTime.class).toInstant(),
        rs.getString("case_id"));
  }

  private static Checkin checkin(ResultSet rs, int i) throws SQLException {
    return new Checkin(
        rs.getString("checkin_id"),
        rs.getString("advisory_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("traveler_id"),
        CheckinStatus.valueOf(rs.getString("status")),
        rs.getString("note"),
        rs.getObject("recorded_at", OffsetDateTime.class).toInstant());
  }

  static @Nullable String blank(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
