package io.travelos.context.service;

import io.travelos.context.model.DemandCandidate;
import io.travelos.context.model.Employee;
import io.travelos.spring.web.auth.RequestPrincipal;
import java.util.Optional;

/**
 * Who may see or act on a candidate: the traveler, the traveler's manager as the HRIS states it
 * (never a display name, never a claim in a source record), and the tenant's travel admins. FINANCE
 * reads. A MANAGER realm role alone is not a relationship.
 */
public final class DemandAccess {
  private DemandAccess() {}

  public static boolean canRead(
      RequestPrincipal me, DemandCandidate c, Optional<Employee> traveler) {
    return canAct(me, c, traveler) || me.hasRole("FINANCE");
  }

  public static boolean canAct(
      RequestPrincipal me, DemandCandidate c, Optional<Employee> traveler) {
    if (c.travelerId().equals(me.employeeId()) || me.hasRole("TRAVEL_ADMIN")) {
      return true;
    }
    return me.employeeId() != null
        && traveler.map(t -> me.employeeId().equals(t.managerEmployeeId())).orElse(false);
  }

  public static boolean canManageConnectors(RequestPrincipal me) {
    return me.hasRole("TRAVEL_ADMIN");
  }

  public static boolean canReadConnectors(RequestPrincipal me) {
    return me.hasAnyRole("TRAVEL_ADMIN", "FINANCE");
  }
}
