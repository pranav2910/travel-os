import { apiFetch, query } from './http';
import type {
  ActivationRecord,
  FeedbackView,
  LearningConfig,
  LearningMode,
  LearningSummary,
  OutcomeView,
  ProfileView,
} from './types';

export const learningKeys = {
  config: () => ['learning', 'config'] as const,
  summary: () => ['learning', 'summary'] as const,
  history: () => ['learning', 'history'] as const,
  profiles: () => ['learning', 'profiles'] as const,
  profile: (id: string) => ['learning', 'profile', id] as const,
  preferences: () => ['learning', 'preferences'] as const,
  outcomes: (tripId: string) => ['learning', 'outcomes', tripId] as const,
  feedback: (tripId: string) => ['learning', 'feedback', tripId] as const,
};

export const learning = {
  config: () => apiFetch<LearningConfig>('/api/v1/learning/config'),
  summary: () => apiFetch<LearningSummary>('/api/v1/learning/summary'),
  history: () => apiFetch<ActivationRecord[]>('/api/v1/learning/history'),
  profiles: () => apiFetch<ProfileView[]>('/api/v1/learning/profiles'),
  profile: (id: string) =>
    apiFetch<ProfileView>(`/api/v1/learning/profiles/${encodeURIComponent(id)}`),
  preferences: () =>
    apiFetch<{
      mode: LearningMode;
      activeProfileId: string | null;
      preferences: Record<string, Record<string, unknown>>;
    }>('/api/v1/learning/preferences'),
  outcomes: (tripId: string) =>
    apiFetch<OutcomeView[]>(`/api/v1/learning/outcomes${query({ tripId })}`),
  feedback: (tripId: string) =>
    apiFetch<FeedbackView[]>(`/api/v1/learning/feedback${query({ tripId })}`),
  setMode: (mode: LearningMode, expectedVersion: number, idempotencyKey: string) =>
    apiFetch<LearningConfig>('/api/v1/learning/config', {
      method: 'PUT',
      body: { mode, expectedVersion },
      idempotencyKey,
    }),
  build: (
    body: { cutoff?: string; evidenceClass?: string; window?: string },
    idempotencyKey: string,
  ) => apiFetch<ProfileView>('/api/v1/learning/profiles', { method: 'POST', body, idempotencyKey }),
  activate: (profileId: string, expectedVersion: number, idempotencyKey: string) =>
    apiFetch<LearningConfig>(
      `/api/v1/learning/profiles/${encodeURIComponent(profileId)}/activation`,
      { method: 'POST', body: { expectedVersion }, idempotencyKey },
    ),
  rollback: (expectedVersion: number, toBaseline: boolean, idempotencyKey: string) =>
    apiFetch<LearningConfig>('/api/v1/learning/rollback', {
      method: 'POST',
      body: { expectedVersion, toBaseline },
      idempotencyKey,
    }),
  recordFeedback: (
    body: {
      tripId: string;
      componentId?: string;
      rating: number;
      tags: string[];
      comment?: string;
    },
    idempotencyKey: string,
  ) =>
    apiFetch<FeedbackView>('/api/v1/learning/feedback', { method: 'POST', body, idempotencyKey }),
  recordRefund: (
    body: {
      tripId: string;
      orderId: string;
      itemId?: string;
      amountMinor: number;
      currency: string;
      reference: string;
    },
    idempotencyKey: string,
  ) =>
    apiFetch<OutcomeView>('/api/v1/learning/outcomes/refunds', {
      method: 'POST',
      body,
      idempotencyKey,
    }),
};
