package io.travelos.learning.model;

import org.jspecify.annotations.Nullable;

/**
 * @param currency the currency the item was booked in, when the order event carried its total
 */
public record OrderItemRef(
    String orderId,
    String itemId,
    String tripId,
    @Nullable String componentId,
    String type,
    @Nullable String provider,
    @Nullable String supplierKey,
    String status,
    @Nullable String currency) {}
