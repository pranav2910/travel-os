import { apiFetch, query } from './http';
import type { PolicyDecisionResponse } from './types';

export const policyKeys = {
  byTrip: (tripId: string) => ['policy', 'trip', tripId] as const,
  one: (id: string) => ['policy', 'one', id] as const,
};

export const policy = {
  byTrip: (tripId: string) =>
    apiFetch<PolicyDecisionResponse[]>(`/api/v1/policy-decisions${query({ tripId })}`),
  get: (decisionId: string) =>
    apiFetch<PolicyDecisionResponse>(`/api/v1/policy-decisions/${encodeURIComponent(decisionId)}`),
};
