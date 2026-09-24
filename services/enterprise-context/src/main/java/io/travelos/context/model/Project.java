package io.travelos.context.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A client engagement or internal project travel is charged to. A restricted project's travel is
 * visible only to its members, their managers and the tenant's travel admins and Finance.
 */
public record Project(
    TenantId tenant,
    String projectId,
    String code,
    String name,
    @Nullable String client,
    @Nullable String costCenterId,
    boolean restricted,
    boolean active,
    long version,
    Instant updatedAt) {}
