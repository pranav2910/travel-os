package io.travelos.travelcore.trip;

/** Where the request came from. Mirrors the {@code source} enum in trip-events.schema.json. */
public enum TripSource {
  WEB,
  API,
  AGENT,
  CALENDAR,
  EMAIL,
  /** Slice 4: converted from a detected travel demand; the trip carries the candidate id. */
  DEMAND,
  /** Phase 3: a turn of a persisted conversation; the trip carries the conversation id. */
  CONVERSATION
}
