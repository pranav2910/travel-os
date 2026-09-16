package io.travelos.learning.model;

import org.jspecify.annotations.Nullable;

public record OrderItemRef(
    String orderId,
    String itemId,
    String tripId,
    @Nullable String componentId,
    String type,
    @Nullable String provider,
    @Nullable String supplierKey,
    String status) {}
