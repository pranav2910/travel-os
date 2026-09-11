package io.travelos.travelcore.trip;

import org.jspecify.annotations.Nullable;

/** Ids of the decisions that shaped this trip. The decision ledger hangs off these. */
public record TripEvidence(
    @Nullable String selectedBundleId,
    @Nullable String optimizationRunId,
    @Nullable String policyDecisionId,
    @Nullable String approvalId,
    @Nullable String orderId) {

  public static final TripEvidence NONE = new TripEvidence(null, null, null, null, null);

  /** Merge: a non-blank incoming value replaces, blank keeps what is there. */
  public TripEvidence merge(
      @Nullable String bundle,
      @Nullable String optimizationRun,
      @Nullable String policyDecision,
      @Nullable String approval,
      @Nullable String order) {
    return new TripEvidence(
        pick(bundle, selectedBundleId),
        pick(optimizationRun, optimizationRunId),
        pick(policyDecision, policyDecisionId),
        pick(approval, approvalId),
        pick(order, orderId));
  }

  private static @Nullable String pick(@Nullable String incoming, @Nullable String current) {
    return incoming == null || incoming.isBlank() ? current : incoming;
  }
}
