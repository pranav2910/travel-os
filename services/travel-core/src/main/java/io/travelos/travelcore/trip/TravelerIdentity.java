package io.travelos.travelcore.trip;

import org.jspecify.annotations.Nullable;

/**
 * Who travels, as the arranger states it. A reservation is made in a person's name, so a trip
 * arranged for someone else cannot be accepted without this; the traveler's own trips take the
 * identity from their token instead.
 */
public record TravelerIdentity(
    @Nullable String givenName, @Nullable String familyName, @Nullable String email) {

  boolean complete() {
    return !blank(givenName) && !blank(familyName) && !blank(email) && email.indexOf('@') > 0;
  }

  TravelerSnapshot snapshot(String travelerId) {
    return new TravelerSnapshot(travelerId, givenName.trim(), familyName.trim(), email.trim());
  }

  /** The canonical form for the idempotency fingerprint; empty when nothing was given. */
  String canonical() {
    return String.join(
        "|",
        givenName == null ? "" : givenName.strip(),
        familyName == null ? "" : familyName.strip(),
        email == null ? "" : email.strip().toLowerCase(java.util.Locale.ROOT));
  }

  private static boolean blank(@Nullable String s) {
    return s == null || s.isBlank();
  }
}
