package io.travelos.order.store;

import io.travelos.common.money.Money;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Money at risk because a confirmed component could not be released (Slice 3). */
public record ExposureRecord(
    String exposureId,
    String orderId,
    String itemId,
    @Nullable String componentId,
    String provider,
    String externalRef,
    Money amount,
    String reason,
    @Nullable String detail,
    Status status,
    @Nullable String resolvedBy,
    @Nullable String resolution,
    @Nullable String resolutionIdempotencyKey,
    Instant createdAt,
    @Nullable Instant resolvedAt) {
  public enum Status {
    OPEN,
    RESOLVED
  }
}
