package io.travelos.policy.events;

import io.travelos.common.identity.Principal;
import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import io.travelos.events.EventEnvelope;
import io.travelos.policy.engine.Decision;
import io.travelos.spring.grpc.RequestContexts;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Builds travel.policy.* envelopes. Shapes mirror contracts/events/policy-events.schema.json. */
public final class PolicyEvents {

  public static final String PRODUCER = "policy";

  private PolicyEvents() {}

  public static EventEnvelope published(
      TenantId tenant,
      String policyId,
      int version,
      Principal by,
      String hash,
      @Nullable String note,
      Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("policyId", policyId);
    data.put("policyVersion", version);
    data.put("publishedBy", by.id());
    data.put("documentHash", hash);
    if (note != null && !note.isBlank()) {
      data.put("note", note);
    }
    return EventEnvelope.create(
        "travel.policy.published", 1, tenant, "policy:" + policyId, null, PRODUCER, data, clock);
  }

  public static EventEnvelope evaluated(
      RequestContexts.Validated ctx,
      String tripId,
      String travelerId,
      @Nullable String bundleId,
      @Nullable String action,
      String policyId,
      int policyVersion,
      String decisionId,
      Decision decision,
      Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("decisionId", decisionId);
    data.put("tripId", tripId);
    data.put("travelerId", travelerId);
    if (bundleId != null && !bundleId.isBlank()) {
      data.put("bundleId", bundleId);
    }
    if (action != null) {
      data.put("action", action);
    }
    data.put("policyId", policyId.isEmpty() ? "NONE" : policyId);
    data.put("policyVersion", Math.max(policyVersion, 1));
    data.put("outcome", decision.outcome().name());
    data.put("rulesEvaluated", decision.rulesEvaluated());
    data.put("reasons", reasons(decision));
    data.put("requiresApproval", decision.requiresApproval());
    data.put("evaluatedFor", ctx.principal().id());
    data.put(
        "economics",
        Map.of(
            "referenceFare", money(decision.economics().referenceFare()),
            "inPolicyCeiling", money(decision.economics().inPolicyCeiling()),
            "travelerIncentive", money(decision.economics().travelerIncentive()),
            "travelerPays", money(decision.economics().travelerPays())));
    return EventEnvelope.create(
        "travel.policy.evaluated", 1, ctx.tenant(), tripId, null, PRODUCER, data, clock);
  }

  public static EventEnvelope violation(
      RequestContexts.Validated ctx,
      String tripId,
      String policyId,
      int policyVersion,
      String decisionId,
      Decision decision,
      Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("decisionId", decisionId);
    data.put("tripId", tripId);
    data.put("policyId", policyId.isEmpty() ? "NONE" : policyId);
    data.put("policyVersion", Math.max(policyVersion, 1));
    data.put("reasons", reasons(decision));
    data.put("attemptedBy", ctx.principal().id());
    return EventEnvelope.create(
        "travel.policy.violation", 1, ctx.tenant(), tripId, null, PRODUCER, data, clock);
  }

  private static List<Map<String, Object>> reasons(Decision decision) {
    List<Map<String, Object>> reasons = new ArrayList<>();
    for (Decision.Violation v : decision.violations()) {
      Map<String, Object> reason = new LinkedHashMap<>();
      reason.put("code", v.code());
      reason.put("ruleId", v.ruleId());
      reason.put("message", v.message());
      reasons.add(reason);
    }
    return reasons;
  }

  private static Map<String, Object> money(Money money) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("currency", money.currency());
    m.put("amountMinor", money.amountMinor());
    return m;
  }
}
