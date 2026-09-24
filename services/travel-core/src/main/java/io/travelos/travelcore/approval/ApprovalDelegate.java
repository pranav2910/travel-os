package io.travelos.travelcore.approval;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Phase 7: a manager's approval authority handed to someone for a period. */
public record ApprovalDelegate(
    String delegateId,
    TenantId tenant,
    String delegatorEmployeeId,
    String delegateEmployeeId,
    Instant validFrom,
    Instant validUntil,
    String createdBy,
    Instant createdAt,
    @Nullable Instant revokedAt,
    @Nullable String revokedBy) {
  public boolean activeAt(Instant at) {
    return revokedAt == null && !at.isBefore(validFrom) && at.isBefore(validUntil);
  }
}
