import { describe, expect, it } from 'vitest';
import type { TripResponse } from '@/api/types';
import { STEPS, explainFailure, stepState } from './status';

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
});
