import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { trips, tripKeys } from '@/api/trips';
import {
  TERMINAL_TRIP,
  WAITING_TRIP,
  type CreateTripRequest,
  type TripResponse,
} from '@/api/types';
import { finish, keyFor } from '@/lib/idempotency';
import { pollingInterval, wake, wokenAt } from '@/lib/polling';
import { UncertainError } from '@/lib/problem';

export function useMyTrips() {
  return useQuery({ queryKey: tripKeys.mine(), queryFn: () => trips.listMine() });
}

/** One trip, polled with backoff while the workflow is still moving it. */
export function useTrip(tripId: string | undefined, startedAt: number | null = null) {
  return useQuery({
    queryKey: tripKeys.one(tripId ?? ''),
    queryFn: () => trips.get(tripId!),
    enabled: !!tripId,
    refetchInterval: (q) =>
      tripRefetchInterval(q.state.data as TripResponse | undefined, startedAt),
  });
}

/**
 * The trip's refetch cadence: quick while the workflow moves it, none once it ended, none while
 * it rests on a person, unless that person just acted here (see `wake`).
 */
export function tripRefetchInterval(
  t: TripResponse | undefined,
  startedAt: number | null,
): number | false {
  if (!t) return false;
  const woke = wokenAt(t.tripId);
  const resting = WAITING_TRIP.has(t.status) && woke === null;
  return pollingInterval(
    TERMINAL_TRIP.has(t.status) || resting,
    woke ?? startedAt ?? new Date(t.createdAt).getTime(),
  );
}

/**
 * Every other view of a trip (components, bookings, disruptions, the decision record, the
 * timeline, feedback) follows the trip itself: whenever the trip changed they are refetched, and
 * again shortly after, because the ledgers are written by consumers that run a moment later.
 * The first sight of a trip only schedules the delayed refreshes (the views are loading anyway).
 */
export function useFollowTrip(trip: TripResponse | undefined) {
  const qc = useQueryClient();
  const seen = useRef<string | null>(null);
  const tripId = trip?.tripId;
  const mark = trip ? `${trip.status}:${trip.version}:${trip.updatedAt}` : '';
  useEffect(() => {
    if (!tripId || !mark) return;
    const first = !seen.current?.startsWith(`${tripId}|`);
    seen.current = `${tripId}|${mark}`;
    const refresh = () =>
      void qc.invalidateQueries({
        predicate: (q) =>
          q.queryKey.includes(tripId) && !(q.queryKey[0] === 'trips' && q.queryKey[1] === 'one'),
      });
    const delays = first ? [4000, 12000] : [0, 4000, 12000];
    const timers = delays.map((ms) => window.setTimeout(refresh, ms));
    return () => timers.forEach((id) => window.clearTimeout(id));
  }, [qc, tripId, mark]);
}

/**
 * Create a trip exactly once per intended request: the idempotency key is fixed to the payload
 * until the platform answered, so a double click, a retry after a timeout or a reload send the
 * same key and get the same trip back. An uncertain answer is surfaced as such: the caller
 * reconciles (the trips list) before deciding anything.
 */
export function useCreateTrip() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateTripRequest) => {
      const key = keyFor('create-trip', body);
      try {
        const trip = await trips.create(body, key);
        finish('create-trip', body);
        return trip;
      } catch (e) {
        if (e instanceof UncertainError) throw e; // keep the key: the next attempt replays it
        finish('create-trip', body); // a definite refusal: a new attempt is a new operation
        throw e;
      }
    },
    onSuccess: (trip) => {
      qc.setQueryData(tripKeys.one(trip.tripId), trip);
      void qc.invalidateQueries({ queryKey: tripKeys.all });
    },
  });
}

export function useCancelTrip(tripId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (reason: string) =>
      trips.cancel(tripId, reason, keyFor('cancel-trip', { tripId, reason })),
    onSuccess: (trip) => {
      finish('cancel-trip', { tripId });
      wake(tripId);
      qc.setQueryData(tripKeys.one(tripId), trip);
      void qc.invalidateQueries({ queryKey: tripKeys.all });
    },
  });
}

export function useCompleteTrip(tripId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => trips.complete(tripId, keyFor('complete-trip', { tripId })),
    onSuccess: (trip) => {
      finish('complete-trip', { tripId });
      wake(tripId);
      qc.setQueryData(tripKeys.one(tripId), trip);
      void qc.invalidateQueries({ queryKey: tripKeys.all });
    },
  });
}

export function useDecideTrip(tripId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      decision,
      comment,
      version,
    }: {
      decision: 'APPROVE' | 'REJECT';
      comment?: string;
      version: number;
    }) =>
      trips.approve(
        tripId,
        decision,
        comment,
        keyFor('decide-trip', { tripId, decision, version }),
      ),
    onSuccess: () => {
      wake(tripId); // the person acted: the workflow moves again, follow it
      void qc.invalidateQueries({ queryKey: tripKeys.all });
    },
  });
}
