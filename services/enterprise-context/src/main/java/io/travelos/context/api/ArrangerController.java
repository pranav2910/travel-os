package io.travelos.context.api;

import io.travelos.context.model.ArrangerGrant;
import io.travelos.context.service.ArrangerService;
import io.travelos.spring.web.auth.RequestPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explicit arranger permissions: who may arrange travel for whom, and whether they may see
 * documents.
 */
@RestController
@RequestMapping(path = "/api/v1/arrangers", produces = "application/json")
public class ArrangerController {
  private final ArrangerService arrangers;

  public ArrangerController(ArrangerService arrangers) {
    this.arrangers = arrangers;
  }

  public record GrantRequest(
      @NotBlank String arrangerEmployeeId,
      @NotNull ArrangerGrant.Scope scope,
      @Nullable String scopeId,
      boolean mayReadDocuments,
      @Nullable Instant expiresAt) {}

  @GetMapping
  public List<ArrangerGrant> list(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(required = false) @Nullable String arrangerEmployeeId) {
    return arrangers.list(me, arrangerEmployeeId);
  }

  @PostMapping(consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public ArrangerGrant grant(
      @AuthenticationPrincipal RequestPrincipal me, @Valid @RequestBody GrantRequest r) {
    return arrangers.grant(
        me, r.arrangerEmployeeId(), r.scope(), r.scopeId(), r.mayReadDocuments(), r.expiresAt());
  }

  @DeleteMapping("/{grantId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@AuthenticationPrincipal RequestPrincipal me, @PathVariable String grantId) {
    arrangers.revoke(me, grantId);
  }
}
