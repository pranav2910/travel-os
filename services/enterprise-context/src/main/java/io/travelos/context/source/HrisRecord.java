package io.travelos.context.source;

import org.jspecify.annotations.Nullable;

/**
 * An employee as the HRIS states them. The trusted mapping behind every identity decision; the
 * organizational ids are the HRIS's own codes for department, cost center, legal entity and office
 * (resolved against org units by code when they are known here).
 */
public record HrisRecord(
    String employeeId,
    String email,
    String displayName,
    String workLocation,
    String timeZone,
    @Nullable String managerEmployeeId,
    boolean active,
    @Nullable String departmentId,
    @Nullable String costCenterId,
    @Nullable String legalEntityId,
    @Nullable String officeId) {

  public HrisRecord(
      String employeeId,
      String email,
      String displayName,
      String workLocation,
      String timeZone,
      @Nullable String managerEmployeeId,
      boolean active) {
    this(
        employeeId,
        email,
        displayName,
        workLocation,
        timeZone,
        managerEmployeeId,
        active,
        null,
        null,
        null,
        null);
  }
}
