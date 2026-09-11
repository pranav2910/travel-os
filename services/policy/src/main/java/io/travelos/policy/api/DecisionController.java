package io.travelos.policy.api;

import io.travelos.policy.store.DecisionRepository;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Why was this allowed / denied / sent for approval?" Travelers read decisions about their own
 * trips; MANAGER, TRAVEL_ADMIN and FINANCE read the tenant's. Anything else is 404.
 */
@RestController
@RequestMapping(path = "/api/v1/policy-decisions", produces = "application/json")
public class DecisionController {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String[] TENANT_WIDE = {"MANAGER", "TRAVEL_ADMIN", "FINANCE"};

  private final DecisionRepository decisions;

  public DecisionController(DecisionRepository decisions) {
    this.decisions = decisions;
  }

  public record DecisionResponse(
      String decisionId,
      String evaluationId,
      String tripId,
      String travelerId,
      @Nullable String bundleId,
      @Nullable String action,
      String policyId,
      int policyVersion,
      String outcome,
      boolean requiresApproval,
      String evaluatedFor,
      Instant evaluatedAt,
      JsonNode decision) {

    static DecisionResponse from(DecisionRepository.Record r) {
      return new DecisionResponse(
          r.decisionId(),
          r.evaluationId(),
          r.tripId(),
          r.travelerId(),
          r.bundleId(),
          r.action(),
          r.policyId(),
          r.policyVersion(),
          r.outcome(),
          r.requiresApproval(),
          r.evaluatedFor().id(),
          r.evaluatedAt(),
          JSON.readTree(r.decisionJson()));
    }
  }

  @GetMapping("/{decisionId}")
  public DecisionResponse get(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String decisionId) {
    return decisions
        .find(me.tenant(), decisionId)
        .filter(record -> canRead(me, record))
        .map(DecisionResponse::from)
        .orElseThrow(() -> new ApiException.NotFound("policy decision", decisionId));
  }

  @GetMapping
  public List<DecisionResponse> byTrip(
      @AuthenticationPrincipal RequestPrincipal me, @RequestParam String tripId) {
    return decisions.byTrip(me.tenant(), tripId).stream()
        .filter(record -> canRead(me, record))
        .map(DecisionResponse::from)
        .toList();
  }

  private static boolean canRead(RequestPrincipal me, DecisionRepository.Record record) {
    return record.travelerId().equals(me.employeeId()) || me.hasAnyRole(TENANT_WIDE);
  }
}
