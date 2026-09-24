package io.travelos.travelcore.api;

import io.travelos.travelcore.trip.TravelerIdentity;
import io.travelos.travelcore.trip.TripSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
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
 * @param traveler who the trip is for, required when {@code travelerId} is someone else (the
 *     reservation is made in their name); the caller's own trips take it from their token
 */
public record CreateTripRequest(
    @Nullable @Size(max = 64) String travelerId,
    @Nullable @Size(max = 4000) String request,
    @Nullable @Valid IntentRequest intent,
    @Nullable TripSource source,
    @Nullable @Valid TravelerRequest traveler,
    /** The project the travel is charged to; a restricted project admits its members only. */
    @Nullable @Size(max = 64) String projectId,
    /**
     * Phase 3. POLICY (default): book on policy's authority when the policy document grants
     * autonomous purchase for the plan. CONFIRM: quote first; a person authorizes the purchase.
     */
    @Nullable @Pattern(regexp = "^(POLICY|CONFIRM)$") String purchaseMode,
    /** Phase 3: keep it as a draft; nothing is planned until {@code POST /{tripId}/submission}. */
    @Nullable Boolean draft) {

  public record TravelerRequest(
      @Nullable @Size(max = 100) String givenName,
      @Nullable @Size(max = 100) String familyName,
      @Nullable @Email @Size(max = 254) String email) {
    TravelerIdentity toDomain() {
      return new TravelerIdentity(givenName, familyName, email);
    }
  }
}
