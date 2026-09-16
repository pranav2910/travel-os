package io.travelos.context.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.model.DemandCandidate;
import io.travelos.context.model.DemandStatus;
import io.travelos.context.model.DemandTransition;
import io.travelos.context.model.SourceRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class DemandRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final JdbcClient jdbc;

  public DemandRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(DemandCandidate c) {
    jdbc.sql(
            """
            INSERT INTO demand_candidate (candidate_id, tenant_id, traveler_id, status, origin, destination, start_date,
              end_date, time_zone, window_start, window_end, purpose, missing, review_reasons, sources, rules_version,
              explanation, trip_id, conversion_key, primary_key, version, created_at, updated_at)
            VALUES (:id, :tenant, :traveler, :status, :origin, :destination, :start, :end, :zone, :wstart, :wend, :purpose,
              CAST(:missing AS jsonb), CAST(:reasons AS jsonb), CAST(:sources AS jsonb), :rules, :explanation, :trip, :ckey,
              :pkey, 0, :now, :now)
            """)
        .param("id", c.candidateId())
        .param("tenant", c.tenant().value())
        .param("traveler", c.travelerId())
        .param("status", c.status().name())
        .param("origin", c.origin())
        .param("destination", c.destination())
        .param("start", Rows.date(c.startDate()))
        .param("end", Rows.date(c.endDate()))
        .param("zone", c.timeZone())
        .param("wstart", Rows.ts(c.windowStart()))
        .param("wend", Rows.ts(c.windowEnd()))
        .param("purpose", c.purpose())
        .param("missing", JSON.writeValueAsString(c.missing()))
        .param("reasons", JSON.writeValueAsString(c.reviewReasons()))
        .param(
            "sources", JSON.writeValueAsString(c.sources().stream().map(SourceRef::toMap).toList()))
        .param("rules", c.rulesVersion())
        .param("explanation", c.explanation())
        .param("trip", c.tripId())
        .param("ckey", c.conversionKey())
        .param("pkey", c.primaryKey())
        .param("now", Rows.ts(c.createdAt()))
        .update();
  }

  /** Optimistic update: every field a rule or a person may change, guarded by the version. */
  public boolean update(DemandCandidate c, long expectedVersion) {
    return jdbc.sql(
                """
                UPDATE demand_candidate SET status = :status, origin = :origin, destination = :destination, start_date = :start,
                  end_date = :end, time_zone = :zone, window_start = :wstart, window_end = :wend, purpose = :purpose,
                  missing = CAST(:missing AS jsonb), review_reasons = CAST(:reasons AS jsonb), sources = CAST(:sources AS jsonb),
                  rules_version = :rules, explanation = :explanation, trip_id = :trip, conversion_key = :ckey,
                  version = :next, updated_at = :now
                WHERE candidate_id = :id AND tenant_id = :tenant AND version = :expected
                """)
            .param("status", c.status().name())
            .param("origin", c.origin())
            .param("destination", c.destination())
            .param("start", Rows.date(c.startDate()))
            .param("end", Rows.date(c.endDate()))
            .param("zone", c.timeZone())
            .param("wstart", Rows.ts(c.windowStart()))
            .param("wend", Rows.ts(c.windowEnd()))
            .param("purpose", c.purpose())
            .param("missing", JSON.writeValueAsString(c.missing()))
            .param("reasons", JSON.writeValueAsString(c.reviewReasons()))
            .param(
                "sources",
                JSON.writeValueAsString(c.sources().stream().map(SourceRef::toMap).toList()))
            .param("rules", c.rulesVersion())
            .param("explanation", c.explanation())
            .param("trip", c.tripId())
            .param("ckey", c.conversionKey())
            .param("next", expectedVersion + 1)
            .param("now", Rows.ts(c.updatedAt()))
            .param("id", c.candidateId())
            .param("tenant", c.tenant().value())
            .param("expected", expectedVersion)
            .update()
        == 1;
  }

  public Optional<DemandCandidate> find(TenantId tenant, String candidateId) {
    return jdbc.sql("SELECT * FROM demand_candidate WHERE tenant_id = :t AND candidate_id = :id")
        .param("t", tenant.value())
        .param("id", candidateId)
        .query(DemandRepository::map)
        .optional();
  }

  /** Row lock: conversion and source updates of one candidate are serialized. */
  public Optional<DemandCandidate> lock(TenantId tenant, String candidateId) {
    return jdbc.sql(
            "SELECT * FROM demand_candidate WHERE tenant_id = :t AND candidate_id = :id FOR UPDATE")
        .param("t", tenant.value())
        .param("id", candidateId)
        .query(DemandRepository::map)
        .optional();
  }

  public Optional<DemandCandidate> findByPrimaryKey(TenantId tenant, String primaryKey) {
    return jdbc.sql(
            "SELECT * FROM demand_candidate WHERE tenant_id = :t AND primary_key = :k FOR UPDATE")
        .param("t", tenant.value())
        .param("k", primaryKey)
        .query(DemandRepository::map)
        .optional();
  }

  /** Open candidates of one traveler in one city: the correlation search space. */
  public List<DemandCandidate> openFor(TenantId tenant, String travelerId, String destination) {
    return jdbc.sql(
            """
            SELECT * FROM demand_candidate WHERE tenant_id = :t AND traveler_id = :e AND destination = :d
              AND status IN ('NEEDS_REVIEW', 'ACTIONABLE') ORDER BY created_at FOR UPDATE
            """)
        .param("t", tenant.value())
        .param("e", travelerId)
        .param("d", destination)
        .query(DemandRepository::map)
        .list();
  }

  public List<DemandCandidate> openForTraveler(TenantId tenant, String travelerId) {
    return jdbc.sql(
            """
            SELECT * FROM demand_candidate WHERE tenant_id = :t AND traveler_id = :e
              AND status IN ('NEEDS_REVIEW', 'ACTIONABLE') ORDER BY created_at FOR UPDATE
            """)
        .param("t", tenant.value())
        .param("e", travelerId)
        .query(DemandRepository::map)
        .list();
  }

  public List<DemandCandidate> list(
      TenantId tenant, @Nullable String travelerId, @Nullable DemandStatus status, int limit) {
    return jdbc.sql(
            """
            SELECT * FROM demand_candidate WHERE tenant_id = :t
              AND (CAST(:e AS VARCHAR) IS NULL OR traveler_id = CAST(:e AS VARCHAR))
              AND (CAST(:s AS VARCHAR) IS NULL OR status = CAST(:s AS VARCHAR))
            ORDER BY updated_at DESC LIMIT :n
            """)
        .param("t", tenant.value())
        .param("e", travelerId)
        .param("s", status == null ? null : status.name())
        .param("n", limit)
        .query(DemandRepository::map)
        .list();
  }

  public void transition(
      String candidateId,
      TenantId tenant,
      @Nullable DemandStatus from,
      DemandStatus to,
      String reason,
      @Nullable String detail,
      String actor,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO demand_transition (candidate_id, tenant_id, from_status, to_status, reason, detail, actor, occurred_at)
            VALUES (:id, :t, :from, :to, :reason, :detail, :actor, :now)
            """)
        .param("id", candidateId)
        .param("t", tenant.value())
        .param("from", from == null ? null : from.name())
        .param("to", to.name())
        .param("reason", reason)
        .param(
            "detail", detail == null ? null : detail.substring(0, Math.min(2000, detail.length())))
        .param("actor", actor)
        .param("now", Rows.ts(now))
        .update();
  }

  public List<DemandTransition> transitions(TenantId tenant, String candidateId) {
    return jdbc.sql(
            "SELECT * FROM demand_transition WHERE tenant_id = :t AND candidate_id = :id ORDER BY id")
        .param("t", tenant.value())
        .param("id", candidateId)
        .query(
            (rs, i) ->
                new DemandTransition(
                    rs.getLong("id"),
                    rs.getString("candidate_id"),
                    rs.getString("from_status") == null
                        ? null
                        : DemandStatus.valueOf(rs.getString("from_status")),
                    DemandStatus.valueOf(rs.getString("to_status")),
                    rs.getString("reason"),
                    rs.getString("detail"),
                    rs.getString("actor"),
                    Rows.instant(rs, "occurred_at")))
        .list();
  }

  @SuppressWarnings("unchecked")
  static DemandCandidate map(ResultSet rs, int i) throws SQLException {
    List<Map<String, Object>> sourceMaps = JSON.readValue(rs.getString("sources"), List.class);
    List<SourceRef> sources = new ArrayList<>();
    for (Map<String, Object> m : sourceMaps) {
      sources.add(
          new SourceRef(
              String.valueOf(m.get("connectorId")),
              ConnectorKind.valueOf(String.valueOf(m.get("kind"))),
              String.valueOf(m.get("sourceId")),
              ((Number) m.get("revision")).longValue(),
              SourceRef.Role.valueOf(String.valueOf(m.get("role")))));
    }
    return new DemandCandidate(
        rs.getString("candidate_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("traveler_id"),
        DemandStatus.valueOf(rs.getString("status")),
        rs.getString("origin"),
        rs.getString("destination"),
        Rows.date(rs, "start_date"),
        Rows.date(rs, "end_date"),
        rs.getString("time_zone"),
        Rows.instant(rs, "window_start"),
        Rows.instant(rs, "window_end"),
        rs.getString("purpose"),
        JSON.readValue(rs.getString("missing"), List.class),
        JSON.readValue(rs.getString("review_reasons"), List.class),
        sources,
        rs.getString("rules_version"),
        rs.getString("explanation"),
        rs.getString("trip_id"),
        rs.getString("conversion_key"),
        rs.getString("primary_key"),
        rs.getLong("version"),
        Rows.instant(rs, "created_at"),
        Rows.instant(rs, "updated_at"));
  }
}
