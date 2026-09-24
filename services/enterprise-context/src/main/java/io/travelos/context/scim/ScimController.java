package io.travelos.context.scim;

import io.travelos.context.model.Employee;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Phase 7: SCIM 2.0 (RFC 7643/7644) Users for an identity provider's provisioning (Okta, Entra ID,
 * OneLogin). Groups are not modelled: roles come from the IdP's claims at sign-in.
 */
@RestController
@RequestMapping(
    path = "/scim/v2",
    produces = {"application/scim+json", MediaType.APPLICATION_JSON_VALUE})
public class ScimController {
  private static final MediaType SCIM = MediaType.parseMediaType("application/scim+json");
  private final ScimService scim;

  public ScimController(ScimService scim) {
    this.scim = scim;
  }

  @GetMapping("/ServiceProviderConfig")
  public ResponseEntity<Map<String, Object>> serviceProviderConfig() {
    return ResponseEntity.ok().contentType(SCIM).body(ScimService.serviceProviderConfig());
  }

  @GetMapping("/Users")
  public ResponseEntity<ObjectNode> list(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(required = false) @Nullable String filter,
      @RequestParam(defaultValue = "1") int startIndex,
      @RequestParam(defaultValue = "100") int count) {
    List<Employee> all = scim.list(me.tenant(), filter);
    int from = Math.max(0, startIndex - 1);
    List<Employee> page =
        from >= all.size()
            ? List.of()
            : all.subList(from, Math.min(all.size(), from + Math.clamp(count, 1, 200)));
    ObjectNode body = scim.listResponse(page, startIndex, "/scim/v2/Users");
    body.put("totalResults", all.size());
    return ResponseEntity.ok().contentType(SCIM).body(body);
  }

  @GetMapping("/Users/{id}")
  public ResponseEntity<ObjectNode> get(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String id) {
    Employee e = scim.get(me.tenant(), id).orElseThrow(() -> new ApiException.NotFound("User", id));
    return ResponseEntity.ok().contentType(SCIM).body(scim.toScim(e, "/scim/v2/Users/" + id));
  }

  @PostMapping(
      path = "/Users",
      consumes = {"application/scim+json", MediaType.APPLICATION_JSON_VALUE})
  public ResponseEntity<ObjectNode> create(
      @AuthenticationPrincipal RequestPrincipal me, @RequestBody JsonNode user) {
    Employee e = scim.create(me, user);
    return ResponseEntity.status(HttpStatus.CREATED)
        .contentType(SCIM)
        .body(scim.toScim(e, "/scim/v2/Users/" + e.employeeId()));
  }

  @PutMapping(
      path = "/Users/{id}",
      consumes = {"application/scim+json", MediaType.APPLICATION_JSON_VALUE})
  public ResponseEntity<ObjectNode> replace(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String id,
      @RequestBody JsonNode user) {
    Employee e = scim.replace(me, id, user);
    return ResponseEntity.ok().contentType(SCIM).body(scim.toScim(e, "/scim/v2/Users/" + id));
  }

  @PatchMapping(
      path = "/Users/{id}",
      consumes = {"application/scim+json", MediaType.APPLICATION_JSON_VALUE})
  public ResponseEntity<ObjectNode> patch(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String id,
      @RequestBody JsonNode patch) {
    Employee e = scim.patch(me, id, patch);
    return ResponseEntity.ok().contentType(SCIM).body(scim.toScim(e, "/scim/v2/Users/" + id));
  }

  @DeleteMapping("/Users/{id}")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String id) {
    scim.delete(me, id);
    return ResponseEntity.noContent().build();
  }

  /** SCIM clients expect the SCIM error shape, not problem+json. */
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ObjectNode> scimError(ApiException e) {
    int status = e.status().value();
    String type =
        status == 409 ? "uniqueness" : status == 422 || status == 400 ? "invalidValue" : null;
    return ResponseEntity.status(status)
        .contentType(SCIM)
        .body(ScimService.error(status, e.getMessage(), type));
  }
}
