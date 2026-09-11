package io.travelos.travelcore.approval;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A human decision the workflow is waiting for. One pending approval per trip at a time. */
public record Approval(
    String approvalId,
    TenantId tenant,
    String tripId,
    String requiredRole,
    Status status,
    @Nullable String policyDecisionId,
    Instant requestedAt,
    @Nullable String decidedBy,
    @Nullable Instant decidedAt,
    @Nullable String comment,
    @Nullable String decisionIdempotencyKey) {

  public enum Status {
    PENDING,
    APPROVED,
    REJECTED
  }
}
