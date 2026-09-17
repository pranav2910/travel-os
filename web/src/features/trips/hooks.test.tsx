import { QueryClient, QueryClientProvider, type Query } from '@tanstack/react-query';
import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { TripResponse } from '@/api/types';
import { wake } from '@/lib/polling';
import { tripRefetchInterval, useFollowTrip } from './hooks';

function trip(status: string, version: number): TripResponse {
  return {
    tripId: 'trip_1',
    status,
    version,
    createdAt: '2026-09-16T00:00:00Z',
    updatedAt: `2026-09-16T00:00:0${version}Z`,
  } as unknown as TripResponse;
}

describe('useFollowTrip', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('refetches the other views of a trip when the trip changed, never on a mere re-render', () => {
    const qc = new QueryClient();
    const spy = vi.spyOn(qc, 'invalidateQueries').mockResolvedValue();
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={qc}>{children}</QueryClientProvider>
    );
    const { rerender } = renderHook(({ t }: { t: TripResponse | undefined }) => useFollowTrip(t), {
      wrapper,
      initialProps: { t: trip('SUBMITTED', 1) },
    });
    vi.advanceTimersByTime(0);
    expect(spy).not.toHaveBeenCalled(); // first sight: the views are loading anyway
    rerender({ t: trip('SUBMITTED', 1) });
    vi.advanceTimersByTime(0);
    expect(spy).not.toHaveBeenCalled(); // same state
    rerender({ t: trip('BOOKED', 2) });
    vi.advanceTimersByTime(0);
    expect(spy).toHaveBeenCalledTimes(1); // the change refreshes at once ...
    vi.advanceTimersByTime(12_000);
    expect(spy).toHaveBeenCalledTimes(3); // ... and twice more while the ledgers catch up
    const predicate = spy.mock.calls[0]![0]!.predicate!;
    const q = (queryKey: unknown[]) => ({ queryKey }) as unknown as Query;
    expect(predicate(q(['orders', 'trip', 'trip_1']))).toBe(true);
    expect(predicate(q(['audit', 'ledger', 'trip_1']))).toBe(true);
    expect(predicate(q(['trips', 'one', 'trip_1']))).toBe(false); // the trip itself polls on its own
    expect(predicate(q(['orders', 'trip', 'trip_2']))).toBe(false);
  });
});

describe('tripRefetchInterval', () => {
  it('rests while a person is needed and follows again once someone acted here', () => {
    const now = Date.now();
    expect(tripRefetchInterval(undefined, null)).toBe(false);
    expect(tripRefetchInterval(trip('SUBMITTED', 1), now)).toBe(1500);
    expect(tripRefetchInterval(trip('AWAITING_APPROVAL', 2), now)).toBe(false);
    wake('trip_1');
    expect(tripRefetchInterval(trip('AWAITING_APPROVAL', 2), now)).toBe(1500);
    expect(tripRefetchInterval(trip('BOOKED', 3), now)).toBe(false); // ended: never polled again
  });
});
