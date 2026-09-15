package io.travelos.workflows.recovery;

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import io.travelos.contracts.common.v1.Cabin;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.common.v1.TimeWindow;
import io.travelos.contracts.disruption.v1.ComponentChange;
import io.travelos.contracts.disruption.v1.Disruption;
import io.travelos.contracts.offer.v1.Bundle;
import io.travelos.contracts.offer.v1.FlightSegment;
import io.travelos.contracts.offer.v1.Journey;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderItem;
import io.travelos.contracts.order.v1.OrderItemStatus;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchGroundRequest;
import io.travelos.contracts.supplier.v1.SearchHotelsRequest;
import io.travelos.contracts.trip.v1.Itinerary;
import io.travelos.contracts.trip.v1.Leg;
import io.travelos.contracts.trip.v1.Stay;
import io.travelos.contracts.trip.v1.Transfer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Slice 3: what else a changed leg drags along. A later flight moves the airport transfer that
 * meets it, may push a stay's check-in to another day, and may break the chain to the next leg.
 * Reservations that are still valid are preserved; the rest are re-quoted, on the same vendor or
 * property whenever the supplier can change the booking in place. Everything here is a proposal:
 * policy judges the whole replacement and the Order service performs it.
 */
final class Dependants {

  static final Duration TRANSFER_AFTER_ARRIVAL = Duration.ofMinutes(45);
  static final Duration TRANSFER_REACH = Duration.ofHours(4);
  static final Duration TRANSFER_BEFORE_DEPARTURE = Duration.ofMinutes(90);
  static final Duration TRANSFER_EARLIEST_BEFORE = Duration.ofHours(6);
  static final Duration MIN_CONNECTION = Duration.ofHours(1);

  /**
   * @param replacement the component-tagged bundle to book (the new leg plus every dependant that
   *     changes); empty when the order is not an itinerary
   * @param affectedComponentIds the changed leg and every dependant touched, preserved ones too
   * @param changes per-component accounting (REPLACED, RETIMED, PRESERVED)
   * @param incremental the sum of every delta
   */
  record Plan(
      @Nullable Bundle replacement,
      List<String> affectedComponentIds,
      List<ComponentChange> changes,
      Money incremental) {}

  private Dependants() {}

  /** The confirmed item the disruption hit, or null when the order has no component ids. */
  static @Nullable OrderItem affectedItem(Order order, Disruption d) {
    OrderItem byRef = null;
    OrderItem lastAir = null;
    for (OrderItem item : order.getItemsList()) {
      if (item.getStatus() != OrderItemStatus.ITEM_CONFIRMED || item.getComponentId().isBlank()) {
        continue;
      }
      if (item.getExternalRef().equals(d.getExternalOrderId()) && item.getOffer().hasAir()) {
        byRef = item;
      }
      if (item.getOffer().hasAir()) {
        String flight = d.getAffected().getFlightNumber();
        for (FlightSegment s : item.getOffer().getAir().getOutbound().getSegmentsList()) {
          if (!flight.isBlank() && s.getFlightNumber().equalsIgnoreCase(flight)) {
            return item;
          }
        }
        lastAir = item;
      }
    }
    return byRef != null ? byRef : lastAir;
  }

