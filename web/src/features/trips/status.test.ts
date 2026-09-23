import { describe, expect, it } from 'vitest';
import type { TripResponse } from '@/api/types';
import {
  STEPS,
  canRequestCancellation,
  denyReasons,
  explainCancelling,
  explainFailure,
  isTerminal,
  stepState,
} from './status';

const base: TripResponse = {
  tripId: 'trip_1',
  tenantId: 'acme',
  travelerId: 'emp_1001',
  status: 'PLANNING',
  source: 'WEB',
  evidence: {},
  createdBy: 'human/alice',
  version: 1,
  createdAt: '2026-09-16T00:00:00Z',
  updatedAt: '2026-09-16T00:00:00Z',
  traveler: { travelerId: 'emp_1001', givenName: 'Alice', familyName: 'N', email: 'a@x' },
};

describe('trip progress', () => {
  it('marks steps done, current and pending from the status alone', () => {
    const states = STEPS.map((s) => stepState({ ...base, status: 'BOOKING' }, s));
    expect(states).toEqual(['done', 'done', 'skipped', 'current', 'todo']);
  });
  it('never shows a booked trip as completed because time passed', () => {
    const booked = STEPS.map((s) =>
      stepState({ ...base, status: 'BOOKED', updatedAt: '2020-01-01T00:00:00Z' }, s),
    );
    expect(booked[4]).toBe('current');
    expect(stepState({ ...base, status: 'COMPLETED' }, STEPS[4]!)).toBe('current');
  });
  it('a cancelling trip is neither over nor cancellable again, and says what is happening', () => {
    expect(isTerminal('CANCELLING')).toBe(false);
    expect(canRequestCancellation('BOOKED')).toBe(true);
    expect(canRequestCancellation('CANCELLING')).toBe(false);
    expect(canRequestCancellation('CANCELLED')).toBe(false);
    expect(STEPS.map((s) => stepState({ ...base, status: 'CANCELLING' }, s))).toEqual([
      'skipped',
      'skipped',
      'skipped',
      'skipped',
      'skipped',
    ]);
    expect(explainCancelling({ ...base, status: 'CANCELLING' })).toMatch(
      /released at the suppliers/,
    );
    expect(
      explainCancelling({
        ...base,
        status: 'CANCELLING',
        failureStage: 'CANCELLATION',
        failureCode: 'CANCELLATION_INCOMPLETE',
      }),
    ).toMatch(/refused.*not cancelled yet/);
  });
  it('explains a policy denial without inventing detail', () => {
    expect(
      explainFailure({
        ...base,
        status: 'FAILED',
        failureStage: 'POLICY',
        failureCode: 'ALL_CANDIDATES_DENIED',
      }),
    ).toMatch(/denied by policy/);
    expect(
      stepState(
        { ...base, status: 'FAILED', failureStage: 'POLICY', failureCode: 'ALL_CANDIDATES_DENIED' },
        STEPS[1]!,
      ),
    ).toBe('failed');
  });

  it('lists the distinct reasons policy gave for denying, most frequent first, in its own words', () => {
    const d = (outcome: string, reasons: unknown) =>
      ({ decisionId: Math.random().toString(), outcome, decision: { reasons } }) as never;
    const out = denyReasons([
      d('DENY', [{ code: 'NO_POLICY', message: 'tenant globex has no default travel policy' }]),
      d('DENY', [{ code: 'NO_POLICY', message: 'tenant globex has no default travel policy' }]),
      d('DENY', [{ code: 'OVER_BUDGET', message: 'USD 5000 over the ceiling' }]),
      d('ALLOW', [{ code: 'IGNORED' }]),
      d('DENY', 'not a list'),
    ]);
    expect(out).toEqual([
      { code: 'NO_POLICY', message: 'tenant globex has no default travel policy', count: 2 },
      { code: 'OVER_BUDGET', message: 'USD 5000 over the ceiling', count: 1 },
    ]);
  });
});
