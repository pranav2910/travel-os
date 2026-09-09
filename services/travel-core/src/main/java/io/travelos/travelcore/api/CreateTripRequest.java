package io.travelos.travelcore.api;

import io.travelos.travelcore.trip.TripSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * {@code POST /api/v1/trips}. Either {@code request} (free text, understood later) or {@code
 * intent} (structured, planned immediately), or both.
 *
 * @param travelerId who travels; defaults to the caller. Arranging for others needs
 *     MANAGER/TRAVEL_ADMIN.
 * @param request what the traveler said, e.g. "I need to be in Seattle before 9am Tuesday..."
 * @param intent the structured need, when the client already knows it
 * @param source defaults to API
 */
public record CreateTripRequest(
    @Nullable @Size(max = 64) String travelerId,
    @Nullable @Size(max = 4000) String request,
    @Nullable @Valid IntentRequest intent,
    @Nullable TripSource source) {}
