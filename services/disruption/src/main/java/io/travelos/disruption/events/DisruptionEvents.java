package io.travelos.disruption.events;

import io.travelos.common.money.Money;
import io.travelos.disruption.model.Disruption;
import io.travelos.disruption.model.RecoveryApproval;
import io.travelos.events.EventEnvelope;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** travel.disruption.* and the recovery's travel.approval.* events, per contracts/events. */
public final class DisruptionEvents {

  public static final String PRODUCER = "disruption";

  private DisruptionEvents() {}

  public static EventEnvelope impactConfirmed(
      Disruption d, Map<String, Object> affected, @Nullable String causationId, Clock clock) {
    Map<String, Object> data = base(d);
    data.put("travelerId", d.travelerId());
    if (d.segmentId() != null) {
      data.put("segmentId", d.segmentId());
    }
    data.put("type", d.type());
    data.put("supplier", d.supplier());
    data.put("severity", d.severity());
    data.put("affected", affected);
    return envelope("travel.disruption.impact-confirmed", d, causationId, data, clock);
  }

  public static EventEnvelope recoveryStarted(
      Disruption d, String workflowId, String agent, Clock clock) {
    Map<String, Object> data = base(d);
    data.put("workflowId", workflowId);
    data.put("agent", agent);
    return envelope("travel.disruption.recovery-started", d, d.disruptionId(), data, clock);
  }

  public record DecisionSummary(
      int candidatesSearched,
      int candidatesPermitted,
      int candidatesFeasible,
      List<Map<String, Object>> rejected,
      String selectedBundleId,
      @Nullable Double selectedScore,
      @Nullable Money originalTotal,
      @Nullable Money replacementTotal,
      Money incrementalCost,
      String policyDecisionId,
      String policyId,
      int policyVersion,
      String optimizationRunId,
      String autonomyOutcome,
      List<String> reasonCodes,
      @Nullable List<Map<String, Object>> componentChanges) {
    public DecisionSummary(
        int candidatesSearched,
        int candidatesPermitted,
        int candidatesFeasible,
        List<Map<String, Object>> rejected,
        String selectedBundleId,
        @Nullable Double selectedScore,
        @Nullable Money originalTotal,
        @Nullable Money replacementTotal,
        Money incrementalCost,
        String policyDecisionId,
        String policyId,
        int policyVersion,
        String optimizationRunId,
        String autonomyOutcome,
        List<String> reasonCodes) {
      this(
          candidatesSearched,
          candidatesPermitted,
          candidatesFeasible,
          rejected,
          selectedBundleId,
          selectedScore,
          originalTotal,
          replacementTotal,
          incrementalCost,
          policyDecisionId,
          policyId,
          policyVersion,
          optimizationRunId,
          autonomyOutcome,
          reasonCodes,
          null);
    }
  }

  public static EventEnvelope decisionReady(Disruption d, DecisionSummary s, Clock clock) {
    Map<String, Object> data = base(d);
    data.put("candidatesSearched", s.candidatesSearched());
    data.put("candidatesPermitted", s.candidatesPermitted());
    data.put("candidatesFeasible", s.candidatesFeasible());
    data.put("rejected", s.rejected());
    data.put("selectedBundleId", s.selectedBundleId());
    if (s.selectedScore() != null) {
      data.put("selectedScore", s.selectedScore());
    }
    if (s.originalTotal() != null) {
      data.put("originalTotal", money(s.originalTotal()));
    }
    if (s.replacementTotal() != null) {
      data.put("replacementTotal", money(s.replacementTotal()));
    }
    data.put("incrementalCost", money(s.incrementalCost()));
    data.put("policyDecisionId", s.policyDecisionId());
    if (!s.policyId().isBlank()) {
      data.put("policyId", s.policyId());
      data.put("policyVersion", s.policyVersion());
    }
    data.put("optimizationRunId", s.optimizationRunId());
    data.put("autonomyOutcome", s.autonomyOutcome());
    data.put("reasonCodes", s.reasonCodes());
    if (s.componentChanges() != null && !s.componentChanges().isEmpty()) {
      data.put("componentChanges", s.componentChanges());
      data.put(
          "affectedComponentIds",
          s.componentChanges().stream().map(c -> c.get("componentId")).toList());
    }
    return envelope("travel.disruption.decision-ready", d, d.disruptionId(), data, clock);
  }

