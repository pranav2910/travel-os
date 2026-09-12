package io.travelos.audit.api;

import io.travelos.audit.store.TripIndexEntry;
import io.travelos.spring.web.auth.RequestPrincipal;

/**
 * Same within-tenant rule as Travel Core: travelers see their own trips; the oversight roles see
 * all.
 */
final class AuditAccess {

  private static final String[] TENANT_WIDE = {"MANAGER", "TRAVEL_ADMIN", "FINANCE"};
  private static final String[] ADMIN = {"TRAVEL_ADMIN", "FINANCE"};

  private AuditAccess() {}

  static boolean canReadTrip(RequestPrincipal me, TripIndexEntry trip) {
    return trip.travelerId().equals(me.employeeId()) || me.hasAnyRole(TENANT_WIDE);
  }

  static boolean canQueryEvents(RequestPrincipal me) {
    return me.hasAnyRole(ADMIN);
  }
}
