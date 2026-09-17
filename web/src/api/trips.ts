import { apiFetch, query } from './http';
import type {
  AgentDecision,
  CreateTripRequest,
  StatusChange,
  TripResponse,
  TripStatus,
} from './types';

export const tripKeys = {
  all: ['trips'] as const,
  mine: () => ['trips', 'mine'] as const,
  tenant: (status?: TripStatus) => ['trips', 'tenant', status ?? 'ALL'] as const,
  one: (id: string) => ['trips', 'one', id] as const,
  history: (id: string) => ['trips', 'history', id] as const,
  decisions: (id: string) => ['trips', 'decisions', id] as const,
};

export const trips = {
  listMine: (limit = 100) => apiFetch<TripResponse[]>(`/api/v1/trips${query({ limit })}`),
  listTenant: (status?: TripStatus, limit = 100) =>
    apiFetch<TripResponse[]>(`/api/v1/trips${query({ scope: 'tenant', status, limit })}`),
  get: (tripId: string) => apiFetch<TripResponse>(`/api/v1/trips/${encodeURIComponent(tripId)}`),
  history: (tripId: string) =>
    apiFetch<StatusChange[]>(`/api/v1/trips/${encodeURIComponent(tripId)}/history`),
  decisions: (tripId: string) =>
    apiFetch<AgentDecision[]>(`/api/v1/trips/${encodeURIComponent(tripId)}/decisions`),
  create: (body: CreateTripRequest, idempotencyKey: string) =>
    apiFetch<TripResponse>('/api/v1/trips', { method: 'POST', body, idempotencyKey }),
  approve: (
    tripId: string,
    decision: 'APPROVE' | 'REJECT',
    comment: string | undefined,
    idempotencyKey: string,
  ) =>
    apiFetch<TripResponse['approval']>(`/api/v1/trips/${encodeURIComponent(tripId)}/approval`, {
      method: 'POST',
      body: { decision, comment },
      idempotencyKey,
    }),
  cancel: (tripId: string, reason: string, idempotencyKey: string) =>
    apiFetch<TripResponse>(`/api/v1/trips/${encodeURIComponent(tripId)}/cancellation`, {
      method: 'POST',
      body: { reason },
      idempotencyKey,
    }),
  complete: (tripId: string, idempotencyKey: string) =>
    apiFetch<TripResponse>(`/api/v1/trips/${encodeURIComponent(tripId)}/completion`, {
      method: 'POST',
      idempotencyKey,
    }),
};
