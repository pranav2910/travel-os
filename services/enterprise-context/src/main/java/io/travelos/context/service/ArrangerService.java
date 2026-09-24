package io.travelos.context.service;

import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.ArrangerGrant;
import io.travelos.context.model.Employee;
import io.travelos.context.model.Project;
import io.travelos.context.model.TravelerProfile;
import io.travelos.context.service.ProfileAccess.Caller;
import io.travelos.context.service.ProfileAccess.Relation;
import io.travelos.context.store.ArrangerRepository;
import io.travelos.context.store.EmployeeRepository;
import io.travelos.context.store.OrgRepository;
import io.travelos.context.store.ProfileRepository;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit arranger permissions, and the one question Travel Core asks before it creates a trip for
 * someone: may this person arrange travel for that traveler, on that project?
 */
@Service
public class ArrangerService {
  private static final Logger log = LoggerFactory.getLogger(ArrangerService.class);

  private final ArrangerRepository grants;
  private final EmployeeRepository employees;
  private final ProfileRepository profiles;
  private final OrgRepository org;
  private final ProfileAccess access;
  private final Clock clock;

  public ArrangerService(
      ArrangerRepository grants,
      EmployeeRepository employees,
      ProfileRepository profiles,
      OrgRepository org,
      ProfileAccess access,
      Clock clock) {
    this.grants = grants;
    this.employees = employees;
    this.profiles = profiles;
    this.org = org;
    this.access = access;
    this.clock = clock;
  }

  /**
   * @param basis SELF | MANAGER | SPONSOR | GRANT | TRAVEL_ADMIN when allowed
   * @param reasonCode NOT_AN_ARRANGER | TRAVELER_INACTIVE | PROJECT_RESTRICTED | TRAVELER_UNKNOWN
   *     when refused
   */
  public record Authorization(
      boolean allowed,
      @Nullable String basis,
      @Nullable String reasonCode,
      boolean mayReadDocuments) {
    static Authorization refused(String reason) {
      return new Authorization(false, null, reason, false);
    }
  }

  public Authorization authorize(
      TenantId tenant, Caller arranger, String travelerId, @Nullable String projectId) {
    Optional<Employee> employee = employees.find(tenant, travelerId);
    Optional<TravelerProfile> profile = profiles.find(tenant, travelerId, false);
    if (employee.isEmpty() && profile.isEmpty()) {
      return Authorization.refused("TRAVELER_UNKNOWN");
    }
    boolean active =
        employee.map(Employee::active).orElse(true)
            && profile.map(TravelerProfile::active).orElse(true);
    if (!active) {
      return Authorization.refused("TRAVELER_INACTIVE");
    }
    Relation r = access.relation(tenant, arranger, travelerId);
    if (!ProfileAccess.mayArrange(r)) {
      return Authorization.refused("NOT_AN_ARRANGER");
    }
    if (projectId != null && !projectId.isBlank()) {
      Optional<Project> p = org.findProject(tenant, projectId);
      if (p.isEmpty() || !p.get().active()) {
        return Authorization.refused("PROJECT_UNKNOWN");
      }
      if (p.get().restricted()) {
        // Restricted travel is for the project's members: the traveler must be one, whoever asks.
        // The arranger must be the traveler, the traveler's manager, a member themselves, the
        // holder of a grant scoped to this very project, or a travel admin. A MANAGER realm role
        // held by someone unrelated opens nothing.
        boolean travelerIn = org.isMember(tenant, projectId, travelerId);
        boolean arrangerIn =
            r == Relation.SELF
                || r == Relation.MANAGER
                || r == Relation.TRAVEL_ADMIN
                || (arranger.employeeId() != null
                    && org.isMember(tenant, projectId, arranger.employeeId()))
                || hasProjectGrant(tenant, arranger.employeeId(), projectId);
        if (!travelerIn || !arrangerIn) {
          return Authorization.refused("PROJECT_RESTRICTED");
        }
      }
    }
    String basis =
        switch (r) {
          case SELF -> "SELF";
          case TRAVEL_ADMIN -> "TRAVEL_ADMIN";
          case SPONSOR -> "SPONSOR";
          case MANAGER -> "MANAGER";
          case GRANT, GRANT_WITH_DOCUMENTS -> "GRANT";
          case FINANCE, NONE -> throw new IllegalStateException(r.name());
        };
    return new Authorization(true, basis, null, ProfileAccess.mayRevealDocuments(r));
  }

