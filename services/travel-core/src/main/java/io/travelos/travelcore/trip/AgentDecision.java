package io.travelos.travelcore.trip;

import io.travelos.common.identity.Principal;
import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One entry in the agent-decision ledger: what an agent concluded about a trip and the model call
 * behind it. Append-only; the explainability API reads it.
 */
public record AgentDecision(
    String decisionId,
    TenantId tenantId,
    String tripId,
    Principal agent,
    String decisionType,
    String result,
    @Nullable Double confidence,
    List<String> assumptions,
    Map<String, Object> detail,
    @Nullable ModelCallEvidence call,
    Instant occurredAt) {

  public static final String INTENT_EXTRACTION = "INTENT_EXTRACTION";

  public AgentDecision {
    assumptions = List.copyOf(assumptions);
    detail = Map.copyOf(detail);
  }

  /** Mirrors travelos.common.v1.ModelCall. */
  public record ModelCallEvidence(
      String callId,
      String provider,
      String model,
      String promptId,
      int promptVersion,
      @Nullable String providerRequestId,
      long inputTokens,
      long outputTokens,
      long cacheReadTokens,
      long latencyMs,
      long costMicros,
      @Nullable Instant calledAt) {}
}
