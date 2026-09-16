package io.travelos.context.source;

import org.jspecify.annotations.Nullable;

/** An employee as the HRIS states them. The trusted mapping behind every identity decision. */
public record HrisRecord(
    String employeeId,
    String email,
    String displayName,
    String workLocation,
    String timeZone,
    @Nullable String managerEmployeeId,
    boolean active) {}
