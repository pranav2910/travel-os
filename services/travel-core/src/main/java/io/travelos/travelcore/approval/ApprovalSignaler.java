package io.travelos.travelcore.approval;

import io.travelos.workflows.TripPlanning;

/**
 * Tells the trip workflow that a person decided. The decision is already durable in Postgres when
 * this is called; the workflow also re-reads it on resume, so a lost signal delays, never loses,
 * the outcome.
 */
public interface ApprovalSignaler {

  void approvalDecided(String tripId, TripPlanning.ApprovalDecision decision);
}
