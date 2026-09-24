package io.travelos.travelcore.trip;

import io.travelos.common.geo.Locations;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Phase 8: the trip as a traveler carries it: an iCalendar (RFC 5545) with one event per leg, stay
 * and transfer (confirmed components name the supplier reference), and the same as JSON for wallets
 * and hand-offs. Built from the frozen intent and the reported components; nothing here is
 * authoritative for the reservation (the Order service is).
 */
public final class ItineraryExport {
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

  private ItineraryExport() {}

  /** One line of the itinerary: what, where, when, and the supplier's reference when confirmed. */
  public record Item(
      String kind,
      @Nullable String componentId,
      String title,
      @Nullable String location,
      @Nullable Instant start,
      @Nullable Instant end,
      @Nullable LocalDate startDate,
      @Nullable LocalDate endDate,
      @Nullable String status,
      @Nullable String provider,
      @Nullable String reference,
      @Nullable String summary) {}

  public record Summary(
      String tripId, String status, String traveler, @Nullable String purpose, List<Item> items) {}

  public static Summary summary(Trip trip, List<TripComponent> components) {
    Map<String, TripComponent> byId = new LinkedHashMap<>();
    for (TripComponent c : components) {
      byId.put(c.componentId(), c);
    }
    List<Item> items = new ArrayList<>();
    TravelIntent intent = trip.intent();
    if (intent != null && intent.itinerary() != null && !intent.itinerary().legs().isEmpty()) {
      Itinerary it = intent.itinerary();
      for (Itinerary.Leg leg : it.legs()) {
        TripComponent c = byId.get(leg.componentId());
        items.add(
            new Item(
                "FLIGHT",
                leg.componentId(),
                "Flight " + leg.origin() + " to " + leg.destination(),
                leg.origin() + " -> " + leg.destination(),
                leg.earliestDeparture(),
                leg.arrivalDeadline(),
                null,
                null,
                status(c),
                provider(c),
                reference(c),
                summaryOf(c)));
      }
      for (Itinerary.Stay stay : it.stays()) {
        TripComponent c = byId.get(stay.componentId());
        items.add(
            new Item(
                "STAY",
                stay.componentId(),
                "Stay in " + place(stay.city()),
                place(stay.city()),
                null,
                null,
                stay.checkIn(),
                stay.checkOut(),
                status(c),
                provider(c),
                reference(c),
                summaryOf(c)));
      }
      for (Itinerary.Transfer t : it.transfers()) {
        TripComponent c = byId.get(t.componentId());
        items.add(
            new Item(
                "TRANSFER",
                t.componentId(),
                t.kind().replace('_', ' ').toLowerCase(java.util.Locale.ROOT)
                    + " in "
                    + place(t.city()),
                (t.from() == null ? "" : t.from()) + (t.to() == null ? "" : " -> " + t.to()),
                t.pickup(),
                t.pickup() == null ? null : t.pickup().plusSeconds(3600),
                null,
                null,
                status(c),
                provider(c),
                reference(c),
                summaryOf(c)));
      }
    } else if (intent != null) {
      TripComponent air =
          components.stream().filter(c -> "AIR".equals(c.type())).findFirst().orElse(null);
      items.add(
          new Item(
              "FLIGHT",
              air == null ? null : air.componentId(),
              "Flight " + intent.origin() + " to " + intent.destination(),
              intent.origin() + " -> " + intent.destination(),
              intent.earliestDeparture(),
              intent.arrivalDeadline(),
              null,
              null,
              status(air),
              provider(air),
              reference(air),
              summaryOf(air)));
      if (intent.returnAfter() != null) {
        items.add(
            new Item(
                "FLIGHT",
                null,
                "Flight " + intent.destination() + " to " + intent.origin(),
                intent.destination() + " -> " + intent.origin(),
                intent.returnAfter(),
                intent.latestReturn() == null
                    ? intent.returnAfter().plusSeconds(6 * 3600)
                    : intent.latestReturn(),
                null,
                null,
                status(air),
                provider(air),
                reference(air),
                null));
      }
      TripComponent hotel =
          components.stream().filter(c -> "HOTEL".equals(c.type())).findFirst().orElse(null);
      if (intent.hotelRequired() || hotel != null) {
        LocalDate in =
            Locations.localDate(
                intent.arrivalDeadline(),
                Locations.zoneOf(intent.destination()).orElse(ZoneOffset.UTC));
        LocalDate out =
            intent.returnAfter() == null
                ? in.plusDays(1)
                : Locations.localDate(
                    intent.returnAfter(),
                    Locations.zoneOf(intent.destination()).orElse(ZoneOffset.UTC));
        items.add(
            new Item(
                "STAY",
                hotel == null ? null : hotel.componentId(),
                "Stay in " + place(intent.destination()),
                place(intent.destination()),
                null,
                null,
                in,
                out.isAfter(in) ? out : in.plusDays(1),
                status(hotel),
                provider(hotel),
                reference(hotel),
                summaryOf(hotel)));
      }
    }
    String traveler =
        trip.traveler() == null
            ? trip.travelerId()
            : (trip.traveler().givenName() + " " + trip.traveler().familyName()).trim();
    return new Summary(
        trip.tripId(),
        trip.status().name(),
        traveler,
        intent == null ? null : intent.purpose(),
        items);
  }

