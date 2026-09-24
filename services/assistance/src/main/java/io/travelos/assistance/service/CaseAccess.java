package io.travelos.assistance.service;

import io.travelos.assistance.model.AssistanceCase;
import io.travelos.assistance.model.Queue;
import io.travelos.spring.web.auth.RequestPrincipal;

/**
 * Who sees and works cases. Travel admins and Finance work every queue of their tenant (Finance
 * first on the FINANCE queue, travel admins on the rest; either may act on any, the queue is a
 * routing hint, not a permission). A traveler sees the cases on their own trips and may open one
 * for themselves. Everyone else sees nothing: another person's case is 404, never 403.
 */
public final class CaseAccess {
  private CaseAccess() {}

  public static boolean worker(RequestPrincipal me) {
    return me.hasAnyRole("TRAVEL_ADMIN", "FINANCE");
  }

  public static boolean canRead(RequestPrincipal me, AssistanceCase c) {
    if (worker(me)) {
      return true;
    }
    return c.travelerId() != null && c.travelerId().equals(me.employeeId());
  }

  public static boolean canAct(RequestPrincipal me, AssistanceCase c) {
    return worker(me);
  }

  /** The role that should take the next step on a queue, for the case's next-action hint. */
  public static String roleFor(Queue queue) {
    return switch (queue) {
      case FINANCE -> "FINANCE";
      case APPROVALS -> "MANAGER";
      case TRAVEL_OPS, SAFETY -> "TRAVEL_ADMIN";
    };
  }
}
