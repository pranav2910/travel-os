package io.travelos.supplier.sandbox;

import io.travelos.contracts.offer.v1.CancellationTerms;
import io.travelos.contracts.offer.v1.GroundOffer;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.offer.v1.OfferType;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic synthetic ground transport (Slice 3): a few vendors per city, pickups on the
 * quarter hour every {@link #PICKUP_STEP} across the whole requested window (the window is the
 * caller's uncertainty about when the traveler lands or must leave, so availability has to cover
 * all of it), 35-minute transfers. Fault fixtures, as the cheapest vendor of a city: LAX (booking
 * refused), DEN (booking committed, answer lost).
 */
final class SandboxGroundInventory {
  static final String PROVIDER = "sandbox-ground";
  static final Duration OFFER_TTL = Duration.ofMinutes(20);
  static final Duration TRANSFER_DURATION = Duration.ofMinutes(35);
  static final Duration PICKUP_STEP = Duration.ofMinutes(30);

  /** Per vendor; bounds a response for a window wider than a day. */
  static final int MAX_PICKUPS = 48;

  record Vendor(
      String code,
      String name,
      String vehicleClass,
      long fareMinor,
      boolean refundable,
      String description,
      SandboxFault fault) {
    String vendorId(String city) {
      return "GRD-" + city + "-" + code;
    }
  }

  private static final List<Vendor> DEFAULT =
      List.of(
          new Vendor(
              "SHUTTLE",
              "CityShuttle",
              "SHUTTLE",
              3900,
              false,
              "Shared van, non-refundable.",
              SandboxFault.NONE),
          new Vendor(
              "SEDAN",
              "MetroCar",
              "SEDAN",
              6500,
              true,
              "Private sedan, free cancellation.",
              SandboxFault.NONE),
          new Vendor("VAN", "ExecVan", "VAN", 9800, true, "Executive van.", SandboxFault.NONE));

  private SandboxGroundInventory() {}

  static List<Vendor> catalog(String city) {
    return switch (city) {
      case "LAX" ->
          List.of(
              new Vendor(
                  "FAIL",
                  "Coastal Rides",
                  "SHUTTLE",
                  3500,
                  true,
                  "Often sold out.",
                  SandboxFault.FAIL),
              DEFAULT.get(1),
              DEFAULT.get(2));
      case "DEN" ->
          List.of(
              new Vendor(
                  "TIMEOUT",
                  "Rocky Shuttle",
                  "SHUTTLE",
                  3500,
                  true,
                  "Slow dispatch.",
                  SandboxFault.TIMEOUT),
              DEFAULT.get(1),
              DEFAULT.get(2));
      default -> DEFAULT;
    };
  }

  static Optional<Vendor> vendor(String city, String code) {
    return catalog(city).stream().filter(v -> v.code().equals(code)).findFirst();
  }

  static Offer toOffer(
      Vendor v,
      SandboxTransferId id,
      ZoneId zone,
      Instant now,
      String searchSessionId,
      String offerId) {
    Instant pickup = Instant.ofEpochSecond(id.pickupEpochSeconds());
    Instant dropoff = pickup.plus(TRANSFER_DURATION);
    return Offer.newBuilder()
        .setOfferId(offerId)
        .setSearchSessionId(searchSessionId)
        .setProvider(PROVIDER)
        .setProviderOfferId(id.encode())
        .setType(OfferType.GROUND)
        .setTotal(SandboxHotelInventory.money("USD", v.fareMinor()))
        .setRefundable(v.refundable())
        .setChangePenalty(SandboxHotelInventory.money("USD", 0))
        .setExpiresAt(SandboxHotelInventory.ts(now.plus(OFFER_TTL)))
        .setCancellation(
            CancellationTerms.newBuilder()
                .setRefundable(v.refundable())
                .setPenalty(SandboxHotelInventory.money("USD", v.refundable() ? 0 : v.fareMinor()))
                .setFreeUntil(SandboxHotelInventory.ts(pickup.minus(Duration.ofHours(2)))))
        .setGround(
            GroundOffer.newBuilder()
                .setVendorId(v.vendorId(id.city()))
                .setVendorName(v.name())
                .setVehicleClass(v.vehicleClass())
                .setPickupLocation(id.from())
                .setDropoffLocation(id.to())
                .setPickup(SandboxHotelInventory.ts(pickup))
                .setDropoff(SandboxHotelInventory.ts(dropoff))
                .setPassengers(1)
                .setTimeZone(zone.getId())
                .setCity(id.city())
                .setDescription(v.description()))
        .build();
  }
}