  /** Plans the dependants of replacing {@code affected} with {@code newLeg}. */
  static Plan plan(
      RecoveryActivities activities,
      Function<String, RequestContext> ctx,
      Itinerary itinerary,
      Order order,
      OrderItem affected,
      Offer newLeg) {
    String legId = affected.getComponentId();
    String currency = order.getTotal().getCurrency();
    Bundle.Builder bundle =
        Bundle.newBuilder().setBundleId("bdl_" + newLeg.getOfferId().substring(4));
    List<String> touched = new ArrayList<>();
    List<ComponentChange> changes = new ArrayList<>();
    long incremental = 0;

    Offer taggedLeg = newLeg.toBuilder().setComponentId(legId).build();
    bundle.addOffers(taggedLeg);
    touched.add(legId);
    long legDelta = newLeg.getTotal().getAmountMinor() - affected.getTotal().getAmountMinor();
    incremental += legDelta;
    changes.add(
        change(
            legId,
            "AIR",
            "REPLACED",
            affected.getTotal(),
            newLeg.getTotal(),
            currency,
            "flight replaced"));

    Journey out = newLeg.getAir().getOutbound();
    Instant newDeparture = instant(out.getSegments(0).getDeparture());
    Instant newArrival = instant(out.getSegments(out.getSegmentsCount() - 1).getArrival());

    // transfers that meet this leg
    for (Transfer t : itinerary.getTransfersList()) {
      if (!t.getDependsOnList().contains(legId)) {
        continue;
      }
      OrderItem current = confirmed(order, t.getComponentId());
      if (current == null) {
        continue;
      }
      boolean arriving = "AIRPORT_TO_HOTEL".equals(t.getKind());
      Instant pickup = instant(current.getOffer().getGround().getPickup());
      Instant dropoff = instant(current.getOffer().getGround().getDropoff());
      boolean stillFits =
          arriving
              ? !pickup.isBefore(newArrival.plus(TRANSFER_AFTER_ARRIVAL))
                  && !pickup.isAfter(newArrival.plus(TRANSFER_REACH))
              : !dropoff.isAfter(newDeparture.minus(TRANSFER_BEFORE_DEPARTURE))
                  && !pickup.isBefore(newDeparture.minus(TRANSFER_EARLIEST_BEFORE));
      if (stillFits) {
        touched.add(t.getComponentId());
        changes.add(
            change(
                t.getComponentId(),
                "GROUND",
                "PRESERVED",
                current.getTotal(),
                current.getTotal(),
                currency,
                "pickup still meets the new flight"));
        continue;
      }
      TimeWindow window =
          arriving
              ? window(newArrival.plus(TRANSFER_AFTER_ARRIVAL), newArrival.plus(TRANSFER_REACH))
              : window(
                  newDeparture.minus(TRANSFER_EARLIEST_BEFORE),
                  newDeparture.minus(TRANSFER_BEFORE_DEPARTURE));
      List<Offer> offers =
          activities
              .searchGround(
                  SearchGroundRequest.newBuilder()
                      .setCtx(ctx.apply(""))
                      .setCity(t.getCity())
                      .setKind(t.getKind())
                      .setFromLocation(t.getFromLocation())
                      .setToLocation(t.getToLocation())
                      .setPickup(window)
                      .setPassengers(1)
                      .build())
              .getOffersList();
      String vendor = current.getOffer().getGround().getVendorId();
      Offer chosen =
          offers.stream()
              .filter(o -> o.getGround().getVendorId().equals(vendor))
              .min(
                  Comparator.comparingLong(
                      o -> instant(o.getGround().getPickup()).getEpochSecond()))
              .or(
                  () ->
                      offers.stream()
                          .min(Comparator.comparingLong(o -> o.getTotal().getAmountMinor())))
              .orElse(null);
      touched.add(t.getComponentId());
      if (chosen == null) {
        changes.add(
            change(
                t.getComponentId(),
                "GROUND",
                "CANCELLED",
                current.getTotal(),
                null,
                currency,
                "no transfer meets the new flight"));
        continue;
      }
      boolean sameVendor = chosen.getGround().getVendorId().equals(vendor);
      bundle.addOffers(chosen.toBuilder().setComponentId(t.getComponentId()).build());
      incremental += chosen.getTotal().getAmountMinor() - current.getTotal().getAmountMinor();
      changes.add(
          change(
              t.getComponentId(),
              "GROUND",
              sameVendor ? "RETIMED" : "REPLACED",
              current.getTotal(),
              chosen.getTotal(),
              currency,
              (sameVendor ? "re-timed" : "re-booked") + " to meet the new flight"));
    }

    // stays that begin with this leg's landing or end with its departure
    for (Stay s : itinerary.getStaysList()) {
      boolean begins = s.getDependsOnCount() > 0 && s.getDependsOn(0).equals(legId);
      boolean ends = s.getDependsOnCount() > 1 && s.getDependsOn(1).equals(legId);
      if (!begins && !ends) {
        continue;
      }
      OrderItem current = confirmed(order, s.getComponentId());
      if (current == null) {
        continue;
      }
      ZoneId zone = ZoneId.of(s.getTimeZone());
      String checkIn = current.getOffer().getHotel().getCheckInDate();
      String checkOut = current.getOffer().getHotel().getCheckOutDate();
      String newCheckIn = begins ? newArrival.atZone(zone).toLocalDate().toString() : checkIn;
      String newCheckOut = ends ? newDeparture.atZone(zone).toLocalDate().toString() : checkOut;
      if (newCheckIn.compareTo(newCheckOut) >= 0) {
        newCheckOut = java.time.LocalDate.parse(newCheckIn).plusDays(1).toString();
      }
      if (newCheckIn.equals(checkIn) && newCheckOut.equals(checkOut)) {
        touched.add(s.getComponentId());
        changes.add(
            change(
                s.getComponentId(),
                "HOTEL",
                "PRESERVED",
                current.getTotal(),
                current.getTotal(),
                currency,
                "the stay still covers every night"));
        continue;
      }
      List<Offer> offers =
          activities
              .searchHotels(
                  SearchHotelsRequest.newBuilder()
                      .setCtx(ctx.apply(""))
                      .setCity(s.getCity())
                      .setCheckInDate(newCheckIn)
                      .setCheckOutDate(newCheckOut)
                      .setGuests(1)
                      .build())
              .getOffersList();
      String property = current.getOffer().getHotel().getPropertyId();
      Offer chosen =
          offers.stream()
              .filter(o -> o.getHotel().getPropertyId().equals(property))
              .findFirst()
              .or(
                  () ->
                      offers.stream()
                          .min(Comparator.comparingLong(o -> o.getTotal().getAmountMinor())))
              .orElse(null);
      touched.add(s.getComponentId());
      if (chosen == null) {
        changes.add(
            change(
                s.getComponentId(),
                "HOTEL",
                "CANCELLED",
                current.getTotal(),
                null,
                currency,
                "no room for the new dates"));
        continue;
      }
      boolean sameProperty = chosen.getHotel().getPropertyId().equals(property);
      bundle.addOffers(chosen.toBuilder().setComponentId(s.getComponentId()).build());
      incremental += chosen.getTotal().getAmountMinor() - current.getTotal().getAmountMinor();
      changes.add(
          change(
              s.getComponentId(),
              "HOTEL",
              sameProperty ? "RETIMED" : "REPLACED",
              current.getTotal(),
              chosen.getTotal(),
              currency,
              "stay moved to " + newCheckIn + " to " + newCheckOut));
    }

    // the next leg, when the new landing breaks the chain to it
    for (Leg next : itinerary.getLegsList()) {
      if (!next.getDependsOnList().contains(legId)) {
        continue;
      }
      OrderItem current = confirmed(order, next.getComponentId());
      if (current == null || !current.getOffer().hasAir()) {
        continue;
      }
      Instant nextDeparture =
          instant(current.getOffer().getAir().getOutbound().getSegments(0).getDeparture());
      if (!nextDeparture.isBefore(newArrival.plus(MIN_CONNECTION))) {
        continue; // still reachable: untouched
      }
      List<Offer> offers =
          activities
              .search(
                  SearchAirRequest.newBuilder()
                      .setCtx(ctx.apply(""))
                      .setOrigin(next.getOrigin())
                      .setDestination(next.getDestination())
                      .setPassengers(1)
                      .addCabins(Cabin.ECONOMY)
                      .setOutboundDeparture(
                          window(
                              newArrival.plus(MIN_CONNECTION), instant(next.getArrivalDeadline())))
                      .build())
              .getOffersList();
      Offer chosen =
          offers.stream()
              .filter(
                  o ->
                      !instant(o.getAir().getOutbound().getSegments(0).getDeparture())
                          .isBefore(newArrival.plus(MIN_CONNECTION)))
              .min(Comparator.comparingLong(o -> o.getTotal().getAmountMinor()))
              .orElse(null);
      touched.add(next.getComponentId());
      if (chosen == null) {
        changes.add(
            change(
                next.getComponentId(),
                "AIR",
                "CANCELLED",
                current.getTotal(),
                null,
                currency,
                "no onward flight after the new landing"));
        continue;
      }
      bundle.addOffers(chosen.toBuilder().setComponentId(next.getComponentId()).build());
      incremental += chosen.getTotal().getAmountMinor() - current.getTotal().getAmountMinor();
      changes.add(
          change(
              next.getComponentId(),
              "AIR",
              "REPLACED",
              current.getTotal(),
              chosen.getTotal(),
              currency,
              "onward leg re-chained after the new landing"));
    }

    long total =
        bundle.getOffersList().stream().mapToLong(o -> o.getTotal().getAmountMinor()).sum();
    bundle.setTotal(Money.newBuilder().setCurrency(currency).setAmountMinor(total));
    return new Plan(
        bundle.build(),
        List.copyOf(touched),
        List.copyOf(changes),
        Money.newBuilder().setCurrency(currency).setAmountMinor(incremental).build());
  }

