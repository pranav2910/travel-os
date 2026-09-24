package io.travelos.travelcore.trip;

import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Permission to spend: one plan, one total, one currency, the conditions, for one traveler, for a
 * while. A person gives it (HUMAN) or the policy document does (POLICY_AUTONOMY, auditable through
 * the policy decision). A changed price or another plan supersedes it; booking consumes it once.
 */
public record PurchaseAuthorization(
    String authorizationId,
    TenantId tenant,
    String tripId,
    Status status,
    Basis basis,
    String authorizedBy,
    String bundleId,
    Money total,
    @Nullable String conditions,
    String travelerId,
    long profileVersion,
    long tripVersion,
    @Nullable String policyDecisionId,
    @Nullable String idempotencyKey,
    @Nullable Instant expiresAt,
    Instant createdAt,
    Instant updatedAt,
    @Nullable String consumedOrderAttempt,
    @Nullable String supersededBy,
    @Nullable String supersededReason) {

  public enum Status {
    ACTIVE,
    CONSUMED,
    SUPERSEDED,
    REVOKED,
    EXPIRED
  }

  public enum Basis {
    HUMAN,
    POLICY_AUTONOMY
  }

  /**
   * Does this authorization cover booking that plan at that price, now? The authorized total is a
   * ceiling: the same plan re-quoted lower is still what the person agreed to pay for; anything
   * higher, another plan, another currency or an expired quote is not.
   */
  public boolean covers(String bundle, Money price, Instant now) {
    return status == Status.ACTIVE
        && bundleId.equals(bundle)
        && total.currency().equals(price.currency())
        && price.amountMinor() <= total.amountMinor()
        && (expiresAt == null || expiresAt.isAfter(now));
  }
}
