package io.travelos.context.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;

/** A verified identity from the tenant's HRIS. The only source of who an attendee is. */
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
    Instant updatedAt) {}
