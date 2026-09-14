package io.travelos.order.store;

import io.travelos.common.identity.Principal;
import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** One logical replacement of an order's itinerary. */
public record OrderChangeRecord(
    String changeId,
    String orderId,
    TenantId tenant,
    @Nullable String disruptionId,
    String idempotencyKey,
    Status status,
    OrderStatus previousStatus,
    String previousBundleId,
    String replacementBundleId,
    String replacementOfferJson,
    String currency,
    @Nullable Long incrementalMinor,
    @Nullable String policyDecisionId,
    @Nullable String optimizationRunId,
    @Nullable String approvalId,
    @Nullable String externalOrderId,
    @Nullable String recordLocator,
    @Nullable String failureCode,
    @Nullable String failureMessage,
    Principal requestedBy,
    Instant createdAt,
    Instant updatedAt) {

  public enum Status {
    PENDING,
    APPLIED,
    FAILED
  }
}
