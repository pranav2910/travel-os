package io.travelos.context.detect;

import com.google.protobuf.Timestamp;
import io.travelos.common.geo.Locations;
import io.travelos.context.model.DemandCandidate;
import io.travelos.contracts.trip.v1.Itinerary;
import io.travelos.contracts.trip.v1.Leg;
import io.travelos.contracts.trip.v1.Stay;
import io.travelos.contracts.trip.v1.TravelIntent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * How an actionable candidate becomes a frozen intent, deterministically (plan-v1): arrive at least
 * two hours before the first commitment, or the evening before when it starts before 11:00 local;
 * leave after the last one; a required stay covers every night in between, on the destination's
 * calendar. The trip lifecycle (policy, optimization, approval, booking) then decides what is
 * actually bookable; nothing here books anything.
 */
public final class TravelPlan {
  public static final String VERSION = "plan-v1";
  static final Duration ARRIVAL_BUFFER = Duration.ofHours(2);
  static final Duration DEPARTURE_BUFFER = Duration.ofHours(1);
  static final Duration OUTBOUND_WINDOW = Duration.ofHours(14);
  static final Duration RETURN_WINDOW = Duration.ofHours(16);
  static final LocalTime EARLY_START = LocalTime.of(11, 0);
  static final LocalTime DAY_STARTS = LocalTime.of(5, 0);
  static final LocalTime EVENING_ARRIVAL = LocalTime.of(23, 0);

  private TravelPlan() {}

  public static TravelIntent derive(DemandCandidate c, ZoneId originZone) {
    Objects.requireNonNull(c.destination(), "destination");
    Objects.requireNonNull(c.windowStart(), "windowStart");
    Objects.requireNonNull(c.windowEnd(), "windowEnd");
    ZoneId destZone = Locations.zoneOrThrow(c.destination());
    LocalDate startDate = c.windowStart().atZone(destZone).toLocalDate();
    Instant arrivalDeadline;
    Instant earliestDeparture;
    if (c.windowStart().atZone(destZone).toLocalTime().isBefore(EARLY_START)) {
      LocalDate eve = startDate.minusDays(1);
      arrivalDeadline = eve.atTime(EVENING_ARRIVAL).atZone(destZone).toInstant();
      earliestDeparture = eve.atTime(DAY_STARTS).atZone(originZone).toInstant();
    } else {
      arrivalDeadline = c.windowStart().minus(ARRIVAL_BUFFER);
      earliestDeparture = startDate.atTime(DAY_STARTS).atZone(originZone).toInstant();
    }
    if (!earliestDeparture.isBefore(arrivalDeadline.minus(Duration.ofHours(2)))) {
      earliestDeparture = arrivalDeadline.minus(OUTBOUND_WINDOW);
    }
    Instant returnAfter = c.windowEnd().plus(DEPARTURE_BUFFER);
    Instant latestReturn = returnAfter.plus(RETURN_WINDOW);
    LocalDate checkIn = arrivalDeadline.atZone(destZone).toLocalDate();
    LocalDate checkOut = returnAfter.atZone(destZone).toLocalDate();
    Itinerary.Builder it =
        Itinerary.newBuilder()
            .addLegs(
                Leg.newBuilder()
                    .setOrigin(c.origin())
                    .setDestination(c.destination())
                    .setEarliestDeparture(ts(earliestDeparture))
                    .setArrivalDeadline(ts(arrivalDeadline)))
            .addLegs(
                Leg.newBuilder()
                    .setOrigin(c.destination())
                    .setDestination(c.origin())
                    .setEarliestDeparture(ts(returnAfter))
                    .setArrivalDeadline(ts(latestReturn)));
    if (checkOut.isAfter(checkIn)) {
      it.addStays(
          Stay.newBuilder()
              .setCity(c.destination())
              .setCheckInDate(checkIn.toString())
              .setCheckOutDate(checkOut.toString())
              .setRequired(true));
    }
    TravelIntent.Builder intent = TravelIntent.newBuilder().setItinerary(it).setTravelers(1);
    if (c.purpose() != null) {
      intent.setPurpose(c.purpose());
    }
    return intent.build();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).build();
  }
}
