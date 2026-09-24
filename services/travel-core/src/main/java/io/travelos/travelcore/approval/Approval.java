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
    @Nullable String decisionIdempotencyKey,
    int step,
    int chainLength,
    java.util.List<String> chainRoles,
    @Nullable Instant expiresAt,
    @Nullable Instant escalatedAt,
    @Nullable String escalatedToRole,
    @Nullable String onBehalfOf) {

  public Approval {
    chainRoles =
        chainRoles == null || chainRoles.isEmpty()
            ? java.util.List.of(requiredRole)
            : java.util.List.copyOf(chainRoles);
  }

  /** The Slice 2 shape: one step, no expiry. */
  public Approval(
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
    this(
        approvalId,
        tenant,
        tripId,
        requiredRole,
        status,
        policyDecisionId,
        requestedAt,
        decidedBy,
        decidedAt,
        comment,
        decisionIdempotencyKey,
        1,
        1,
        java.util.List.of(requiredRole),
        null,
        null,
        null,
        null);
  }

  /** The role that may decide now: the escalation target once escalated, else the step's role. */
  public String deciderRole() {
    return escalatedToRole == null ? requiredRole : escalatedToRole;
  }

  public boolean lastStep() {
    return step >= chainLength;
  }

  public enum Status {
    PENDING,
    APPROVED,
    REJECTED
  }
}
