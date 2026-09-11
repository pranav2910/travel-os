package io.travelos.travelcore.trip;

import io.travelos.spring.web.auth.RequestPrincipal;

/**
 * The traveler as the identity provider described them when the trip was requested. Enough to book
 * a domestic flight; passports and loyalty numbers belong to the Enterprise Context service (and an
 * encrypted vault) in a later slice.
 */
public record TravelerSnapshot(
    String travelerId, String givenName, String familyName, String email) {

  /**
   * From the caller's own claims, with honest fallbacks so a booking is never blocked on a claim.
   */
  public static TravelerSnapshot of(String travelerId, RequestPrincipal me) {
    String username = me.principal().id().substring(me.principal().id().indexOf('/') + 1);
    String given = blank(me.givenName()) ? username : me.givenName();
    String family = blank(me.familyName()) ? "" : me.familyName();
    String email =
        blank(me.email()) ? username + "@" + me.tenant().value() + ".invalid" : me.email();
    return new TravelerSnapshot(travelerId, given, family, email);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
