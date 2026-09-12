package io.travelos.travelcore.trip;

import io.travelos.common.identity.Principal;
import io.travelos.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AgentDecisionRepository {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private final JdbcClient jdbc;

  public AgentDecisionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts, or returns false when this model call was already ledgered (a retried activity). */
  public boolean insertIfAbsent(AgentDecision d) {
    AgentDecision.ModelCallEvidence c = d.call();
    int rows =
        jdbc.sql(
                """
                INSERT INTO agent_decision (decision_id, tenant_id, trip_id, agent, decision_type,
                  result, confidence, assumptions, detail, model_call_id, provider, model, prompt_id,
                  prompt_version, provider_request_id, input_tokens, output_tokens, cache_read_tokens,
                  latency_ms, cost_micros, occurred_at)
                VALUES (:decisionId, :tenantId, :tripId, :agent, :decisionType, :result, :confidence,
                  CAST(:assumptions AS jsonb), CAST(:detail AS jsonb), :callId, :provider, :model,
                  :promptId, :promptVersion, :providerRequestId, :inputTokens, :outputTokens,
                  :cacheReadTokens, :latencyMs, :costMicros, :occurredAt)
                ON CONFLICT (model_call_id) DO NOTHING
                """)
            .param("decisionId", d.decisionId())
            .param("tenantId", d.tenantId().value())
            .param("tripId", d.tripId())
            .param("agent", d.agent().id())
            .param("decisionType", d.decisionType())
            .param("result", d.result())
            .param("confidence", d.confidence())
            .param("assumptions", JSON.writeValueAsString(d.assumptions()))
            .param("detail", JSON.writeValueAsString(d.detail()))
            .param("callId", c == null ? null : c.callId())
            .param("provider", c == null ? null : c.provider())
            .param("model", c == null ? null : c.model())
            .param("promptId", c == null ? null : c.promptId())
            .param("promptVersion", c == null ? null : c.promptVersion())
            .param("providerRequestId", c == null ? null : c.providerRequestId())
            .param("inputTokens", c == null ? 0L : c.inputTokens())
            .param("outputTokens", c == null ? 0L : c.outputTokens())
            .param("cacheReadTokens", c == null ? 0L : c.cacheReadTokens())
            .param("latencyMs", c == null ? 0L : c.latencyMs())
            .param("costMicros", c == null ? 0L : c.costMicros())
            .param("occurredAt", ts(d.occurredAt()))
            .update();
    return rows == 1;
  }

  public List<AgentDecision> listForTrip(TenantId tenant, String tripId) {
    return jdbc.sql(
            """
            SELECT decision_id, tenant_id, trip_id, agent, decision_type, result, confidence,
              assumptions::text AS assumptions, detail::text AS detail, model_call_id, provider,
              model, prompt_id, prompt_version, provider_request_id, input_tokens, output_tokens,
              cache_read_tokens, latency_ms, cost_micros, occurred_at
            FROM agent_decision WHERE tenant_id = :tenantId AND trip_id = :tripId
            ORDER BY occurred_at, decision_id
            """)
        .param("tenantId", tenant.value())
        .param("tripId", tripId)
        .query(AgentDecisionRepository::map)
        .list();
  }

  private static AgentDecision map(ResultSet rs, int rowNum) throws SQLException {
    String callId = rs.getString("model_call_id");
    AgentDecision.ModelCallEvidence call =
        callId == null
            ? null
            : new AgentDecision.ModelCallEvidence(
                callId,
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("prompt_id"),
                rs.getInt("prompt_version"),
                rs.getString("provider_request_id"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("cache_read_tokens"),
                rs.getLong("latency_ms"),
                rs.getLong("cost_micros"),
                instant(rs, "occurred_at"));
    double confidence = rs.getDouble("confidence");
    return new AgentDecision(
        rs.getString("decision_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("trip_id"),
        Principal.parse(rs.getString("agent")),
        rs.getString("decision_type"),
        rs.getString("result"),
        rs.wasNull() ? null : confidence,
        JSON.readValue(rs.getString("assumptions"), STRINGS),
        JSON.readValue(rs.getString("detail"), OBJECT),
        call,
        instant(rs, "occurred_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value.toInstant();
  }

  private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
