package io.travelos.learning.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.learning.model.ActivationRecord;
import io.travelos.learning.model.LearningMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ActivationHistoryRepository {
  private final JdbcClient jdbc;

  public ActivationHistoryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void append(
      TenantId tenant,
      String action,
      @Nullable String profileId,
      @Nullable String previousProfileId,
      LearningMode mode,
      @Nullable LearningMode previousMode,
      long configVersion,
      String actor,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO activation_history (tenant_id, action, profile_id, previous_profile_id, mode, previous_mode,
              config_version, actor, occurred_at)
            VALUES (:t, :action, :p, :prev, :mode, :prevMode, :v, :actor, :now)
            """)
        .param("t", tenant.value())
        .param("action", action)
        .param("p", profileId)
        .param("prev", previousProfileId)
        .param("mode", mode.name())
        .param("prevMode", previousMode == null ? null : previousMode.name())
        .param("v", configVersion)
        .param("actor", actor)
        .param("now", Rows.ts(now))
        .update();
  }

  public List<ActivationRecord> list(TenantId tenant) {
    return jdbc.sql(
            "SELECT * FROM activation_history WHERE tenant_id = :t ORDER BY id DESC LIMIT 100")
        .param("t", tenant.value())
        .query(ActivationHistoryRepository::map)
        .list();
  }

  static ActivationRecord map(ResultSet rs, int i) throws SQLException {
    String prev = rs.getString("previous_mode");
    return new ActivationRecord(
        rs.getLong("id"),
        rs.getString("action"),
        rs.getString("profile_id"),
        rs.getString("previous_profile_id"),
        LearningMode.valueOf(rs.getString("mode")),
        prev == null ? null : LearningMode.valueOf(prev),
        rs.getLong("config_version"),
        rs.getString("actor"),
        Rows.instantOrThrow(rs, "occurred_at"));
  }
}
