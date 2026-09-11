package io.travelos.policy.api;

import io.travelos.policy.document.PolicyDocument;
import io.travelos.policy.document.PolicyDocuments;
import io.travelos.policy.events.PolicyEvents;
import io.travelos.policy.store.PolicyRepository;
import io.travelos.policy.store.PolicyVersion;
import io.travelos.spring.outbox.Outbox;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Travel administrators publish policy versions and choose the tenant default. Publishing is
 * idempotent by content: the same document as the current version returns it instead of creating a
 * duplicate version.
 */
@RestController
@RequestMapping(path = "/api/v1/policies", produces = "application/json")
public class PolicyAdminController {

  private final PolicyRepository policies;
  private final Outbox outbox;
  private final Clock clock;

  public PolicyAdminController(PolicyRepository policies, Outbox outbox, Clock clock) {
    this.policies = policies;
    this.outbox = outbox;
    this.clock = clock;
  }

  /**
   * @param document the policy, see PolicyDocument; amounts in minor units
   * @param note why this version exists (shows up in the audit trail)
   */
  public record PublishRequest(
      @NotNull JsonNode document, @Nullable @Size(max = 500) String note) {}

  public record SetDefaultRequest(@NotBlank @Size(max = 64) String policyId) {}

  public record VersionResponse(
      String policyId,
      int version,
      String name,
      String documentHash,
      String publishedBy,
      Instant publishedAt,
      @Nullable String note,
      JsonNode document) {

    static VersionResponse from(PolicyVersion v) {
      return new VersionResponse(
          v.policyId(),
          v.version(),
          v.document().name(),
          v.documentHash(),
          v.publishedBy().id(),
          v.publishedAt(),
          v.note(),
          PolicyDocuments.toJsonNode(v.document()));
    }
  }

  @PostMapping(consumes = "application/json")
  @PreAuthorize("hasRole('TRAVEL_ADMIN')")
  @Transactional
  public ResponseEntity<VersionResponse> publish(
      @AuthenticationPrincipal RequestPrincipal me, @Valid @RequestBody PublishRequest request) {
    PolicyDocument document = PolicyDocuments.parse(request.document());
    String hash = PolicyDocuments.hash(document);
    Optional<PolicyVersion> current = policies.current(me.tenant(), document.policyId());
    if (current.isPresent() && current.get().documentHash().equals(hash)) {
      return ResponseEntity.ok(VersionResponse.from(current.get()));
    }
    PolicyVersion published =
        policies.publish(
            me.tenant(), document, hash, me.principal(), request.note(), clock.instant());
    outbox.append(
        PolicyEvents.published(
            me.tenant(),
            published.policyId(),
            published.version(),
            me.principal(),
            hash,
            request.note(),
            clock));
    return ResponseEntity.created(
            URI.create(
                "/api/v1/policies/" + published.policyId() + "/versions/" + published.version()))
        .body(VersionResponse.from(published));
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('TRAVEL_ADMIN','MANAGER','FINANCE')")
  public List<PolicyVersion.Summary> list(@AuthenticationPrincipal RequestPrincipal me) {
    return policies.list(me.tenant());
  }

  @GetMapping("/{policyId}")
  @PreAuthorize("hasAnyRole('TRAVEL_ADMIN','MANAGER','FINANCE')")
  public VersionResponse current(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String policyId) {
    return policies
        .current(me.tenant(), policyId)
        .map(VersionResponse::from)
        .orElseThrow(() -> new ApiException.NotFound("policy", policyId));
  }

  @GetMapping("/{policyId}/versions/{version}")
  @PreAuthorize("hasAnyRole('TRAVEL_ADMIN','MANAGER','FINANCE')")
  public VersionResponse version(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String policyId,
      @PathVariable int version) {
    return policies
        .version(me.tenant(), policyId, version)
        .map(VersionResponse::from)
        .orElseThrow(() -> new ApiException.NotFound("policy version", policyId + "/" + version));
  }

  @PutMapping(path = "/default", consumes = "application/json")
  @PreAuthorize("hasRole('TRAVEL_ADMIN')")
  @Transactional
  public VersionResponse setDefault(
      @AuthenticationPrincipal RequestPrincipal me, @Valid @RequestBody SetDefaultRequest request) {
    if (!policies.setDefault(me.tenant(), request.policyId(), me.principal(), clock.instant())) {
      throw new ApiException.NotFound("policy", request.policyId());
    }
    return policies
        .current(me.tenant(), request.policyId())
        .map(VersionResponse::from)
        .orElseThrow();
  }

  @ExceptionHandler(PolicyDocuments.InvalidPolicyException.class)
  public ProblemDetail invalidPolicy(PolicyDocuments.InvalidPolicyException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_CONTENT, "policy document is invalid");
    problem.setTitle("POLICY_INVALID");
    problem.setProperty("code", "POLICY_INVALID");
    problem.setProperty("problems", e.problems());
    return problem;
  }
}
