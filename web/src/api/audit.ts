import { apiFetch, query } from './http';
import type { AuditRecord, DecisionLedger } from './types';

export const auditKeys = {
  ledger: (tripId: string) => ['audit', 'ledger', tripId] as const,
  trail: (tripId: string) => ['audit', 'trail', tripId] as const,
  demand: (candidateId: string) => ['audit', 'demand', candidateId] as const,
};

export const audit = {
  ledger: (tripId: string) =>
    apiFetch<DecisionLedger>(`/api/v1/audit/trips/${encodeURIComponent(tripId)}/decisions`),
  trail: (tripId: string) =>
    apiFetch<{ tripId: string; travelerId: string; events: AuditRecord[] }>(
      `/api/v1/audit/trips/${encodeURIComponent(tripId)}`,
    ),
  demand: (candidateId: string) =>
    apiFetch<{ candidateId: string; travelerId: string; events: AuditRecord[] }>(
      `/api/v1/audit/demand/${encodeURIComponent(candidateId)}`,
    ),
  events: (type: string, limit = 50) =>
    apiFetch<AuditRecord[]>(`/api/v1/audit/events${query({ type, limit })}`),
};
