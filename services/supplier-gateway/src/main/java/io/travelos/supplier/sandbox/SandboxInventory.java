package io.travelos.supplier.sandbox;

import com.google.protobuf.Timestamp;
import io.travelos.contracts.common.v1.Cabin;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.offer.v1.AirOffer;
import io.travelos.contracts.offer.v1.FlightSegment;
import io.travelos.contracts.offer.v1.Journey;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.offer.v1.OfferType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Deterministic synthetic airline inventory. The same route, dates and cabin always produce the
 * same schedules and fares (seeded by a hash of the route), so tests and demos are reproducible,
 * and pricing an offer later regenerates exactly what search returned.
 *
 * <p>Failure injection, documented for failure-path tests:
 *
 * <ul>
 *   <li>destination {@code ZZZ}: search fails with a retryable UNAVAILABLE
 *   <li>slot 13 (present on every route): booking fails with SEAT_NO_LONGER_AVAILABLE
 *   <li>payment token {@code decline}: booking fails with PAYMENT_DECLINED
 * </ul>
 */
final class SandboxInventory {

  static final String PROVIDER = "sandbox-air";
  static final Duration OFFER_TTL = Duration.ofMinutes(20);
  static final int FAILING_SLOT = 13;

  private static final String[] CARRIERS = {"DL", "UA", "AA", "B6", "AS"};
  private static final LocalTime[] DEPARTURES = {
    LocalTime.of(6, 0), LocalTime.of(8, 30), LocalTime.of(11, 15),
    LocalTime.of(14, 0), LocalTime.of(17, 45), LocalTime.of(21, 30)
  };
  private static final String[] HUBS = {"ORD", "DFW", "DEN"};

  private SandboxInventory() {}

  record Schedule(
      int slot,
      String carrier,
      Cabin cabin,
      List<Leg> outbound,
      List<Leg> inbound,
      long fareMinor,
      boolean refundable,
      int seatsRemaining) {}

  record Leg(
      String carrier,
      String flightNumber,
      String origin,
      String destination,
      Instant departure,
      Instant arrival) {}

  /** All schedules for a route on given dates; the caller filters by windows and cabins. */
  static List<Schedule> schedules(
      String origin,
      String destination,
      LocalDate outboundDate,
      LocalDate inboundDate,
      Set<Cabin> cabins) {
    long seed = seed(origin, destination);
    int nonstopMinutes = 180 + (int) (seed % 6) * 30 + routeBonus(origin, destination);
    long baseFare = 28000 + (seed % 40) * 1000; // USD 280..670 base economy round-trip-ish
    List<Schedule> result = new ArrayList<>();
    int slot = 0;
    for (Cabin cabin : cabins.isEmpty() ? Set.of(Cabin.ECONOMY) : cabins) {
      if (cabin == Cabin.CABIN_UNSPECIFIED || cabin == Cabin.UNRECOGNIZED) {
        continue;
      }
      for (int c = 0; c < CARRIERS.length; c++) {
        String carrier = CARRIERS[c];
        for (int d = 0; d < 3; d++) {
          LocalTime dep = DEPARTURES[(c + d * 2) % DEPARTURES.length];
          List<Leg> out =
              nonstop(carrier, origin, destination, outboundDate, dep, nonstopMinutes, seed + slot);
          List<List<Leg>> inbounds =
              inboundDate == null
                  ? List.of(List.of())
                  : List.of(
                      nonstop(
                          carrier,
                          destination,
                          origin,
                          inboundDate,
                          DEPARTURES[(c + 1) % DEPARTURES.length],
                          nonstopMinutes,
                          seed + slot + 100),
                      nonstop(
                          carrier,
                          destination,
                          origin,
                          inboundDate,
                          DEPARTURES[(c + 4) % DEPARTURES.length],
                          nonstopMinutes,
                          seed + slot + 200));
          for (List<Leg> in : inbounds) {
            long fare = fare(baseFare, cabin, c, d, in.isEmpty(), seed + slot);
            result.add(
                new Schedule(
                    slot++,
                    carrier,
                    cabin,
                    out,
                    in,
                    fare,
                    (slot % 3) == 0,
                    2 + (int) ((seed + slot) % 7)));
          }
        }
        // One cheaper one-stop itinerary per carrier for the first two carriers.
        if (c < 2) {
          String hub = HUBS[c];
          List<Leg> out =
              oneStop(
                  carrier,
                  origin,
                  hub,
                  destination,
                  outboundDate,
                  DEPARTURES[c],
                  nonstopMinutes,
                  seed + slot);
          List<Leg> in =
              inboundDate == null
                  ? List.of()
                  : oneStop(
                      carrier,
                      destination,
                      hub,
                      origin,
                      inboundDate,
                      DEPARTURES[c + 2],
                      nonstopMinutes,
                      seed + slot + 300);
          long fare = (long) (fare(baseFare, cabin, c, 1, in.isEmpty(), seed + slot) * 0.82);
          result.add(new Schedule(slot++, carrier, cabin, out, in, fare, false, 9));
        }
      }
    }
    return result;
  }