  private boolean hasProjectGrant(TenantId tenant, @Nullable String arrangerId, String projectId) {
    if (arrangerId == null) {
      return false;
    }
    Instant now = clock.instant();
    return grants.byArranger(tenant, arrangerId).stream()
        .anyMatch(
            g ->
                g.activeAt(now)
                    && ((g.scope() == ArrangerGrant.Scope.PROJECT && projectId.equals(g.scopeId()))
                        || g.scope() == ArrangerGrant.Scope.TENANT));
  }

  // ------------------------------------------------------------------ grant administration

  public List<ArrangerGrant> list(RequestPrincipal me, @Nullable String arrangerEmployeeId) {
    if (me.hasAnyRole("TRAVEL_ADMIN", "FINANCE")) {
      return arrangerEmployeeId == null
          ? grants.list(me.tenant())
          : grants.byArranger(me.tenant(), arrangerEmployeeId);
    }
    // Everyone may see the grants they hold; nobody else's.
    return grants.byArranger(me.tenant(), me.employeeIdOrThrow());
  }

  @Transactional
  public ArrangerGrant grant(
      RequestPrincipal me,
      String arrangerEmployeeId,
      ArrangerGrant.Scope scope,
      @Nullable String scopeId,
      boolean mayReadDocuments,
      @Nullable Instant expiresAt) {
    boolean self =
        scope == ArrangerGrant.Scope.EMPLOYEE
            && me.employeeId() != null
            && me.employeeId().equals(scopeId);
    if (!me.hasRole("TRAVEL_ADMIN") && !self) {
      throw new ApiException.Forbidden(
          "GRANT_NOT_ALLOWED",
          "only a travel admin grants arranger access; a traveler may delegate their own travel");
    }
    Employee arranger =
        employees
            .find(me.tenant(), arrangerEmployeeId)
            .orElseThrow(
                () ->
                    new ApiException.Unprocessable(
                        "ARRANGER_UNKNOWN", "no employee " + arrangerEmployeeId));
    if (!arranger.active()) {
      throw new ApiException.Unprocessable(
          "ARRANGER_INACTIVE", arrangerEmployeeId + " is inactive");
    }
    switch (scope) {
      case EMPLOYEE -> {
        if (scopeId == null
            || (employees.find(me.tenant(), scopeId).isEmpty()
                && profiles.find(me.tenant(), scopeId, false).isEmpty())) {
          throw new ApiException.Unprocessable("SCOPE_UNKNOWN", "no traveler " + scopeId);
        }
      }
      case ORG_UNIT -> {
        if (scopeId == null || org.findUnit(me.tenant(), scopeId).isEmpty()) {
          throw new ApiException.Unprocessable("SCOPE_UNKNOWN", "no org unit " + scopeId);
        }
      }
      case PROJECT -> {
        if (scopeId == null || org.findProject(me.tenant(), scopeId).isEmpty()) {
          throw new ApiException.Unprocessable("SCOPE_UNKNOWN", "no project " + scopeId);
        }
      }
      case TENANT -> scopeId = null;
    }
    Instant now = clock.instant();
    if (expiresAt != null && !expiresAt.isAfter(now)) {
      throw new ApiException.Unprocessable("EXPIRY_PAST", "expiresAt is in the past");
    }
    ArrangerGrant g =
        new ArrangerGrant(
            me.tenant(),
            Ids.newId(IdPrefix.ARRANGER_GRANT),
            arrangerEmployeeId,
            scope,
            scopeId,
            mayReadDocuments,
            me.principal().id(),
            now,
            expiresAt,
            null);
    grants.insert(g);
    log.info(
        "arranger grant {}: {} may arrange for {} {} (documents={}) by {}",
        g.grantId(),
        arrangerEmployeeId,
        scope,
        scopeId,
        mayReadDocuments,
        me.principal().id());
    return g;
  }

  @Transactional
  public void revoke(RequestPrincipal me, String grantId) {
    ArrangerGrant g =
        grants
            .find(me.tenant(), grantId)
            .orElseThrow(() -> new ApiException.NotFound("grant", grantId));
    boolean self =
        g.scope() == ArrangerGrant.Scope.EMPLOYEE
            && me.employeeId() != null
            && me.employeeId().equals(g.scopeId());
    if (!me.hasRole("TRAVEL_ADMIN") && !self) {
      throw new ApiException.Forbidden(
          "GRANT_NOT_ALLOWED", "only a travel admin or the traveler revokes a grant");
    }
    if (!grants.revoke(me.tenant(), grantId, clock.instant())) {
      throw new ApiException.Conflict("GRANT_ALREADY_REVOKED", grantId + " is already revoked");
    }
    log.info("arranger grant {} revoked by {}", grantId, me.principal().id());
  }
}
