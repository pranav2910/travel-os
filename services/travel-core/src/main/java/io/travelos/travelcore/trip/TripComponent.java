package io.travelos.travelcore.trip;

import io.travelos.common.money.Money;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Where one itinerary component stands (Slice 3). */
public record TripComponent(
    String componentId,
    String type,
    String status,
    @Nullable String offerId,
    @Nullable String provider,
    @Nullable String externalRef,
    @Nullable Money total,
    @Nullable String failureCode,
    @Nullable String summary,
    int position,
    Instant updatedAt) {}
