package io.travelos.travelcore.trip;

import io.travelos.common.money.Money;
import org.jspecify.annotations.Nullable;

/** One of the ranked plans a person may choose while the trip is QUOTED. */
public record TripAlternative(
    String bundleId,
    Money total,
    @Nullable String summary,
    int rank,
    boolean refundable,
    @Nullable Money changePenalty,
    @Nullable String conditions) {}
