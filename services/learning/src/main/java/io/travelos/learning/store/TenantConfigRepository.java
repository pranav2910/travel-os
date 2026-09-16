package io.travelos.learning.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.learning.model.LearningMode;
import io.travelos.learning.model.TenantConfig;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TenantConfigRepository {
  private final JdbcClient jdbc;

  public TenantConfigRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<TenantConfig> find(TenantId tenant) {
    return jdbc.sql("SELECT * FROM tenant_config WHERE tenant_id = :t")
        .param("t", tenant.value())
        .query(TenantConfigRepository::map)
        .optional();
  }

  /** The row, created with the default mode when absent, locked for the caller's transaction. */
  public TenantConfig lock(TenantId tenant, LearningMode defaultMode, Instant now) {
    jdbc.sql(
            """
            INSERT INTO tenant_config (tenant_id, mode, version, updated_by, updated_at)
            VALUES (:t, :mode, 0, 'service/learning', :now)
            ON CONFLICT (tenant_id) DO NOTHING
            """)
        .param("t", tenant.value())
        .param("mode", defaultMode.name())
        .param("now", Rows.ts(now))
        .update();
    return jdbc.sql("SELECT * FROM tenant_config WHERE tenant_id = :t FOR UPDATE")
        .param("t", tenant.value())
        .query(TenantConfigRepository::map)
        .single();
  }

  public TenantConfig update(
      TenantId tenant,
      LearningMode mode,
      @Nullable String active,
      @Nullable String previous,
      String by,
      Instant now) {
    return jdbc.sql(
            """
            UPDATE tenant_config SET mode = :mode, active_profile_id = :active, previous_profile_id = :previous,
              version = version + 1, updated_by = :by, updated_at = :now
            WHERE tenant_id = :t RETURNING *
            """)
        .param("mode", mode.name())
        .param("active", active)
        .param("previous", previous)
        .param("by", by)
        .param("now", Rows.ts(now))
        .param("t", tenant.value())
        .query(TenantConfigRepository::map)
        .single();
  }

  static TenantConfig map(ResultSet rs, int i) throws SQLException {
    return new TenantConfig(
        TenantId.of(rs.getString("tenant_id")),
        LearningMode.valueOf(rs.getString("mode")),
        rs.getString("active_profile_id"),
        rs.getString("previous_profile_id"),
        rs.getLong("version"),
        rs.getString("updated_by"),
        Rows.instantOrThrow(rs, "updated_at"));
  }
}
