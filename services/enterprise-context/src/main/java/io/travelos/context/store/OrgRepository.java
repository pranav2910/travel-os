package io.travelos.context.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.OrgUnit;
import io.travelos.context.model.Project;
import io.travelos.context.model.ProjectMember;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrgRepository {
  private final JdbcClient jdbc;

  public OrgRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insertUnit(OrgUnit u) {
    jdbc.sql(
            """
            INSERT INTO org_unit (tenant_id, unit_id, kind, code, name, parent_unit_id, legal_entity_id, active, version, updated_at)
            VALUES (:t, :id, :kind, :code, :name, :parent, :legal, :active, :version, :now)
            """)
        .param("t", u.tenant().value())
        .param("id", u.unitId())
        .param("kind", u.kind().name())
        .param("code", u.code())
        .param("name", u.name())
        .param("parent", u.parentUnitId())
        .param("legal", u.legalEntityId())
        .param("active", u.active())
        .param("version", u.version())
        .param("now", Rows.ts(u.updatedAt()))
        .update();
  }

  public Optional<OrgUnit> findUnit(TenantId tenant, String unitId) {
    return jdbc.sql("SELECT * FROM org_unit WHERE tenant_id = :t AND unit_id = :id")
        .param("t", tenant.value())
        .param("id", unitId)
        .query(OrgRepository::mapUnit)
        .optional();
  }

  public Optional<OrgUnit> findUnitByCode(TenantId tenant, OrgUnit.Kind kind, String code) {
    return jdbc.sql("SELECT * FROM org_unit WHERE tenant_id = :t AND kind = :k AND code = :c")
        .param("t", tenant.value())
        .param("k", kind.name())
        .param("c", code)
        .query(OrgRepository::mapUnit)
        .optional();
  }

  public List<OrgUnit> listUnits(TenantId tenant) {
    return jdbc.sql("SELECT * FROM org_unit WHERE tenant_id = :t ORDER BY kind, code")
        .param("t", tenant.value())
        .query(OrgRepository::mapUnit)
        .list();
  }

  public void insertProject(Project p) {
    jdbc.sql(
            """
            INSERT INTO project (tenant_id, project_id, code, name, client, cost_center_id, restricted, active, version, updated_at)
            VALUES (:t, :id, :code, :name, :client, :cc, :restricted, :active, :version, :now)
            """)
        .param("t", p.tenant().value())
        .param("id", p.projectId())
        .param("code", p.code())
        .param("name", p.name())
        .param("client", p.client())
        .param("cc", p.costCenterId())
        .param("restricted", p.restricted())
        .param("active", p.active())
        .param("version", p.version())
        .param("now", Rows.ts(p.updatedAt()))
        .update();
  }

  public Optional<Project> findProject(TenantId tenant, String projectId) {
    return jdbc.sql("SELECT * FROM project WHERE tenant_id = :t AND project_id = :id")
        .param("t", tenant.value())
        .param("id", projectId)
        .query(OrgRepository::mapProject)
        .optional();
  }

  public List<Project> listProjects(TenantId tenant) {
    return jdbc.sql("SELECT * FROM project WHERE tenant_id = :t ORDER BY code")
        .param("t", tenant.value())
        .query(OrgRepository::mapProject)
        .list();
  }

  public List<Project> projectsOf(TenantId tenant, String employeeId) {
    return jdbc.sql(
            """
            SELECT p.* FROM project p JOIN project_member m ON m.tenant_id = p.tenant_id AND m.project_id = p.project_id
            WHERE p.tenant_id = :t AND m.employee_id = :e ORDER BY p.code
            """)
        .param("t", tenant.value())
        .param("e", employeeId)
        .query(OrgRepository::mapProject)
        .list();
  }

  public void addMember(TenantId tenant, ProjectMember m) {
    jdbc.sql(
            """
            INSERT INTO project_member (tenant_id, project_id, employee_id, role, since)
            VALUES (:t, :p, :e, :role, :since)
            ON CONFLICT (tenant_id, project_id, employee_id) DO UPDATE SET role = EXCLUDED.role
            """)
        .param("t", tenant.value())
        .param("p", m.projectId())
        .param("e", m.employeeId())
        .param("role", m.role())
        .param("since", Rows.ts(m.since()))
        .update();
  }

  public boolean isMember(TenantId tenant, String projectId, String employeeId) {
    Integer n =
        jdbc.sql(
                "SELECT count(*) FROM project_member WHERE tenant_id = :t AND project_id = :p AND employee_id = :e")
            .param("t", tenant.value())
            .param("p", projectId)
            .param("e", employeeId)
            .query(Integer.class)
            .single();
    return n != null && n > 0;
  }

  public List<ProjectMember> members(TenantId tenant, String projectId) {
    return jdbc.sql(
            "SELECT * FROM project_member WHERE tenant_id = :t AND project_id = :p ORDER BY employee_id")
        .param("t", tenant.value())
        .param("p", projectId)
        .query(
            (rs, i) ->
                new ProjectMember(
                    rs.getString("project_id"),
                    rs.getString("employee_id"),
                    rs.getString("role"),
                    rs.getObject("since", java.time.OffsetDateTime.class).toInstant()))
        .list();
  }

  private static OrgUnit mapUnit(ResultSet rs, int i) throws SQLException {
    return new OrgUnit(
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("unit_id"),
        OrgUnit.Kind.valueOf(rs.getString("kind")),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("parent_unit_id"),
        rs.getString("legal_entity_id"),
        rs.getBoolean("active"),
        rs.getLong("version"),
        instant(rs, "updated_at"));
  }

  private static Project mapProject(ResultSet rs, int i) throws SQLException {
    return new Project(
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("project_id"),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("client"),
        rs.getString("cost_center_id"),
        rs.getBoolean("restricted"),
        rs.getBoolean("active"),
        rs.getLong("version"),
        instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, java.time.OffsetDateTime.class).toInstant();
  }
}
