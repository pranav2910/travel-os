package io.travelos.context.api;

import io.travelos.context.service.ProfileService;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.idempotency.IdempotencyKeyHeader;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Employee lifecycle actions that the HRIS does not drive on its own. */
@RestController
@RequestMapping(path = "/api/v1/employees", produces = "application/json")
public class EmployeeController {
  private final ProfileService profiles;

  public EmployeeController(ProfileService profiles) {
    this.profiles = profiles;
  }

  /**
   * Offboarding by a travel admin: the employee (or guest) can neither travel nor arrange; their
   * arranger grants end; their documents are revoked and enter retention. Idempotent: a second call
   * finds nothing left to change and reports zeros.
   */
  @PostMapping("/{employeeId}/deactivation")
  public ProfileService.Deactivation deactivate(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String employeeId,
      @IdempotencyKeyHeader String idempotencyKey) {
    return profiles.deactivate(me, employeeId);
  }
}