  public static EventEnvelope approvalRequired(
      Disruption d, RecoveryApproval a, List<String> reasonCodes, Clock clock) {
    Map<String, Object> data = base(d);
    data.put("approvalId", a.approvalId());
    data.put("role", a.requiredRole());
    data.put(
        "incrementalCost",
        money(a.incrementalCost() == null ? Money.zero("USD") : a.incrementalCost()));
    data.put("policyDecisionId", a.policyDecisionId() == null ? "" : a.policyDecisionId());
    data.put("reasonCodes", reasonCodes);
    return envelope("travel.disruption.approval-required", d, d.disruptionId(), data, clock);
  }

  public static EventEnvelope approvalDecided(
      Disruption d, RecoveryApproval a, String decidedBy, @Nullable String comment, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("approvalId", a.approvalId());
    data.put("tripId", d.tripId());
    data.put("decidedBy", decidedBy);
    if (comment != null && !comment.isBlank()) {
      data.put("comment", comment);
    }
    data.put("disruptionId", d.disruptionId());
    String type =
        a.status() == RecoveryApproval.Status.APPROVED
            ? "travel.approval.approved"
            : "travel.approval.rejected";
    return envelope(type, d, d.disruptionId(), data, clock);
  }

  public record Resolution(
      String replacementBundleId,
      Money incrementalCost,
      String autonomyOutcome,
      @Nullable String approvalId,
      @Nullable String approvedBy,
      String resolvedBy,
      @Nullable String externalOrderId,
      @Nullable String recordLocator,
      long durationMs) {}

  public static EventEnvelope resolved(Disruption d, Resolution r, Clock clock) {
    Map<String, Object> data = base(d);
    data.put("replacementBundleId", r.replacementBundleId());
    data.put("incrementalCost", money(r.incrementalCost()));
    data.put("autonomyOutcome", r.autonomyOutcome());
    if (r.approvalId() != null) {
      data.put("approvalId", r.approvalId());
    }
    if (r.approvedBy() != null) {
      data.put("approvedBy", r.approvedBy());
    }
    data.put("resolvedBy", r.resolvedBy());
    if (r.externalOrderId() != null && !r.externalOrderId().isBlank()) {
      data.put("externalOrderId", r.externalOrderId());
    }
    if (r.recordLocator() != null && !r.recordLocator().isBlank()) {
      data.put("recordLocator", r.recordLocator());
    }
    data.put("durationMs", r.durationMs());
    return envelope("travel.disruption.resolved", d, d.disruptionId(), data, clock);
  }

  public static EventEnvelope recoveryFailed(
      Disruption d,
      String status,
      String stage,
      String code,
      @Nullable String message,
      Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("disruptionId", d.disruptionId());
    if (d.tripId() != null) {
      data.put("tripId", d.tripId());
    }
    if (d.orderId() != null) {
      data.put("orderId", d.orderId());
    }
    data.put("status", status);
    data.put("stage", stage);
    data.put("reasonCode", code);
    if (message != null && !message.isBlank()) {
      data.put("message", message.length() > 2000 ? message.substring(0, 2000) : message);
    }
    return envelope("travel.disruption.recovery-failed", d, d.disruptionId(), data, clock);
  }

  static Map<String, Object> base(Disruption d) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("disruptionId", d.disruptionId());
    data.put("tripId", d.tripId());
    data.put("orderId", d.orderId());
    return data;
  }

  public static Map<String, Object> money(Money money) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("currency", money.currency());
    m.put("amountMinor", money.amountMinor());
    return m;
  }

  public static List<Map<String, Object>> rejected(List<Map<String, Object>> in) {
    return new ArrayList<>(in);
  }

  /** Correlation is the trip when known, the disruption itself before impact is confirmed. */
  private static EventEnvelope envelope(
      String type,
      Disruption d,
      @Nullable String causationId,
      Map<String, Object> data,
      Clock clock) {
    return EventEnvelope.create(
        type,
        1,
        d.tenant(),
        d.tripId() == null ? d.disruptionId() : d.tripId(),
        causationId,
        PRODUCER,
        data,
        clock);
  }
}
