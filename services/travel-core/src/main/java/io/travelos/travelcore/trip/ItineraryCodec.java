package io.travelos.travelcore.trip;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/** The itinerary as stored (JSONB) and as published in events: plain maps with ISO strings. */
public final class ItineraryCodec {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ItineraryCodec() {}

  public static Map<String, Object> toMap(Itinerary it) {
    Map<String, Object> out = new LinkedHashMap<>();
    List<Map<String, Object>> legs = new ArrayList<>();
    for (Itinerary.Leg l : it.legs()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("componentId", l.componentId());
      m.put("sequence", l.sequence());
      m.put("origin", l.origin());
      m.put("destination", l.destination());
      m.put("earliestDeparture", l.earliestDeparture().toString());
      m.put("arrivalDeadline", l.arrivalDeadline().toString());
      m.put("originTimeZone", l.originZone().getId());
      m.put("destinationTimeZone", l.destinationZone().getId());
      m.put("dependsOn", l.dependsOn());
      legs.add(m);
    }
    List<Map<String, Object>> stays = new ArrayList<>();
    for (Itinerary.Stay s : it.stays()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("componentId", s.componentId());
      m.put("city", s.city());
      m.put("checkInDate", s.checkIn().toString());
      m.put("checkOutDate", s.checkOut().toString());
      m.put("timeZone", s.zone().getId());
      m.put("nights", s.nights());
      m.put("required", s.required());
      m.put("dependsOn", s.dependsOn());
      stays.add(m);
    }
    List<Map<String, Object>> transfers = new ArrayList<>();
    for (Itinerary.Transfer t : it.transfers()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("componentId", t.componentId());
      m.put("kind", t.kind());
      m.put("city", t.city());
      m.put("fromLocation", t.from());
      m.put("toLocation", t.to());
      if (t.pickup() != null) {
        m.put("pickup", t.pickup().toString());
      }
      m.put("timeZone", t.zone().getId());
      m.put("required", t.required());
      m.put("dependsOn", t.dependsOn());
      transfers.add(m);
    }
    out.put("legs", legs);
    out.put("stays", stays);
    out.put("transfers", transfers);
    out.put("currency", it.currency());
    return out;
  }

  public static String toJson(Itinerary it) {
    return JSON.writeValueAsString(toMap(it));
  }

  @SuppressWarnings("unchecked")
  public static @Nullable Itinerary fromJson(@Nullable String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    Map<String, Object> m = JSON.readValue(json, Map.class);
    List<Itinerary.Leg> legs = new ArrayList<>();
    for (Map<String, Object> l : (List<Map<String, Object>>) m.getOrDefault("legs", List.of())) {
      legs.add(
          new Itinerary.Leg(
              (String) l.get("componentId"),
              ((Number) l.get("sequence")).intValue(),
              (String) l.get("origin"),
              (String) l.get("destination"),
              Instant.parse((String) l.get("earliestDeparture")),
              Instant.parse((String) l.get("arrivalDeadline")),
              ZoneId.of((String) l.get("originTimeZone")),
              ZoneId.of((String) l.get("destinationTimeZone")),
              (List<String>) l.getOrDefault("dependsOn", List.of())));
    }
    List<Itinerary.Stay> stays = new ArrayList<>();
    for (Map<String, Object> s : (List<Map<String, Object>>) m.getOrDefault("stays", List.of())) {
      stays.add(
          new Itinerary.Stay(
              (String) s.get("componentId"),
              (String) s.get("city"),
              LocalDate.parse((String) s.get("checkInDate")),
              LocalDate.parse((String) s.get("checkOutDate")),
              ZoneId.of((String) s.get("timeZone")),
              !Boolean.FALSE.equals(s.get("required")),
              (List<String>) s.getOrDefault("dependsOn", List.of())));
    }
    List<Itinerary.Transfer> transfers = new ArrayList<>();
    for (Map<String, Object> t :
        (List<Map<String, Object>>) m.getOrDefault("transfers", List.of())) {
      transfers.add(
          new Itinerary.Transfer(
              (String) t.get("componentId"),
              (String) t.get("kind"),
              (String) t.get("city"),
              (String) t.get("fromLocation"),
              (String) t.get("toLocation"),
              t.get("pickup") == null ? null : Instant.parse((String) t.get("pickup")),
              ZoneId.of((String) t.get("timeZone")),
              !Boolean.FALSE.equals(t.get("required")),
              (List<String>) t.getOrDefault("dependsOn", List.of())));
    }
    return new Itinerary(legs, stays, transfers, (String) m.get("currency"));
  }
}
