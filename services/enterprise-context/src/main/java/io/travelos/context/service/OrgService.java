package io.travelos.context.service;

import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.Employee;
import io.travelos.context.model.OrgUnit;
import io.travelos.context.model.Project;
import io.travelos.context.model.ProjectMember;
import io.travelos.context.store.EmployeeRepository;
import io.travelos.context.store.OrgRepository;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Departments, legal entities, offices, cost centers and projects, and who is allocated where. */
@Service
public class OrgService {
  private final OrgRepository org;
  private final EmployeeRepository employees;
  private final Clock clock;

  public OrgService(OrgRepository org, EmployeeRepository employees, Clock clock) {
    this.org = org;
    this.employees = employees;
    this.clock = clock;
  }

  /** The cost allocation a trip is charged to: the employee's units, plus a project when named. */
  public record Allocation(
      @Nullable String departmentId,
      @Nullable String departmentCode,
      @Nullable String costCenterId,
      @Nullable String costCenterCode,
      @Nullable String legalEntityId,
      @Nullable String legalEntityCode,
      @Nullable String officeId,
      @Nullable String projectId,
      @Nullable String projectCode,
      boolean projectRestricted,
      @Nullable String managerEmployeeId) {}

  public Allocation allocation(TenantId tenant, String travelerId, @Nullable String projectId) {
    Optional<Employee> e = employees.find(tenant, travelerId);
    Optional<Project> p =
        projectId == null || projectId.isBlank()
            ? Optional.empty()
            : org.findProject(tenant, projectId);
    String costCenterId = e.map(Employee::costCenterId).orElse(null);
    if (p.isPresent() && p.get().costCenterId() != null) {
      costCenterId = p.get().costCenterId();
    }
    return new Allocation(
        e.map(Employee::departmentId).orElse(null),
        code(tenant, e.map(Employee::departmentId).orElse(null)),
        costCenterId,
        code(tenant, costCenterId),
        e.map(Employee::legalEntityId).orElse(null),
        code(tenant, e.map(Employee::legalEntityId).orElse(null)),
        e.map(Employee::officeId).orElse(null),
        p.map(Project::projectId).orElse(null),
        p.map(Project::code).orElse(null),
        p.map(Project::restricted).orElse(false),
        e.map(Employee::managerEmployeeId).orElse(null));
  }

  private @Nullable String code(TenantId tenant, @Nullable String unitId) {
    return unitId == null ? null : org.findUnit(tenant, unitId).map(OrgUnit::code).orElse(null);
  }

  public List<OrgUnit> units(RequestPrincipal me) {
    return org.listUnits(me.tenant());
  }

  @Transactional
  public OrgUnit createUnit(
      RequestPrincipal me,
      OrgUnit.Kind kind,
      String code,
      String name,
      @Nullable String parentUnitId,
      @Nullable String legalEntityId) {
    requireAdmin(me);
    if (org.findUnitByCode(me.tenant(), kind, code).isPresent()) {
      throw new ApiException.Conflict("ORG_UNIT_EXISTS", kind + " " + code + " already exists");
    }
    if (parentUnitId != null && org.findUnit(me.tenant(), parentUnitId).isEmpty()) {
      throw new ApiException.Unprocessable("PARENT_UNKNOWN", "parent unit " + parentUnitId);
    }
    OrgUnit u =
        new OrgUnit(
            me.tenant(),
            Ids.newId(IdPrefix.ORG_UNIT),
            kind,
            code,
            name,
            parentUnitId,
            legalEntityId,
            true,
            1,
            clock.instant());
    org.insertUnit(u);
    return u;
  }

  /**
   * Projects the caller may know about: all of them for admins and Finance, otherwise their own.
   */
  public List<Project> projects(RequestPrincipal me) {
    if (me.hasAnyRole("TRAVEL_ADMIN", "FINANCE")) {
      return org.listProjects(me.tenant());
    }
    List<Project> all = org.listProjects(me.tenant());
    List<Project> mine = org.projectsOf(me.tenant(), me.employeeIdOrThrow());
    return all.stream()
        .filter(
            p ->
                !p.restricted() || mine.stream().anyMatch(m -> m.projectId().equals(p.projectId())))
        .toList();
  }

  public Project project(RequestPrincipal me, String projectId) {
    Project p =
        org.findProject(me.tenant(), projectId)
            .orElseThrow(() -> new ApiException.NotFound("project", projectId));
    if (p.restricted()
        && !me.hasAnyRole("TRAVEL_ADMIN", "FINANCE")
        && (me.employeeId() == null || !org.isMember(me.tenant(), projectId, me.employeeId()))) {
      throw new ApiException.NotFound("project", projectId);
    }
    return p;
  }

  @Transactional
  public Project createProject(
      RequestPrincipal me,
      String code,
      String name,
      @Nullable String client,
      @Nullable String costCenterId,
      boolean restricted) {
    requireAdmin(me);
    if (org.listProjects(me.tenant()).stream().anyMatch(p -> p.code().equals(code))) {
      throw new ApiException.Conflict("PROJECT_EXISTS", "project " + code + " already exists");
    }
    Project p =
        new Project(
            me.tenant(),
            Ids.newId(IdPrefix.PROJECT),
            code,
            name,
            client,
            costCenterId,
            restricted,
            true,
            1,
            clock.instant());
    org.insertProject(p);
    return p;
  }

  @Transactional
  public ProjectMember addMember(
      RequestPrincipal me, String projectId, String employeeId, String role) {
    requireAdmin(me);
    org.findProject(me.tenant(), projectId)
        .orElseThrow(() -> new ApiException.NotFound("project", projectId));
    if (employees.find(me.tenant(), employeeId).isEmpty()) {
      throw new ApiException.Unprocessable("EMPLOYEE_UNKNOWN", "no employee " + employeeId);
    }
    ProjectMember m = new ProjectMember(projectId, employeeId, role, clock.instant());
    org.addMember(me.tenant(), m);
    return m;
  }

  public List<ProjectMember> members(RequestPrincipal me, String projectId) {
    project(me, projectId);
    return org.members(me.tenant(), projectId);
  }

  public boolean isMember(TenantId tenant, String projectId, String employeeId) {
    return org.isMember(tenant, projectId, employeeId);
  }

  public Optional<Project> findProject(TenantId tenant, String projectId) {
    return org.findProject(tenant, projectId);
  }

  private static void requireAdmin(RequestPrincipal me) {
    if (!me.hasRole("TRAVEL_ADMIN")) {
      throw new ApiException.Forbidden(
          "TRAVEL_ADMIN_REQUIRED", "organization changes are a travel admin action");
    }
  }
}
