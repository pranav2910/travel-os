package io.travelos.travelcore.trip;

import io.travelos.spring.web.auth.RequestPrincipal;
import java.util.Optional;

/**
 * Who may see or act on a trip. Tenant scoping happens in the repository (every query filters by
 * tenant); this is the within-tenant rule.
 *
 * <p>Travelers see their own trips. TRAVEL_ADMIN and FINANCE see the tenant's. A MANAGER sees a
 * trip when its allocation names them as the traveler's manager (as the HRIS stated it when the
 * trip was requested) or as the arranger who asked for it; a MANAGER realm role alone is not a
 * relationship. Trips without an allocation (from before profiles, or created while Enterprise
 * Context is not configured) keep the Slice 1 rule: MANAGER tenant-wide.
 */
public final class TripAccess {

  private static final String[] TENANT_WIDE_ROLES = {"TRAVEL_ADMIN", "FINANCE"};

  private TripAccess() {}

  public static boolean canRead(
      RequestPrincipal me, Trip trip, Optional<TripAllocation> allocation) {
    if (isOwner(me, trip) || me.hasAnyRole(TENANT_WIDE_ROLES)) {
      return true;
    }
    // The arranger who asked for the trip and the traveler's manager see it, whatever their roles.
    if (allocation.isPresent()) {
      return isRelated(me, allocation.get());
    }
    return me.hasRole("MANAGER");
  }

  /** Slice 1 shape, kept for callers that have no allocation at hand (legacy rule). */
  public static boolean canRead(RequestPrincipal me, Trip trip) {
    return canRead(me, trip, Optional.empty());
  }

  public static boolean canReadTenantWide(RequestPrincipal me) {
    return me.hasAnyRole("MANAGER", "TRAVEL_ADMIN", "FINANCE");
  }

  /** May this MANAGER decide the trip's approval? TRAVEL_ADMIN always may; the traveler never. */
  public static boolean canApprove(
      RequestPrincipal me, Trip trip, Optional<TripAllocation> allocation) {
    if (isOwner(me, trip)) {
      return false;
    }
    if (me.hasRole("TRAVEL_ADMIN")) {
      return true;
    }
    return me.hasRole("MANAGER") && allocation.map(a -> isRelated(me, a)).orElse(true);
  }

  public static boolean canCancel(RequestPrincipal me, Trip trip) {
    return isOwner(me, trip) || me.hasAnyRole("TRAVEL_ADMIN");
  }

  /**
   * Booking on behalf of someone else, when Enterprise Context is not there to say who may: an
   * arranger privilege of MANAGER and TRAVEL_ADMIN (Slice 1 rule).
   */
  public static boolean canCreateFor(RequestPrincipal me, String travelerId) {
    return travelerId.equals(me.employeeId()) || me.hasAnyRole("TRAVEL_ADMIN", "MANAGER");
  }

  private static boolean isRelated(RequestPrincipal me, TripAllocation a) {
    return me.employeeId() != null
        && (me.employeeId().equals(a.managerEmployeeId())
            || me.employeeId().equals(a.arrangerEmployeeId()));
  }

  private static boolean isOwner(RequestPrincipal me, Trip trip) {
    return trip.travelerId().equals(me.employeeId());
  }
}
