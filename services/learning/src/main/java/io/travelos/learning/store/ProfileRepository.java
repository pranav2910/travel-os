package io.travelos.learning.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.learning.model.EvidenceClass;
import io.travelos.learning.model.Profile;
import io.travelos.learning.model.ProfileStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileRepository {
  private final JdbcClient jdbc;

  public ProfileRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Profile p) {
    jdbc.sql(
            """
            INSERT INTO profile (profile_id, tenant_id, status, algorithm_version, evidence_class, input_cutoff, window_start,
              parameters, requested_by, created_at)
            VALUES (:id, :t, :status, :algo, :class, :cutoff, :start, CAST(:params AS jsonb), :by, :now)
            """)
        .param("id", p.profileId())
        .param("t", p.tenant().value())
        .param("status", p.status().name())
        .param("algo", p.algorithmVersion())
        .param("class", p.evidenceClass().name())
        .param("cutoff", Rows.ts(p.inputCutoff()))
        .param("start", Rows.ts(p.windowStart()))
        .param("params", Rows.json(p.parameters()))
        .param("by", p.requestedBy())
        .param("now", Rows.ts(p.createdAt()))
        .update();
  }

  public Optional<Profile> find(TenantId tenant, String profileId) {
    return jdbc.sql("SELECT * FROM profile WHERE tenant_id = :t AND profile_id = :id")
        .param("t", tenant.value())
        .param("id", profileId)
        .query(ProfileRepository::map)
        .optional();
  }

  public Optional<Profile> lock(TenantId tenant, String profileId) {
    return jdbc.sql("SELECT * FROM profile WHERE tenant_id = :t AND profile_id = :id FOR UPDATE")
        .param("t", tenant.value())
        .param("id", profileId)
        .query(ProfileRepository::map)
        .optional();
  }

  public List<Profile> list(TenantId tenant) {
    return jdbc.sql("SELECT * FROM profile WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 200")
        .param("t", tenant.value())
        .query(ProfileRepository::map)
        .list();
  }

  public Map<String, Long> countByStatus(TenantId tenant) {
    Map<String, Long> out = new java.util.LinkedHashMap<>();
    jdbc.sql("SELECT status, COUNT(*) AS n FROM profile WHERE tenant_id = :t GROUP BY status")
        .param("t", tenant.value())
        .query((rs, i) -> out.put(rs.getString("status"), rs.getLong("n")))
        .list();
    return out;
  }

  public void built(
      TenantId tenant,
      String profileId,
      String fingerprint,
      int hard,
      int feedback,
      int keys,
      Map<String, Object> body,
      Instant now) {
    jdbc.sql(
            """
            UPDATE profile SET status = 'BUILT', dataset_fingerprint = :fp, hard_outcomes = :hard, feedback_outcomes = :fb,
              supplier_keys = :keys, body = CAST(:body AS jsonb), built_at = :now
            WHERE tenant_id = :t AND profile_id = :id
            """)
        .param("fp", fingerprint)
        .param("hard", hard)
        .param("fb", feedback)
        .param("keys", keys)
        .param("body", Rows.json(body))
        .param("now", Rows.ts(now))
        .param("t", tenant.value())
        .param("id", profileId)
        .update();
  }

  public void evaluated(
      TenantId tenant,
      String profileId,
      ProfileStatus status,
      String verdict,
      Map<String, Object> evaluation,
      Instant now) {
    jdbc.sql(
            """
            UPDATE profile SET status = :status, verdict = :verdict, evaluation = CAST(:eval AS jsonb), evaluated_at = :now,
              finished_at = :now
            WHERE tenant_id = :t AND profile_id = :id
            """)
        .param("status", status.name())
        .param("verdict", verdict)
        .param("eval", Rows.json(evaluation))
        .param("now", Rows.ts(now))
        .param("t", tenant.value())
        .param("id", profileId)
        .update();
  }

  public void failed(
      TenantId tenant, String profileId, String code, @Nullable String message, Instant now) {
    jdbc.sql(
            """
            UPDATE profile SET status = 'FAILED', failure_code = :code, failure_message = :message, finished_at = :now
            WHERE tenant_id = :t AND profile_id = :id
            """)
        .param("code", code)
        .param(
            "message",
            message == null ? null : message.substring(0, Math.min(2000, message.length())))
        .param("now", Rows.ts(now))
        .param("t", tenant.value())
        .param("id", profileId)
        .update();
  }

  static Profile map(ResultSet rs, int i) throws SQLException {
    return new Profile(
        rs.getString("profile_id"),
        TenantId.of(rs.getString("tenant_id")),
        ProfileStatus.valueOf(rs.getString("status")),
        rs.getString("algorithm_version"),
        EvidenceClass.valueOf(rs.getString("evidence_class")),
        Rows.instantOrThrow(rs, "input_cutoff"),
        Rows.instantOrThrow(rs, "window_start"),
        Rows.map(rs, "parameters"),
        rs.getString("dataset_fingerprint"),
        rs.getInt("hard_outcomes"),
        rs.getInt("feedback_outcomes"),
        rs.getInt("supplier_keys"),
        Rows.mapOrNull(rs, "body"),
        Rows.mapOrNull(rs, "evaluation"),
        rs.getString("verdict"),
        rs.getString("failure_code"),
        rs.getString("failure_message"),
        rs.getString("requested_by"),
        Rows.instantOrThrow(rs, "created_at"),
        Rows.instant(rs, "built_at"),
        Rows.instant(rs, "evaluated_at"),
        Rows.instant(rs, "finished_at"));
  }
}