  static @Nullable OrderItem confirmed(Order order, String componentId) {
    OrderItem found = null;
    for (OrderItem item : order.getItemsList()) {
      if (item.getStatus() == OrderItemStatus.ITEM_CONFIRMED
          && item.getComponentId().equals(componentId)) {
        found = item;
      }
    }
    return found;
  }

  static ComponentChange change(
      String id,
      String type,
      String action,
      Money previous,
      @Nullable Money next,
      String currency,
      String reason) {
    ComponentChange.Builder b =
        ComponentChange.newBuilder()
            .setComponentId(id)
            .setType(type)
            .setAction(action)
            .setPreviousTotal(previous)
            .setReason(reason);
    if (next != null) {
      b.setReplacementTotal(next)
          .setDelta(
              Money.newBuilder()
                  .setCurrency(currency)
                  .setAmountMinor(next.getAmountMinor() - previous.getAmountMinor()));
    } else {
      b.setDelta(
          Money.newBuilder().setCurrency(currency).setAmountMinor(-previous.getAmountMinor()));
    }
    return b.build();
  }

  static Instant instant(Timestamp ts) {
    return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
  }

  static TimeWindow window(Instant from, Instant to) {
    return TimeWindow.newBuilder()
        .setNotBefore(Timestamp.newBuilder().setSeconds(from.getEpochSecond()))
        .setNotAfter(Timestamp.newBuilder().setSeconds(to.getEpochSecond()))
        .build();
  }

  static String json(Offer o) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(o);
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      return "{}";
    }
  }
}
