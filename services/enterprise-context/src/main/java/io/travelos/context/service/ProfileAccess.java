package io.travelos.context.service;

import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.ArrangerGrant;
import io.travelos.context.model.Employee;
import io.travelos.context.model.TravelerProfile;
import io.travelos.context.store.ArrangerRepository;
import io.travelos.context.store.EmployeeRepository;
import io.travelos.context.store.OrgRepository;
import io.travelos.context.store.ProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The relationship between a principal and a traveler, resolved from records this service owns: the
 * HRIS manager link, a guest's sponsor, an explicit arranger grant, or the TRAVEL_ADMIN role.
 * Nothing in a request body can create a relationship; a MANAGER realm role alone is not one.
 */
@Component
public class ProfileAccess {

  public enum Relation {
    SELF,
    TRAVEL_ADMIN,
    SPONSOR,
    /** The traveler's manager as the HRIS states it. */
    MANAGER,
    /** An explicit grant covering the traveler, without document access. */
    GRANT,
    /** An explicit grant covering the traveler that also allows reading document numbers. */
    GRANT_WITH_DOCUMENTS,
    /** FINANCE: names and cost allocation, nothing sensitive. */
    FINANCE,
    /**
     * Phase 4: the platform's own machine identity (an agent or service principal) executing a
     * booking the platform already authorized. Reads what a supplier needs, every read logged under
     * the machine principal with purpose BOOKING. Never a person's relationship.
     */
    SYSTEM,
    NONE
  }

  /**
   * Who the caller is: the ids the grants and the HRIS link are keyed by.
   *
   * @param system a machine identity (agent/service principal) acting for the platform
   */
  public record Caller(@Nullable String employeeId, Set<String> roles, boolean system) {
    public Caller {
      roles = Set.copyOf(roles);
    }

    public Caller(@Nullable String employeeId, Set<String> roles) {
      this(employeeId, roles, false);
    }

    public boolean has(String role) {
      return roles.contains(role);
    }
  }

  private final EmployeeRepository employees;
  private final ProfileRepository profiles;
  private final ArrangerRepository grants;
  private final OrgRepository org;
  private final Clock clock;

  public ProfileAccess(
      EmployeeRepository employees,
      ProfileRepository profiles,
      ArrangerRepository grants,
      OrgRepository org,
      Clock clock) {
    this.employees = employees;
    this.profiles = profiles;
    this.grants = grants;
    this.org = org;
    this.clock = clock;
  }

  public Relation relation(TenantId tenant, Caller me, String travelerId) {
    if (me.system()) {
      return Relation.SYSTEM;
    }
    if (me.employeeId() != null && me.employeeId().equals(travelerId)) {
      return Relation.SELF;
    }
    if (me.has("TRAVEL_ADMIN")) {
      return Relation.TRAVEL_ADMIN;
    }
    Optional<Employee> employee = employees.find(tenant, travelerId);
    Optional<TravelerProfile> profile = profiles.find(tenant, travelerId, false);
    if (me.employeeId() != null) {
      if (profile.map(p -> me.employeeId().equals(p.sponsorEmployeeId())).orElse(false)) {
        return Relation.SPONSOR;
      }
      Relation byGrant = byGrant(tenant, me.employeeId(), travelerId, employee, profile);
      if (byGrant != Relation.NONE) {
        return byGrant;
      }
      if (employee.map(e -> me.employeeId().equals(e.managerEmployeeId())).orElse(false)) {
        return Relation.MANAGER;
      }
    }
    return me.has("FINANCE") ? Relation.FINANCE : Relation.NONE;
  }

  /** The strongest active grant the arranger holds over the traveler. */
  private Relation byGrant(
      TenantId tenant,
      String arrangerId,
      String travelerId,
      Optional<Employee> employee,
      Optional<TravelerProfile> profile) {
    Instant now = clock.instant();
    List<ArrangerGrant> active =
        grants.byArranger(tenant, arrangerId).stream().filter(g -> g.activeAt(now)).toList();
    if (active.isEmpty()) {
      return Relation.NONE;
    }
    Set<String> units =
        employee
            .map(
                e -> {
                  java.util.HashSet<String> s = new java.util.HashSet<>();
                  if (e.departmentId() != null) s.add(e.departmentId());
                  if (e.costCenterId() != null) s.add(e.costCenterId());
                  if (e.legalEntityId() != null) s.add(e.legalEntityId());
                  if (e.officeId() != null) s.add(e.officeId());
                  return (Set<String>) s;
                })
            .orElse(Set.of());
    Relation best = Relation.NONE;
    for (ArrangerGrant g : active) {
      boolean covers =
          switch (g.scope()) {
            case TENANT -> true;
            case EMPLOYEE -> travelerId.equals(g.scopeId());
            case ORG_UNIT -> g.scopeId() != null && units.contains(g.scopeId());
            case PROJECT -> g.scopeId() != null && org.isMember(tenant, g.scopeId(), travelerId);
          };
      if (!covers) {
        continue;
      }
      if (g.mayReadDocuments()) {
        return Relation.GRANT_WITH_DOCUMENTS;
      }
      best = Relation.GRANT;
    }
    return best;
  }

  /** May the principal arrange travel (create trips, edit passenger details) for the traveler? */
  public static boolean mayArrange(Relation r) {
    return switch (r) {
      case SELF, TRAVEL_ADMIN, SPONSOR, MANAGER, GRANT, GRANT_WITH_DOCUMENTS -> true;
      case FINANCE, SYSTEM, NONE -> false;
    };
  }

  /** May the principal see the redacted profile (names, email, preferences, masked numbers)? */
  public static boolean mayReadProfile(Relation r) {
    return r != Relation.NONE;
  }

  /** May the principal see phone, date of birth, loyalty numbers, the emergency contact? */
  public static boolean mayRevealSensitive(Relation r) {
    return switch (r) {
      case SELF, TRAVEL_ADMIN, SPONSOR, GRANT, GRANT_WITH_DOCUMENTS, SYSTEM -> true;
      case MANAGER, FINANCE, NONE -> false;
    };
  }

  /** May the principal read document numbers? Narrower than {@link #mayRevealSensitive}. */
  public static boolean mayRevealDocuments(Relation r) {
    return switch (r) {
      case SELF, TRAVEL_ADMIN, SPONSOR, GRANT_WITH_DOCUMENTS, SYSTEM -> true;
      case MANAGER, GRANT, FINANCE, NONE -> false;
    };
  }

  public static boolean mayWrite(Relation r) {
    return switch (r) {
      case SELF, TRAVEL_ADMIN, SPONSOR, GRANT, GRANT_WITH_DOCUMENTS -> true;
      case MANAGER, FINANCE, SYSTEM, NONE -> false;
    };
  }
}
