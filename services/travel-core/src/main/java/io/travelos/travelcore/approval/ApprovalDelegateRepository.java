package io.travelos.travelcore.approval;

import io.travelos.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalDelegateRepository {
  private final JdbcClient jdbc;

  public ApprovalDelegateRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(ApprovalDelegate d) {
    jdbc.sql(
            """
            INSERT INTO approval_delegate (delegate_id, tenant_id, delegator_employee_id, delegate_employee_id,
              valid_from, valid_until, created_by, created_at)
            VALUES (:id, :tenant, :delegator, :delegate, :from, :until, :by, :at)
            """)
        .param("id", d.delegateId())
        .param("tenant", d.tenant().value())
        .param("delegator", d.delegatorEmployeeId())
        .param("delegate", d.delegateEmployeeId())
        .param("from", ts(d.validFrom()))
        .param("until", ts(d.validUntil()))
        .param("by", d.createdBy())
        .param("at", ts(d.createdAt()))
        .update();
  }

  public Optional<ApprovalDelegate> find(TenantId tenant, String delegateId) {
    return jdbc.sql("SELECT * FROM approval_delegate WHERE tenant_id = :t AND delegate_id = :id")
        .param("t", tenant.value())
        .param("id", delegateId)
        .query(ApprovalDelegateRepository::map)
        .optional();
  }

  /** Everyone whose authority this employee may exercise right now. */
  public List<String> delegatorsOf(TenantId tenant, String delegateEmployeeId, Instant at) {
    return jdbc.sql(
            "SELECT delegator_employee_id FROM approval_delegate WHERE tenant_id = :t AND delegate_employee_id = :d"
                + " AND revoked_at IS NULL AND valid_from <= :at AND valid_until > :at")
        .param("t", tenant.value())
        .param("d", delegateEmployeeId)
        .param("at", ts(at))
        .query(String.class)
        .list();
  }

  public List<ApprovalDelegate> involving(TenantId tenant, String employeeId) {
    return jdbc.sql(
            "SELECT * FROM approval_delegate WHERE tenant_id = :t AND (delegator_employee_id = :e OR delegate_employee_id = :e)"
                + " ORDER BY created_at DESC")
        .param("t", tenant.value())
        .param("e", employeeId)
        .query(ApprovalDelegateRepository::map)
        .list();
  }

  public List<ApprovalDelegate> all(TenantId tenant) {
    return jdbc.sql("SELECT * FROM approval_delegate WHERE tenant_id = :t ORDER BY created_at DESC")
        .param("t", tenant.value())
        .query(ApprovalDelegateRepository::map)
        .list();
  }

  public boolean revoke(TenantId tenant, String delegateId, String by, Instant now) {
    return jdbc.sql(
                "UPDATE approval_delegate SET revoked_at = :now, revoked_by = :by WHERE tenant_id = :t AND delegate_id = :id AND revoked_at IS NULL")
            .param("now", ts(now))
            .param("by", by)
            .param("t", tenant.value())
            .param("id", delegateId)
            .update()
        == 1;
  }

  private static ApprovalDelegate map(ResultSet rs, int i) throws SQLException {
    OffsetDateTime revoked = rs.getObject("revoked_at", OffsetDateTime.class);
    return new ApprovalDelegate(
        rs.getString("delegate_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("delegator_employee_id"),
        rs.getString("delegate_employee_id"),
        rs.getObject("valid_from", OffsetDateTime.class).toInstant(),
        rs.getObject("valid_until", OffsetDateTime.class).toInstant(),
        rs.getString("created_by"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        revoked == null ? null : revoked.toInstant(),
        rs.getString("revoked_by"));
  }

  private static OffsetDateTime ts(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
