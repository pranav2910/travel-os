package io.travelos.travelcore.trip;

import io.travelos.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TripAllocationRepository {
  private final JdbcClient jdbc;

  public TripAllocationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(TenantId tenant, TripAllocation a) {
    jdbc.sql(
            """
            INSERT INTO trip_allocation (tenant_id, trip_id, department_id, cost_center_id, legal_entity_id, office_id,
              project_id, project_restricted, manager_employee_id, arranger_employee_id, arranger_basis, traveler_kind,
              profile_version, captured_at)
            VALUES (:t, :trip, :dept, :cc, :le, :office, :project, :restricted, :manager, :arranger, :basis, :kind,
              :pv, :at)
            """)
        .param("t", tenant.value())
        .param("trip", a.tripId())
        .param("dept", a.departmentId())
        .param("cc", a.costCenterId())
        .param("le", a.legalEntityId())
        .param("office", a.officeId())
        .param("project", a.projectId())
        .param("restricted", a.projectRestricted())
        .param("manager", a.managerEmployeeId())
        .param("arranger", a.arrangerEmployeeId())
        .param("basis", a.arrangerBasis())
        .param("kind", a.travelerKind())
        .param("pv", a.profileVersion())
        .param("at", Timestamp.from(a.capturedAt()))
        .update();
  }

  public Optional<TripAllocation> find(TenantId tenant, String tripId) {
    return jdbc.sql("SELECT * FROM trip_allocation WHERE tenant_id = :t AND trip_id = :trip")
        .param("t", tenant.value())
        .param("trip", tripId)
        .query(TripAllocationRepository::map)
        .optional();
  }

  static TripAllocation map(ResultSet rs, int i) throws SQLException {
    return new TripAllocation(
        rs.getString("trip_id"),
        rs.getString("department_id"),
        rs.getString("cost_center_id"),
        rs.getString("legal_entity_id"),
        rs.getString("office_id"),
        rs.getString("project_id"),
        rs.getBoolean("project_restricted"),
        rs.getString("manager_employee_id"),
        rs.getString("arranger_employee_id"),
        rs.getString("arranger_basis"),
        rs.getString("traveler_kind"),
        rs.getLong("profile_version"),
        rs.getObject("captured_at", OffsetDateTime.class).toInstant());
  }
}
