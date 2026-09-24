package io.travelos.context.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;

/**
 * A verified identity from the tenant's HRIS. The only source of who an attendee is, who their
 * manager is, and (since ADR-0014) where they sit in the organization.
 */
public record Employee(
    TenantId tenant,
    String employeeId,
    String email,
    String displayName,
    String workLocation,
    ZoneId timeZone,
    @Nullable String managerEmployeeId,
    boolean active,
    long sourceRevision,
    Instant updatedAt,
    @Nullable String departmentId,
    @Nullable String costCenterId,
    @Nullable String legalEntityId,
    @Nullable String officeId) {

  /** The Slice 4 shape: no organizational fields. */
  public Employee(
      TenantId tenant,
      String employeeId,
      String email,
      String displayName,
      String workLocation,
      ZoneId timeZone,
      @Nullable String managerEmployeeId,
      boolean active,
      long sourceRevision,
      Instant updatedAt) {
    this(
        tenant,
        employeeId,
        email,
        displayName,
        workLocation,
        timeZone,
        managerEmployeeId,
        active,
        sourceRevision,
        updatedAt,
        null,
        null,
        null,
        null);
  }
}
