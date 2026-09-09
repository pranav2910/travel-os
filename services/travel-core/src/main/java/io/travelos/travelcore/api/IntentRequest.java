package io.travelos.travelcore.api;

import io.travelos.travelcore.trip.TravelIntent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record IntentRequest(
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an IATA airport code")
        String origin,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an IATA airport code")
        String destination,
    @NotNull Instant earliestDeparture,
    @NotNull Instant arrivalDeadline,
    @Nullable Instant returnAfter,
    @Nullable Instant latestReturn,
    @Nullable @Size(max = 500) String purpose,
    @Nullable Boolean hotelRequired,
    @Nullable Integer travelers) {

  public TravelIntent toDomain() {
    return new TravelIntent(
        origin,
        destination,
        earliestDeparture,
        arrivalDeadline,
        returnAfter,
        latestReturn,
        purpose,
        hotelRequired != null && hotelRequired,
        travelers == null ? 1 : travelers);
  }

  public static IntentRequest from(TravelIntent intent) {
    return new IntentRequest(
        intent.origin(),
        intent.destination(),
        intent.earliestDeparture(),
        intent.arrivalDeadline(),
        intent.returnAfter(),
        intent.latestReturn(),
        intent.purpose(),
        intent.hotelRequired(),
        intent.travelers());
  }
}
