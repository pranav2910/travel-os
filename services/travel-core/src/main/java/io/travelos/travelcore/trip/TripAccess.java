package io.travelos.travelcore.trip;

import io.travelos.spring.web.auth.RequestPrincipal;

/**
 * Who may see or act on a trip. Tenant scoping happens in the repository (every query filters by
 * tenant); this is the within-tenant rule. Slice 1: travelers see their own trips; MANAGER,
 * TRAVEL_ADMIN and FINANCE see the tenant's. Manager-to-report scoping arrives with the Enterprise
 * Context service.
 */
public final class TripAccess {

  private static final String[] TENANT_WIDE_ROLES = {"MANAGER", "TRAVEL_ADMIN", "FINANCE"};

  private TripAccess() {}

  public static boolean canRead(RequestPrincipal me, Trip trip) {
    return isOwner(me, trip) || me.hasAnyRole(TENANT_WIDE_ROLES);
  }

  public static boolean canReadTenantWide(RequestPrincipal me) {
    return me.hasAnyRole(TENANT_WIDE_ROLES);
  }

  public static boolean canCancel(RequestPrincipal me, Trip trip) {
    return isOwner(me, trip) || me.hasAnyRole("TRAVEL_ADMIN");
  }

  /** Booking on behalf of someone else is an arranger privilege. */
  public static boolean canCreateFor(RequestPrincipal me, String travelerId) {
    return travelerId.equals(me.employeeId()) || me.hasAnyRole("TRAVEL_ADMIN", "MANAGER");
  }

  private static boolean isOwner(RequestPrincipal me, Trip trip) {
    return trip.travelerId().equals(me.employeeId());
  }
}
