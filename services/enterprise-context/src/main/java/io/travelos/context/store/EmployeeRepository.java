package io.travelos.context.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.Employee;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeRepository {
  private final JdbcClient jdbc;

  public EmployeeRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Upsert from the HRIS connector. An older revision never overwrites a newer one. */
  public boolean upsert(Employee e) {
    return jdbc.sql(
                """
                INSERT INTO employee (tenant_id, employee_id, email, display_name, work_location, time_zone,
                  manager_employee_id, active, source_revision, updated_at)
                VALUES (:tenant, :id, :email, :name, :location, :zone, :manager, :active, :revision, :now)
                ON CONFLICT (tenant_id, employee_id) DO UPDATE SET
                  email = EXCLUDED.email, display_name = EXCLUDED.display_name, work_location = EXCLUDED.work_location,
                  time_zone = EXCLUDED.time_zone, manager_employee_id = EXCLUDED.manager_employee_id,
                  active = EXCLUDED.active, source_revision = EXCLUDED.source_revision, updated_at = EXCLUDED.updated_at
                WHERE employee.source_revision < EXCLUDED.source_revision
                """)
            .param("tenant", e.tenant().value())
            .param("id", e.employeeId())
            .param("email", e.email())
            .param("name", e.displayName())
            .param("location", e.workLocation())
            .param("zone", e.timeZone().getId())
            .param("manager", e.managerEmployeeId())
            .param("active", e.active())
            .param("revision", e.sourceRevision())
            .param("now", Rows.ts(e.updatedAt()))
            .update()
        == 1;
  }

  public Optional<Employee> find(TenantId tenant, String employeeId) {
    return jdbc.sql("SELECT * FROM employee WHERE tenant_id = :t AND employee_id = :id")
        .param("t", tenant.value())
        .param("id", employeeId)
        .query(EmployeeRepository::map)
        .optional();
  }

  /** Identity resolution: only an exact, case-insensitive match on the directory's e-mail. */
  public Optional<Employee> findByEmail(TenantId tenant, String email) {
    return jdbc.sql("SELECT * FROM employee WHERE tenant_id = :t AND lower(email) = lower(:e)")
        .param("t", tenant.value())
        .param("e", email)
        .query(EmployeeRepository::map)
        .optional();
  }

  public List<Employee> list(TenantId tenant) {
    return jdbc.sql("SELECT * FROM employee WHERE tenant_id = :t ORDER BY employee_id")
        .param("t", tenant.value())
        .query(EmployeeRepository::map)
        .list();
  }

  static Employee map(ResultSet rs, int i) throws SQLException {
    return new Employee(
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("employee_id"),
        rs.getString("email"),
        rs.getString("display_name"),
        rs.getString("work_location"),
        ZoneId.of(rs.getString("time_zone")),
        rs.getString("manager_employee_id"),
        rs.getBoolean("active"),
        rs.getLong("source_revision"),
        Rows.instant(rs, "updated_at"));
  }
}
