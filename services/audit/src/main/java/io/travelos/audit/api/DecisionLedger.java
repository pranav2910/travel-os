package io.travelos.audit.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.travelos.audit.store.AuditRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The cross-service "why" for one trip, assembled from the audit trail alone. Nothing here calls
 * another service: if it is not in the events, it did not happen. The narrative is deterministic
 * text from evidence, so it is the same for everyone who asks, forever.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionLedger(
    String tripId,
    String travelerId,
    String status,
    @Nullable Map<String, Object> intent,
    @Nullable Map<String, Object> plan,
    @Nullable Map<String, Object> policy,
    @Nullable Map<String, Object> optimization,
    @Nullable Map<String, Object> approval,
    @Nullable Map<String, Object> order,
    @Nullable Map<String, Object> failure,
    List<String> narrative,
    int eventCount) {

  static DecisionLedger from(String tripId, String travelerId, List<AuditRecord> trail) {
    String status = "SUBMITTED";
    Map<String, Object> intent = null;
    Map<String, Object> plan = null;
    Map<String, Object> policy = null;
    Map<String, Object> optimization = null;
    Map<String, Object> approval = null;
    Map<String, Object> order = null;
    Map<String, Object> failure = null;
    int policyEvaluated = 0;
    int policyViolations = 0;
    Map<String, Map<String, Object>> policyByBundle = new LinkedHashMap<>();

    for (AuditRecord r : trail) {
      Map<String, Object> d = r.data();
      switch (r.eventType()) {
        case "travel.intent.detected" -> {
          intent = new LinkedHashMap<>(d);
          intent.put("occurredAt", r.occurredAt().toString());
        }
        case "travel.intent.rejected" -> {
          intent = new LinkedHashMap<>(d);
          intent.put("occurredAt", r.occurredAt().toString());
        }
        case "travel.trip.planned" -> {
          plan = new LinkedHashMap<>(d);
          plan.put("occurredAt", r.occurredAt().toString());
          status = String.valueOf(d.getOrDefault("status", status));
        }
        case "travel.policy.evaluated" -> {
          policyEvaluated++;
          Object bundle = d.get("bundleId");
          if (bundle != null) {
            policyByBundle.put(String.valueOf(bundle), d);
          }
        }
        case "travel.policy.violation" -> policyViolations++;
        case "travel.optimization.completed" -> optimization = d;
        case "travel.approval.requested" -> {
          approval = new LinkedHashMap<>(d);
          approval.put("status", "PENDING");
        }
        case "travel.approval.approved", "travel.approval.rejected" -> {
          Map<String, Object> merged = approval == null ? new LinkedHashMap<>() : approval;
          merged.putAll(d);
          merged.put("status", r.eventType().endsWith("approved") ? "APPROVED" : "REJECTED");
          approval = merged;
        }
        case "travel.order.created" -> order = new LinkedHashMap<>(d);
        case "travel.order.confirmed", "travel.order.failed", "travel.order.cancelled" -> {
          Map<String, Object> merged = order == null ? new LinkedHashMap<>() : order;
          merged.putAll(d);
          merged.put(
              "status", r.eventType().substring("travel.order.".length()).toUpperCase(Locale.ROOT));
          order = merged;
        }
        case "travel.trip.booked" -> status = "BOOKED";
        case "travel.trip.cancelled" -> status = "CANCELLED";
        case "travel.trip.failed" -> {
          status = "FAILED";
          failure = d;
        }
        default -> {}
      }
    }
    if (plan != null && plan.get("selectedBundleId") != null) {
      Map<String, Object> selected =
          policyByBundle.get(String.valueOf(plan.get("selectedBundleId")));
      if (selected != null) {
        policy = new LinkedHashMap<>(selected);
      }
    }
    if (policy == null && !policyByBundle.isEmpty() && plan == null) {
      policy = null;
    }
    if (policy != null || policyEvaluated > 0) {
      Map<String, Object> summary = policy == null ? new LinkedHashMap<>() : policy;
      summary.put("candidatesEvaluated", policyEvaluated);
      summary.put("candidatesDenied", policyViolations);
      policy = summary;
    }

    List<String> narrative = new ArrayList<>();
    if (intent != null) {
      if ("EXTRACTED".equals(intent.get("result"))) {
        narrative.add(
            String.format(
                Locale.ROOT,
                "The request was understood from free text by %s (confidence %s)%s.",
                intent.get("model"),
                intent.get("confidence"),
                assumptions(intent)));
      } else {
        narrative.add(
            "The request could not be understood: "
                + intent.getOrDefault("clarifyingQuestion", intent.get("result")));
      }
    }
    if (policy != null) {
      String policyName =
          policy.get("policyId") == null
              ? "Policy"
              : "Policy " + policy.get("policyId") + " v" + policy.get("policyVersion");
      StringBuilder s =
          new StringBuilder(policyName)
              .append(" evaluated ")
              .append(policyEvaluated)
              .append(" candidates");
      if (policyViolations > 0) {
        s.append(", ").append(policyViolations).append(" denied");
      }
      if (policy.get("outcome") != null) {
        s.append("; the selected itinerary was ").append(policy.get("outcome"));
        Object reasons = policy.get("reasons");
        if (reasons instanceof List<?> list
            && !list.isEmpty()
            && list.get(0) instanceof Map<?, ?> first
            && first.get("message") != null) {
          s.append(" (").append(first.get("message")).append(")");
        }
      }
      narrative.add(s.append('.').toString());
    }
    if (optimization != null) {
      narrative.add(
          String.format(
              Locale.ROOT,
              "The optimizer (%s) ranked %s feasible of %s candidates and selected %s with score %s.",
              optimization.get("solver"),
              optimization.get("feasibleCandidates"),
              optimization.get("candidatesEvaluated"),
              optimization.get("selectedBundleId"),
              optimization.get("selectedScore")));
    }
    if (plan != null) {
      Object total = plan.get("total");
      narrative.add(
          Boolean.TRUE.equals(plan.get("requiresApproval"))
              ? "Approval was required before booking" + money(total) + "."
              : "No approval was required" + money(total) + ".");
    }
    if (approval != null) {
      String st = String.valueOf(approval.get("status"));
      if ("PENDING".equals(st)) {
        narrative.add("Approval is pending with role " + approval.get("role") + ".");
      } else {
        narrative.add(
            "The trip was "
                + st.toLowerCase(Locale.ROOT)
                + " by "
                + approval.get("decidedBy")
                + (approval.get("comment") == null ? "" : " (\"" + approval.get("comment") + "\")")
                + ".");
      }
    }
    if (order != null) {
      String st = String.valueOf(order.get("status"));
      narrative.add(
          "CONFIRMED".equals(st)
              ? "Order "
                  + order.get("orderId")
                  + " was confirmed at "
                  + order.get("supplier")
                  + " (reference "
                  + order.get("externalOrderId")
                  + ")"
                  + money(order.get("total"))
                  + "."
              : "Order " + order.get("orderId") + " ended " + st + ".");
    }
    if (failure != null) {
      narrative.add(
          "The trip failed at "
              + failure.get("stage")
              + ": "
              + failure.get("reasonCode")
              + (failure.get("message") == null ? "" : " (" + failure.get("message") + ")")
              + ".");
    }
    return new DecisionLedger(
        tripId,
        travelerId,
        status,
        intent,
        plan,
        policy,
        optimization,
        approval,
        order,
        failure,
        narrative,
        trail.size());
  }

  private static String assumptions(Map<String, Object> intent) {
    Object a = intent.get("assumptions");
    if (a instanceof List<?> list && !list.isEmpty()) {
      return "; assumed: " + String.join("; ", list.stream().map(String::valueOf).toList());
    }
    return "";
  }

  private static String money(@Nullable Object total) {
    if (total instanceof Map<?, ?> m && m.get("currency") != null && m.get("amountMinor") != null) {
      long minor = ((Number) m.get("amountMinor")).longValue();
      return String.format(
          Locale.ROOT, " for %s %d.%02d", m.get("currency"), minor / 100, minor % 100);
    }
    return "";
  }
}