  /** RFC 5545, CRLF line endings, folded at 75 octets, UTC stamps; all-day stays. */
  public static String ics(Trip trip, List<TripComponent> components, Instant now) {
    Summary s = summary(trip, components);
    StringBuilder out = new StringBuilder();
    line(out, "BEGIN:VCALENDAR");
    line(out, "VERSION:2.0");
    line(out, "PRODID:-//TravelOS//Itinerary//EN");
    line(out, "CALSCALE:GREGORIAN");
    line(out, "METHOD:PUBLISH");
    line(
        out,
        "X-WR-CALNAME:"
            + escape("Trip " + trip.tripId() + (s.purpose() == null ? "" : " - " + s.purpose())));
    int n = 0;
    for (Item item : s.items()) {
      n++;
      line(out, "BEGIN:VEVENT");
      line(
          out,
          "UID:"
              + trip.tripId()
              + "-"
              + (item.componentId() == null ? "item" + n : item.componentId())
              + "@travelos");
      line(out, "DTSTAMP:" + STAMP.format(now));
      if (item.startDate() != null) {
        line(out, "DTSTART;VALUE=DATE:" + DAY.format(item.startDate()));
        line(
            out,
            "DTEND;VALUE=DATE:"
                + DAY.format(
                    item.endDate() == null ? item.startDate().plusDays(1) : item.endDate()));
      } else if (item.start() != null) {
        line(out, "DTSTART:" + STAMP.format(item.start()));
        line(
            out,
            "DTEND:"
                + STAMP.format(item.end() == null ? item.start().plusSeconds(3600) : item.end()));
      }
      String title = item.title() + (item.summary() != null ? " (" + item.summary() + ")" : "");
      line(out, "SUMMARY:" + escape(title));
      if (item.location() != null) {
        line(out, "LOCATION:" + escape(item.location()));
      }
      StringBuilder desc =
          new StringBuilder("Trip " + trip.tripId() + " for " + s.traveler() + ".");
      if (item.status() != null) {
        desc.append(" Status: ").append(item.status()).append(".");
      }
      if (item.reference() != null) {
        desc.append(" Reference: ")
            .append(item.reference())
            .append(item.provider() == null ? "" : " (" + item.provider() + ")")
            .append(".");
      }
      line(out, "DESCRIPTION:" + escape(desc.toString()));
      line(
          out,
          "STATUS:"
              + ("CANCELLED".equals(item.status()) || "CANCEL_FAILED".equals(item.status())
                  ? "CANCELLED"
                  : "CONFIRMED".equals(item.status()) ? "CONFIRMED" : "TENTATIVE"));
      line(out, "CATEGORIES:" + item.kind());
      line(out, "END:VEVENT");
    }
    line(out, "END:VCALENDAR");
    return out.toString();
  }

  private static void line(StringBuilder out, String text) {
    // fold at 75 octets (RFC 5545 3.1): continuation lines start with a space
    byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (bytes.length <= 75) {
      out.append(text).append("\r\n");
      return;
    }
    int at = 0;
    boolean first = true;
    while (at < text.length()) {
      int take = Math.min(first ? 75 : 74, text.length() - at);
      String chunk = text.substring(at, at + take);
      while (chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > (first ? 75 : 74)) {
        take--;
        chunk = text.substring(at, at + take);
      }
      out.append(first ? "" : " ").append(chunk).append("\r\n");
      at += take;
      first = false;
    }
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
  }

  private static String place(String code) {
    return Locations.place(code).map(p -> p.city() + " (" + code + ")").orElse(code);
  }

  private static @Nullable String status(@Nullable TripComponent c) {
    return c == null ? null : c.status();
  }

  private static @Nullable String provider(@Nullable TripComponent c) {
    return c == null ? null : c.provider();
  }

  private static @Nullable String reference(@Nullable TripComponent c) {
    return c == null ? null : c.externalRef();
  }

  private static @Nullable String summaryOf(@Nullable TripComponent c) {
    return c == null ? null : c.summary();
  }
}
