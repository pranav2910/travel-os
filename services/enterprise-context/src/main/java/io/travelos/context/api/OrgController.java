package io.travelos.context.api;

import io.travelos.context.model.OrgUnit;
import io.travelos.context.model.Project;
import io.travelos.context.model.ProjectMember;
import io.travelos.context.service.OrgService;
import io.travelos.spring.web.auth.RequestPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Departments, legal entities, offices, cost centers, projects and their members. */
@RestController
@RequestMapping(path = "/api/v1/org", produces = "application/json")
public class OrgController {
  private final OrgService org;

  public OrgController(OrgService org) {
    this.org = org;
  }

  public record UnitRequest(
      @NotNull OrgUnit.Kind kind,
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 200) String name,
      @Nullable String parentUnitId,
      @Nullable String legalEntityId) {}

  @GetMapping("/units")
  public List<OrgUnit> units(@AuthenticationPrincipal RequestPrincipal me) {
    return org.units(me);
  }

  @PostMapping(path = "/units", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public OrgUnit createUnit(
      @AuthenticationPrincipal RequestPrincipal me, @Valid @RequestBody UnitRequest r) {
    return org.createUnit(me, r.kind(), r.code(), r.name(), r.parentUnitId(), r.legalEntityId());
  }

  public record ProjectRequest(
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 200) String name,
      @Nullable @Size(max = 200) String client,
      @Nullable String costCenterId,
      boolean restricted) {}

  @GetMapping("/projects")
  public List<Project> projects(@AuthenticationPrincipal RequestPrincipal me) {
    return org.projects(me);
  }

  @GetMapping("/projects/{projectId}")
  public Project project(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String projectId) {
    return org.project(me, projectId);
  }

  @PostMapping(path = "/projects", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Project createProject(
      @AuthenticationPrincipal RequestPrincipal me, @Valid @RequestBody ProjectRequest r) {
    return org.createProject(me, r.code(), r.name(), r.client(), r.costCenterId(), r.restricted());
  }

  public record MemberRequest(@NotBlank String employeeId, @Nullable @Size(max = 40) String role) {}

  @GetMapping("/projects/{projectId}/members")
  public List<ProjectMember> members(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String projectId) {
    return org.members(me, projectId);
  }

  @PostMapping(path = "/projects/{projectId}/members", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public ProjectMember addMember(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String projectId,
      @Valid @RequestBody MemberRequest r) {
    return org.addMember(me, projectId, r.employeeId(), r.role() == null ? "MEMBER" : r.role());
  }
}
