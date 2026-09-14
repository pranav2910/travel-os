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
    List<Map<String, Object>> disruptions,
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
    Map<String, Map<String, Object>> disruptionsById = new LinkedHashMap<>();

    for (AuditRecord r : trail) {
      Map<String, Object> d = r.data();
      if (r.eventType().startsWith("travel.disruption.") || d.get("disruptionId") != null) {
        recovery(disruptionsById, r);
      }
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
    List<Map<String, Object>> disruptions = new ArrayList<>(disruptionsById.values());
    for (Map<String, Object> x : disruptions) {
      narrative.addAll(recoveryNarrative(x));
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
        disruptions,
        narrative,
        trail.size());
  }

  /** One map per disruption: what was detected, decided, approved, changed and how it ended. */
  private static void recovery(Map<String, Map<String, Object>> byId, AuditRecord r) {
    Map<String, Object> d = r.data();
    String id = String.valueOf(d.get("disruptionId"));
    Map<String, Object> x = byId.computeIfAbsent(id, k -> new LinkedHashMap<>());
    x.putIfAbsent("disruptionId", id);
    switch (r.eventType()) {
      case "travel.disruption.detected" -> {
        x.put("detected", d);
        x.put("detectedAt", r.occurredAt().toString());
        x.put("status", "DETECTED");
      }
      case "travel.disruption.impact-confirmed" -> {
        x.put("impact", d);
        x.put("status", "IMPACT_CONFIRMED");
      }
      case "travel.disruption.recovery-started" -> x.put("status", "SEARCHING_ALTERNATIVES");
      case "travel.disruption.decision-ready" -> {
        x.put("decision", d);
        x.put("status", "DECISION_READY");
      }
      case "travel.disruption.approval-required" -> {
        x.put("approval", new LinkedHashMap<>(d));
        x.put("status", "HUMAN_REQUIRED");
      }
      case "travel.approval.approved", "travel.approval.rejected" -> {
        @SuppressWarnings("unchecked")
        Map<String, Object> approval =
            x.get("approval") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : new LinkedHashMap<>();
        approval.putAll(d);
        approval.put("status", r.eventType().endsWith("approved") ? "APPROVED" : "REJECTED");
        x.put("approval", approval);
      }
      case "travel.order.change-requested" -> x.put("status", "CHANGING");
      case "travel.order.changed" -> x.put("change", d);
      case "travel.disruption.resolved" -> {
        x.put("resolution", d);
        x.put("status", "RESOLVED");
        x.put("resolvedAt", r.occurredAt().toString());
      }
      case "travel.disruption.recovery-failed" -> {
        x.put("failure", d);
        x.put("status", String.valueOf(d.getOrDefault("status", "FAILED")));
      }
      default -> {}
    }
  }

  private static List<String> recoveryNarrative(Map<String, Object> x) {
    List<String> lines = new ArrayList<>();
    Map<?, ?> detected = x.get("detected") instanceof Map<?, ?> m ? m : Map.of();
    Map<?, ?> affected = detected.get("affected") instanceof Map<?, ?> m ? m : Map.of();
    lines.add(
        String.format(
            Locale.ROOT,
            "Disruption %s: %s reported %s on %s %s-%s%s.",
            x.get("disruptionId"),
            str(detected, "supplier", "the supplier"),
            str(detected, "type", "a disruption"),
            str(affected, "flightNumber", "the flight"),
            str(affected, "origin", "?"),
            str(affected, "destination", "?"),
            detected.get("reason") == null ? "" : " (\"" + detected.get("reason") + "\")"));
    if (x.get("decision") instanceof Map<?, ?> dec) {
      lines.add(
          String.format(
              Locale.ROOT,
              "%s alternatives were searched, %s permitted by policy, %s feasible; the optimizer chose %s at %s versus the original, and policy's verdict on changing the order was %s.",
              str(dec, "candidatesSearched", "?"),
              str(dec, "candidatesPermitted", "?"),
              str(dec, "candidatesFeasible", "?"),
              str(dec, "selectedBundleId", "?"),
              signedMoney(dec.get("incrementalCost")),
              str(dec, "autonomyOutcome", "?")));
    }
    if (x.get("approval") instanceof Map<?, ?> a) {
      String status = String.valueOf(str(a, "status", "PENDING"));
      lines.add(
          "PENDING".equals(status)
              ? "Approval is pending with role " + str(a, "role", "MANAGER") + "."
              : "The change was "
                  + status.toLowerCase(Locale.ROOT)
                  + " by "
                  + str(a, "decidedBy", "a person")
                  + ".");
    }
    if (x.get("change") instanceof Map<?, ?> c) {
      lines.add(
          String.format(
              Locale.ROOT,
              "Order %s was changed by %s for %s (%s%s).",
              str(c, "orderId", "?"),
              str(c, "changedBy", "?"),
              signedMoney(c.get("incrementalCost")),
              str(c, "externalOrderId", "supplier reference unknown"),
              c.get("recordLocator") == null ? "" : ", locator " + c.get("recordLocator")));
    }
    if (x.get("resolution") instanceof Map<?, ?> res) {
      lines.add(
          String.format(
              Locale.ROOT,
              "The disruption was resolved %s in %s ms.",
              "ALLOW".equals(res.get("autonomyOutcome")) ? "autonomously" : "after human approval",
              str(res, "durationMs", "?")));
    } else if (x.get("failure") instanceof Map<?, ?> f) {
      lines.add(
          String.format(
              Locale.ROOT,
              "The recovery ended %s at %s: %s.",
              str(f, "status", "FAILED"),
              str(f, "stage", "?"),
              str(f, "reasonCode", "?")));
    }
    return lines;
  }

  private static Object str(Map<?, ?> m, String key, String fallback) {
    Object v = m.get(key);
    return v == null ? fallback : v;
  }

  private static String signedMoney(@Nullable Object m) {
    if (m instanceof Map<?, ?> map
        && map.get("currency") != null
        && map.get("amountMinor") != null) {
      long minor = ((Number) map.get("amountMinor")).longValue();
      String sign = minor < 0 ? "-" : "+";
      return String.format(
          Locale.ROOT,
          "%s%s %d.%02d",
          sign,
          map.get("currency"),
          Math.abs(minor) / 100,
          Math.abs(minor) % 100);
    }
    return "n/a";
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
