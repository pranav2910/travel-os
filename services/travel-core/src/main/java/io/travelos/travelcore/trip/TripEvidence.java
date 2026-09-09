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
}
