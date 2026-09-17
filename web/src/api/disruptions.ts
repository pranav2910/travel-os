import { apiFetch, query } from './http';
import type { DisruptionView } from './types';

export const disruptionKeys = {
  byTrip: (tripId: string) => ['disruptions', 'trip', tripId] as const,
  list: (status?: string) => ['disruptions', 'list', status ?? 'ALL'] as const,
  one: (id: string) => ['disruptions', 'one', id] as const,
};

export const disruptions = {
  byTrip: (tripId: string) =>
    apiFetch<DisruptionView[]>(`/api/v1/trips/${encodeURIComponent(tripId)}/disruptions`),
  list: (status?: string, limit = 100) =>
    apiFetch<DisruptionView[]>(`/api/v1/disruptions${query({ status, limit })}`),
  get: (id: string) => apiFetch<DisruptionView>(`/api/v1/disruptions/${encodeURIComponent(id)}`),
  decide: (
    id: string,
    decision: 'APPROVE' | 'REJECT',
    comment: string | undefined,
    idempotencyKey: string,
  ) =>
    apiFetch<DisruptionView['approval']>(`/api/v1/disruptions/${encodeURIComponent(id)}/approval`, {
      method: 'POST',
      body: { decision, comment },
      idempotencyKey,
    }),
};
