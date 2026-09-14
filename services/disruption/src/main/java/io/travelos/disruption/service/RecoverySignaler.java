package io.travelos.disruption.service;

import io.travelos.workflows.DisruptionRecovery;

/**
 * Tells the running recovery workflow a person decided. The decision is durable in this service
 * before this is called; the workflow also re-reads it, so a lost signal delays, never loses.
 */
public interface RecoverySignaler {
  void approvalDecided(String disruptionId, DisruptionRecovery.ApprovalDecision decision);
}
