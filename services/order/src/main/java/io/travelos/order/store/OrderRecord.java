package io.travelos.order.store;

import io.travelos.common.identity.Principal;
import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record OrderRecord(
    String orderId,
    TenantId tenant,
    String tripId,
    String travelerId,
    String bundleId,
    String supplier,
    @Nullable String externalOrderId,
    OrderStatus status,
    Money total,
    String idempotencyKey,
    @Nullable String policyDecisionId,
    @Nullable String optimizationRunId,
    @Nullable String approvalId,
    @Nullable String failureCode,
    @Nullable String failureMessage,
    boolean compensated,
    Principal createdBy,
    long version,
    Instant createdAt,
    Instant updatedAt,
    List<Item> items) {

  public enum ItemStatus {
    PENDING,
    HELD,
    CONFIRMED,
    FAILED,
    CANCELLED
  }

  /**
   * @param offerJson the offer as protobuf JSON, exactly what was booked
   */
  public record Item(
      String itemId,
      int position,
      String offerType,
      String provider,
      String providerOfferId,
      String offerJson,
      ItemStatus status,
      @Nullable String externalRef,
      @Nullable String recordLocator,
      Money total,
      @Nullable String failureCode,
      Instant updatedAt) {}
}
