import { apiFetch, query } from './http';
import type {
  CandidateView,
  ConnectorView,
  ConversionView,
  DemandStatus,
  EvidenceView,
  RunView,
  TransitionView,
} from './types';

export const demandKeys = {
  list: (status?: string, travelerId?: string) =>
    ['demand', 'list', status ?? 'ALL', travelerId ?? 'ME'] as const,
  one: (id: string) => ['demand', 'one', id] as const,
  history: (id: string) => ['demand', 'history', id] as const,
  evidence: (id: string) => ['demand', 'evidence', id] as const,
};

export const demand = {
  list: (status?: DemandStatus | string, travelerId?: string) =>
    apiFetch<CandidateView[]>(`/api/v1/demand${query({ status, travelerId })}`),
  get: (id: string) => apiFetch<CandidateView>(`/api/v1/demand/${encodeURIComponent(id)}`),
  history: (id: string) =>
    apiFetch<TransitionView[]>(`/api/v1/demand/${encodeURIComponent(id)}/history`),
  evidence: (id: string) =>
    apiFetch<EvidenceView[]>(`/api/v1/demand/${encodeURIComponent(id)}/evidence`),
  details: (
    id: string,
    body: { destination?: string; startDate?: string; endDate?: string; clearReviewFlag?: string },
    idempotencyKey: string,
  ) =>
    apiFetch<CandidateView>(`/api/v1/demand/${encodeURIComponent(id)}/details`, {
      method: 'POST',
      body,
      idempotencyKey,
    }),
  dismiss: (id: string, reason: string | undefined, idempotencyKey: string) =>
    apiFetch<CandidateView>(`/api/v1/demand/${encodeURIComponent(id)}/dismissal`, {
      method: 'POST',
      body: { reason },
      idempotencyKey,
    }),
  convert: (id: string, idempotencyKey: string) =>
    apiFetch<ConversionView>(`/api/v1/demand/${encodeURIComponent(id)}/conversion`, {
      method: 'POST',
      idempotencyKey,
    }),
};

export const connectorKeys = {
  list: () => ['connectors', 'list'] as const,
  one: (id: string) => ['connectors', 'one', id] as const,
  runs: (id: string) => ['connectors', 'runs', id] as const,
};

export const connectors = {
  list: () => apiFetch<ConnectorView[]>('/api/v1/connectors'),
  get: (id: string) => apiFetch<ConnectorView>(`/api/v1/connectors/${encodeURIComponent(id)}`),
  runs: (id: string) => apiFetch<RunView[]>(`/api/v1/connectors/${encodeURIComponent(id)}/runs`),
  create: (
    body: { kind: string; provider: string; config?: Record<string, unknown> },
    idempotencyKey: string,
  ) => apiFetch<ConnectorView>('/api/v1/connectors', { method: 'POST', body, idempotencyKey }),
  setStatus: (id: string, status: 'ENABLED' | 'DISABLED', idempotencyKey: string) =>
    apiFetch<ConnectorView>(`/api/v1/connectors/${encodeURIComponent(id)}/status`, {
      method: 'POST',
      body: { status },
      idempotencyKey,
    }),
  setConfig: (id: string, config: Record<string, unknown>, idempotencyKey: string) =>
    apiFetch<ConnectorView>(`/api/v1/connectors/${encodeURIComponent(id)}/config`, {
      method: 'POST',
      body: { config },
      idempotencyKey,
    }),
  sync: (id: string, idempotencyKey: string) =>
    apiFetch<RunView>(`/api/v1/connectors/${encodeURIComponent(id)}/sync`, {
      method: 'POST',
      idempotencyKey,
    }),
};
