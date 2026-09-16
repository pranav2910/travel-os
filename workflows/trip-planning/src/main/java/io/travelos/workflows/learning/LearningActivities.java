package io.travelos.workflows.learning;

import io.temporal.activity.ActivityInterface;
import io.travelos.contracts.learning.v1.ProfileSummary;
import io.travelos.contracts.optimization.v1.LearningInputs;

/**
 * The Learning service's side effects, one gRPC call each. {@code resolve} is the only read a
 * planning workflow makes of learned state, and it is made through this activity so the result is
 * pinned to the attempt in the workflow history: a replay never asks again, a retry of the
 * optimizer sees the same inputs.
 */
@ActivityInterface
public interface LearningActivities {
  LearningInputs resolve(String tenantId, String tripId, String travelerId, String purpose);

  ProfileSummary beginBuild(String tenantId, String profileId);

  ProfileSummary computeProfile(String tenantId, String profileId);

  ProfileSummary evaluateProfile(String tenantId, String profileId);

  ProfileSummary failBuild(String tenantId, String profileId, String code, String message);
}
