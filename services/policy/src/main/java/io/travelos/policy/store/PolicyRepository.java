package io.travelos.policy.store;

import io.travelos.common.identity.Principal;
import io.travelos.common.tenant.TenantId;
import io.travelos.policy.document.PolicyDocument;
import io.travelos.policy.document.PolicyDocuments;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Versioned policy documents and the per-tenant default assignment. Tenant-scoped everywhere. */
@Repository
public class PolicyRepository {

  private static final String COLUMNS =
      "tenant_id, policy_id, version, document::text AS document, document_hash, published_by,"
          + " published_at, note";

  private final JdbcClient jdbc;

  public PolicyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Appends the next version. Callers run this inside a transaction; the advisory lock serializes
   * concurrent publishes of the same policy so version numbers never collide.
   */
  public PolicyVersion publish(
      TenantId tenant,
      PolicyDocument document,
      String hash,
      Principal publishedBy,
      @Nullable String note,
      Instant now) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))")
        .param("key", tenant.value() + "/" + document.policyId())
        .query()
        .singleRow();
    Integer next =
        jdbc.sql(
                "SELECT coalesce(max(version), 0) + 1 FROM policy_version"
                    + " WHERE tenant_id = :tenantId AND policy_id = :policyId")
            .param("tenantId", tenant.value())
            .param("policyId", document.policyId())
            .query(Integer.class)
            .single();
    int version = next == null ? 1 : next;
    jdbc.sql(
            """
            INSERT INTO policy_version
              (tenant_id, policy_id, version, name, document, document_hash, published_by, published_at, note)
            VALUES (:tenantId, :policyId, :version, :name, CAST(:document AS jsonb), :hash, :publishedBy,
              :publishedAt, :note)
            """)
        .param("tenantId", tenant.value())
        .param("policyId", document.policyId())
        .param("version", version)
        .param("name", document.name())
        .param("document", PolicyDocuments.canonicalJson(document))
        .param("hash", hash)
        .param("publishedBy", publishedBy.id())
        .param("publishedAt", ts(now))
        .param("note", note)
        .update();
    // The tenant's first policy becomes its default; later ones are opted into explicitly.
    jdbc.sql(
            """
            INSERT INTO policy_assignment (tenant_id, policy_id, updated_by, updated_at)
            VALUES (:tenantId, :policyId, :by, :at)
            ON CONFLICT (tenant_id) DO NOTHING
            """)
        .param("tenantId", tenant.value())
        .param("policyId", document.policyId())
        .param("by", publishedBy.id())
        .param("at", ts(now))
        .update();
    return new PolicyVersion(
        tenant, document.policyId(), version, document, hash, publishedBy, now, note);
  }

  public Optional<PolicyVersion> current(TenantId tenant, String policyId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM policy_version WHERE tenant_id = :tenantId AND policy_id = :policyId"
                + " ORDER BY version DESC LIMIT 1")
        .param("tenantId", tenant.value())
        .param("policyId", policyId)
        .query(PolicyRepository::map)
        .optional();
  }

  public Optional<PolicyVersion> version(TenantId tenant, String policyId, int version) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM policy_version WHERE tenant_id = :tenantId AND policy_id = :policyId"
                + " AND version = :version")
        .param("tenantId", tenant.value())
        .param("policyId", policyId)
        .param("version", version)
        .query(PolicyRepository::map)
        .optional();
  }

  public Optional<PolicyVersion> defaultPolicy(TenantId tenant) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM policy_version v WHERE v.tenant_id = :tenantId AND v.policy_id ="
                + " (SELECT policy_id FROM policy_assignment a WHERE a.tenant_id = :tenantId)"
                + " ORDER BY v.version DESC LIMIT 1")
        .param("tenantId", tenant.value())
        .query(PolicyRepository::map)
        .optional();
  }

  public List<PolicyVersion.Summary> list(TenantId tenant) {
    return jdbc.sql(
            """
            SELECT v.policy_id, v.name, v.version, v.published_at,
                   (a.policy_id IS NOT NULL) AS is_default
            FROM policy_version v
            JOIN (SELECT tenant_id, policy_id, max(version) AS version FROM policy_version
                  WHERE tenant_id = :tenantId GROUP BY tenant_id, policy_id) latest
              ON latest.tenant_id = v.tenant_id AND latest.policy_id = v.policy_id
                 AND latest.version = v.version
            LEFT JOIN policy_assignment a ON a.tenant_id = v.tenant_id AND a.policy_id = v.policy_id
            WHERE v.tenant_id = :tenantId
            ORDER BY v.policy_id
            """)
        .param("tenantId", tenant.value())
        .query(
            (rs, rowNum) ->
                new PolicyVersion.Summary(
                    rs.getString("policy_id"),
                    rs.getString("name"),
                    rs.getInt("version"),
                    rs.getBoolean("is_default"),
                    instant(rs, "published_at")))
        .list();
  }

  /** Returns false when the policy does not exist for the tenant. */
  public boolean setDefault(TenantId tenant, String policyId, Principal by, Instant now) {
    if (current(tenant, policyId).isEmpty()) {
      return false;
    }
    jdbc.sql(
            """
            INSERT INTO policy_assignment (tenant_id, policy_id, updated_by, updated_at)
            VALUES (:tenantId, :policyId, :by, :at)
            ON CONFLICT (tenant_id) DO UPDATE
              SET policy_id = EXCLUDED.policy_id, updated_by = EXCLUDED.updated_by,
                  updated_at = EXCLUDED.updated_at
            """)
        .param("tenantId", tenant.value())
        .param("policyId", policyId)
        .param("by", by.id())
        .param("at", ts(now))
        .update();
    return true;
  }

  private static PolicyVersion map(ResultSet rs, int rowNum) throws SQLException {
    return new PolicyVersion(
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("policy_id"),
        rs.getInt("version"),
        PolicyDocuments.parse(rs.getString("document")),
        rs.getString("document_hash"),
        Principal.parse(rs.getString("published_by")),
        instant(rs, "published_at"),
        rs.getString("note"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static OffsetDateTime ts(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
