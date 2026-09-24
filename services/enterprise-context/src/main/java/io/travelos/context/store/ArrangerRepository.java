package io.travelos.context.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.ArrangerGrant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ArrangerRepository {
  private final JdbcClient jdbc;

  public ArrangerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(ArrangerGrant g) {
    jdbc.sql(
            """
            INSERT INTO arranger_grant (tenant_id, grant_id, arranger_employee_id, scope_kind, scope_id, may_read_documents,
              granted_by, granted_at, expires_at, revoked_at)
            VALUES (:t, :id, :arranger, :scope, :scopeId, :docs, :by, :at, :expires, NULL)
            """)
        .param("t", g.tenant().value())
        .param("id", g.grantId())
        .param("arranger", g.arrangerEmployeeId())
        .param("scope", g.scope().name())
        .param("scopeId", g.scopeId())
        .param("docs", g.mayReadDocuments())
        .param("by", g.grantedBy())
        .param("at", Rows.ts(g.grantedAt()))
        .param("expires", Rows.ts(g.expiresAt()))
        .update();
  }

  public Optional<ArrangerGrant> find(TenantId tenant, String grantId) {
    return jdbc.sql("SELECT * FROM arranger_grant WHERE tenant_id = :t AND grant_id = :id")
        .param("t", tenant.value())
        .param("id", grantId)
        .query(ArrangerRepository::map)
        .optional();
  }

  public List<ArrangerGrant> byArranger(TenantId tenant, String arrangerEmployeeId) {
    return jdbc.sql(
            "SELECT * FROM arranger_grant WHERE tenant_id = :t AND arranger_employee_id = :a ORDER BY granted_at")
        .param("t", tenant.value())
        .param("a", arrangerEmployeeId)
        .query(ArrangerRepository::map)
        .list();
  }

  public List<ArrangerGrant> list(TenantId tenant) {
    return jdbc.sql("SELECT * FROM arranger_grant WHERE tenant_id = :t ORDER BY granted_at")
        .param("t", tenant.value())
        .query(ArrangerRepository::map)
        .list();
  }

  public boolean revoke(TenantId tenant, String grantId, Instant now) {
    return jdbc.sql(
                "UPDATE arranger_grant SET revoked_at = :now WHERE tenant_id = :t AND grant_id = :id AND revoked_at IS NULL")
            .param("now", Rows.ts(now))
            .param("t", tenant.value())
            .param("id", grantId)
            .update()
        == 1;
  }

  /** Offboarding: every grant held by the employee ends. */
  public int revokeAllOf(TenantId tenant, String arrangerEmployeeId, Instant now) {
    return jdbc.sql(
            "UPDATE arranger_grant SET revoked_at = :now WHERE tenant_id = :t AND arranger_employee_id = :a AND revoked_at IS NULL")
        .param("now", Rows.ts(now))
        .param("t", tenant.value())
        .param("a", arrangerEmployeeId)
        .update();
  }

  private static ArrangerGrant map(ResultSet rs, int i) throws SQLException {
    return new ArrangerGrant(
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("grant_id"),
        rs.getString("arranger_employee_id"),
        ArrangerGrant.Scope.valueOf(rs.getString("scope_kind")),
        rs.getString("scope_id"),
        rs.getBoolean("may_read_documents"),
        rs.getString("granted_by"),
        rs.getObject("granted_at", OffsetDateTime.class).toInstant(),
        Rows.instant(rs, "expires_at"),
        Rows.instant(rs, "revoked_at"));
  }
}