  static Offer toOffer(
      Schedule s,
      String origin,
      String destination,
      LocalDate outboundDate,
      LocalDate inboundDate,
      Instant now,
      String searchSessionId,
      String offerId) {
    AirOffer.Builder air =
        AirOffer.newBuilder()
            .setOutbound(journey(s.outbound(), s.cabin()))
            .setFareBasis(s.cabin().name().substring(0, 1) + "SBX")
            .setSeatsRemaining(s.seatsRemaining());
    if (!s.inbound().isEmpty()) {
      air.setInbound(journey(s.inbound(), s.cabin()));
    }
    String providerOfferId =
        new SandboxOfferId(
                origin,
                destination,
                outboundDate,
                inboundDate,
                s.slot(),
                s.cabin().name(),
                now.getEpochSecond())
            .encode();
    Instant expires = now.plus(OFFER_TTL);
    return Offer.newBuilder()
        .setOfferId(offerId)
        .setSearchSessionId(searchSessionId)
        .setProvider(PROVIDER)
        .setProviderOfferId(providerOfferId)
        .setType(OfferType.AIR)
        .setTotal(usd(s.fareMinor()))
        .setRefundable(s.refundable())
        .setChangePenalty(usd(s.refundable() ? 0 : 7500))
        .setExpiresAt(ts(expires))
        .setAir(air)
        .build();
  }

  static Money usd(long minor) {
    return Money.newBuilder().setCurrency("USD").setAmountMinor(minor).build();
  }

  static Timestamp ts(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  private static Journey journey(List<Leg> legs, Cabin cabin) {
    Journey.Builder j = Journey.newBuilder();
    for (Leg leg : legs) {
      j.addSegments(
          FlightSegment.newBuilder()
              .setSegmentId(leg.flightNumber() + "-" + leg.origin())
              .setCarrier(leg.carrier())
              .setFlightNumber(leg.flightNumber())
              .setOrigin(leg.origin())
              .setDestination(leg.destination())
              .setDeparture(ts(leg.departure()))
              .setArrival(ts(leg.arrival()))
              .setCabin(cabin)
              .setBookingClass(
                  cabin == Cabin.ECONOMY ? "Y" : cabin == Cabin.PREMIUM_ECONOMY ? "W" : "J")
              .setAircraft("A321"));
    }
    return j.build();
  }

  private static List<Leg> nonstop(
      String carrier,
      String from,
      String to,
      LocalDate date,
      LocalTime dep,
      int minutes,
      long seed) {
    Instant d = date.atTime(dep).toInstant(ZoneOffset.UTC);
    return List.of(
        new Leg(
            carrier,
            carrier + (100 + Math.floorMod(seed, 800)),
            from,
            to,
            d,
            d.plus(Duration.ofMinutes(minutes))));
  }

  private static List<Leg> oneStop(
      String carrier,
      String from,
      String hub,
      String to,
      LocalDate date,
      LocalTime dep,
      int minutes,
      long seed) {
    Instant d1 = date.atTime(dep).toInstant(ZoneOffset.UTC);
    Instant a1 = d1.plus(Duration.ofMinutes(minutes / 2 + 20));
    Instant d2 = a1.plus(Duration.ofMinutes(75));
    Instant a2 = d2.plus(Duration.ofMinutes(minutes / 2 + 40));
    return List.of(
        new Leg(carrier, carrier + (900 + Math.floorMod(seed, 90)), from, hub, d1, a1),
        new Leg(carrier, carrier + (1000 + Math.floorMod(seed, 90)), hub, to, d2, a2));
  }

  private static long fare(
      long base, Cabin cabin, int carrierIndex, int timeIndex, boolean oneWay, long seed) {
    double multiplier =
        switch (cabin) {
          case PREMIUM_ECONOMY -> 1.6;
          case BUSINESS -> 3.2;
          case FIRST -> 5.0;
          default -> 1.0;
        };
    double carrierFactor = 0.9 + carrierIndex * 0.06;
    double timeFactor = timeIndex == 1 ? 1.12 : timeIndex == 2 ? 0.95 : 1.0;
    double jitter = 1.0 + Math.floorMod(seed, 11) / 100.0;
    long fare = Math.round(base * multiplier * carrierFactor * timeFactor * jitter);
    return oneWay ? Math.round(fare * 0.58) : fare;
  }

  private static long seed(String origin, String destination) {
    return Math.floorMod((long) (origin + "-" + destination).hashCode(), 100_000L);
  }

  private static int routeBonus(String origin, String destination) {
    // Transcontinental-ish pairs get a longer block time so BOS-SEA looks like BOS-SEA.
    Set<String> west = Set.of("SEA", "SFO", "LAX", "SAN", "PDX", "LAS", "PHX");
    Set<String> east = Set.of("BOS", "JFK", "EWR", "LGA", "DCA", "IAD", "PHL", "BWI", "MIA", "ATL");
    boolean transcon =
        (west.contains(origin) && east.contains(destination))
            || (east.contains(origin) && west.contains(destination));
    return transcon ? 150 : 0;
  }
}
