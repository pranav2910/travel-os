package io.travelos.travelcore.api;

import io.travelos.travelcore.trip.Itinerary;
import io.travelos.travelcore.trip.TravelIntent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The structured need, as a client states it. Either the Slice 1/2 shape (origin, destination,
 * windows) or, since Slice 3, an {@code itinerary} of ordered legs, stays and transfers; when an
 * itinerary is given the legacy fields are derived from it and must not be supplied.
 */
public record IntentRequest(
    @Nullable @Pattern(regexp = "^[A-Z]{3}$", message = "must be an IATA airport code")
        String origin,
    @Nullable @Pattern(regexp = "^[A-Z]{3}$", message = "must be an IATA airport code")
        String destination,
    @Nullable Instant earliestDeparture,
    @Nullable Instant arrivalDeadline,
    @Nullable Instant returnAfter,
    @Nullable Instant latestReturn,
    @Nullable @Size(max = 500) String purpose,
    @Nullable Boolean hotelRequired,
    @Nullable Integer travelers,
    @Nullable @Valid ItineraryRequest itinerary) {

  /**
   * Slice 3: ordered components. Times are instants (UTC); stay dates are the property's local
   * dates.
   */
  public record ItineraryRequest(
      @NotNull @Size(min = 1, max = 8) List<@Valid LegRequest> legs,
      @Nullable List<@Valid StayRequest> stays,
      @Nullable List<@Valid TransferRequest> transfers,
      @Nullable @Pattern(regexp = "^[A-Z]{3}$") String currency) {}

  public record LegRequest(
      @Nullable String componentId,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String origin,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String destination,
      @NotNull Instant earliestDeparture,
      @NotNull Instant arrivalDeadline) {}

  public record StayRequest(
      @Nullable String componentId,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String city,
      @NotNull LocalDate checkInDate,
      @NotNull LocalDate checkOutDate,
      @Nullable Boolean required) {}

  public record TransferRequest(
      @Nullable String componentId,
      @NotBlank String kind,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String city,
      @Nullable String fromLocation,
      @Nullable String toLocation,
      @Nullable Instant pickup,
      @Nullable Boolean required) {}

  public TravelIntent toDomain() {
    int travelerCount = travelers == null ? 1 : travelers;
    if (itinerary != null) {
      if (origin != null
          || destination != null
          || earliestDeparture != null
          || arrivalDeadline != null) {
        throw new IllegalArgumentException(
            "give either an itinerary or origin/destination/windows, not both");
      }
      List<Itinerary.LegSpec> legs = new ArrayList<>();
      for (LegRequest l : itinerary.legs()) {
        legs.add(
            new Itinerary.LegSpec(
                l.componentId(),
                l.origin(),
                l.destination(),
                l.earliestDeparture(),
                l.arrivalDeadline()));
      }
      List<Itinerary.StaySpec> stays = new ArrayList<>();
      for (StayRequest s : itinerary.stays() == null ? List.<StayRequest>of() : itinerary.stays()) {
        stays.add(
            new Itinerary.StaySpec(
                s.componentId(),
                s.city(),
                s.checkInDate(),
                s.checkOutDate(),
                s.required() == null || s.required()));
      }
      List<Itinerary.TransferSpec> transfers = new ArrayList<>();
      for (TransferRequest t :
          itinerary.transfers() == null ? List.<TransferRequest>of() : itinerary.transfers()) {
        transfers.add(
            new Itinerary.TransferSpec(
                t.componentId(),
                t.kind(),
                t.city(),
                t.fromLocation(),
                t.toLocation(),
                t.pickup(),
                t.required() == null || t.required()));
      }
      return TravelIntent.of(
          Itinerary.of(legs, stays, transfers, itinerary.currency()), purpose, travelerCount);
    }
    if (origin == null
        || destination == null
        || earliestDeparture == null
        || arrivalDeadline == null) {
      throw new IllegalArgumentException(
          "origin, destination, earliestDeparture and arrivalDeadline are required (or an itinerary)");
    }
    return new TravelIntent(
        origin,
        destination,
        earliestDeparture,
        arrivalDeadline,
        returnAfter,
        latestReturn,
        purpose,
        hotelRequired != null && hotelRequired,
        travelerCount);
  }

  public static IntentRequest from(TravelIntent intent) {
    ItineraryRequest itinerary = null;
    if (intent.itinerary() != null) {
      Itinerary it = intent.itinerary();
      itinerary =
          new ItineraryRequest(
              it.legs().stream()
                  .map(
                      l ->
                          new LegRequest(
                              l.componentId(),
                              l.origin(),
                              l.destination(),
                              l.earliestDeparture(),
                              l.arrivalDeadline()))
                  .toList(),
              it.stays().stream()
                  .map(
                      s ->
                          new StayRequest(
                              s.componentId(), s.city(), s.checkIn(), s.checkOut(), s.required()))
                  .toList(),
              it.transfers().stream()
                  .map(
                      t ->
                          new TransferRequest(
                              t.componentId(),
                              t.kind(),
                              t.city(),
                              t.from(),
                              t.to(),
                              t.pickup(),
                              t.required()))
                  .toList(),
              it.currency());
    }
    return new IntentRequest(
        intent.origin(),
        intent.destination(),
        intent.earliestDeparture(),
        intent.arrivalDeadline(),
        intent.returnAfter(),
        intent.latestReturn(),
        intent.purpose(),
        intent.hotelRequired(),
        intent.travelers(),
        itinerary);
  }
}
