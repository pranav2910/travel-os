package io.travelos.travelcore.approval;

import io.travelos.workflows.TripPlanning;

/**
 * Tells the trip workflow that a person decided. The decision is already durable in Postgres when
 * this is called; the workflow also re-reads it on resume, so a lost signal delays, never loses,
 * the outcome.
 */
public interface ApprovalSignaler {

  void approvalDecided(String tripId, TripPlanning.ApprovalDecision decision);

  /**
   * The requester cancelled a trip that holds no reservation. CANCELLED is already durable; the
   * workflow stops waiting for a person and books nothing (it also re-reads the trip on its own, so
   * a lost signal delays nothing that matters).
   */
  default void cancelled(String tripId) {}

  /**
   * Phase 3: a person authorized the purchase of the quoted plan (durable before this is called).
   */
  default void purchaseAuthorized(String tripId, TripPlanning.PurchaseAuthorized authorized) {}

  /** Phase 3: a person chose another quoted alternative; the workflow re-quotes it. */
  default void selectionChanged(String tripId, TripPlanning.SelectionChanged selection) {}

  /** Phase 3: a person asked for a fresh price on the quoted plan. */
  default void refreshQuote(String tripId, String reason) {}
}
