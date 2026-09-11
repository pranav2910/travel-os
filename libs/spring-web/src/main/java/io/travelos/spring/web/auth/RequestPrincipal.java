package io.travelos.spring.web.auth;

import io.travelos.common.identity.Principal;
import io.travelos.common.tenant.TenantId;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Who is calling, resolved from a validated JWT. Available to controllers via
 * {@code @AuthenticationPrincipal RequestPrincipal}. The tenant is not optional: a token without
 * one is rejected before any controller runs.
 *
 * @param principal canonical identity, e.g. {@code human/alice}
 * @param tenant the caller's tenant — every repository call must be scoped by it
 * @param employeeId the caller's employee id when the caller is a person (traveler id in trips)
 * @param roles realm roles as issued (TRAVELER, MANAGER, TRAVEL_ADMIN, FINANCE)
 * @param givenName from the given_name claim, when the IdP issues it
 * @param familyName from the family_name claim
 * @param email from the email claim
 */
public record RequestPrincipal(
    Principal principal,
    TenantId tenant,
    @Nullable String employeeId,
    Set<String> roles,
    @Nullable String givenName,
    @Nullable String familyName,
    @Nullable String email) {

  public RequestPrincipal {
    roles = Set.copyOf(roles);
  }

  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  public boolean hasAnyRole(String... candidates) {
    for (String role : candidates) {
      if (roles.contains(role)) {
        return true;
      }
    }
    return false;
  }

  public String employeeIdOrThrow() {
    if (employeeId == null) {
      throw new IllegalStateException(principal.id() + " has no employee_id claim");
    }
    return employeeId;
  }
}
