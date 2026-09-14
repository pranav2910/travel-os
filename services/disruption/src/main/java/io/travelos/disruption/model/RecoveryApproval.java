package io.travelos.disruption.model;

import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record RecoveryApproval(
    String approvalId,
    String disruptionId,
    TenantId tenant,
    String tripId,
    String travelerId,
    String requiredRole,
    Status status,
    @Nullable String policyDecisionId,
    @Nullable Money incrementalCost,
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
