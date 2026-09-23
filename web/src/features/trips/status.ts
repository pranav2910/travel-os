import type { PolicyDecisionResponse, TripResponse, TripStatus } from '@/api/types';

/** What the platform does with a submitted trip, in order; the current status sets the step states. */
export const STEPS: { key: string; label: string; statuses: TripStatus[] }[] = [
  { key: 'submitted', label: 'Submitted', statuses: ['SUBMITTED'] },
  { key: 'planning', label: 'Search, policy, optimizer', statuses: ['PLANNING'] },
  { key: 'approval', label: 'Approval', statuses: ['AWAITING_APPROVAL', 'APPROVED'] },
  { key: 'booking', label: 'Booking', statuses: ['BOOKING'] },
  { key: 'booked', label: 'Booked', statuses: ['BOOKED', 'COMPLETED'] },
];

export function stepState(
  trip: TripResponse,
  step: (typeof STEPS)[number],
): 'done' | 'current' | 'todo' | 'failed' | 'skipped' {
  const order: TripStatus[] = [
    'DRAFT',
    'SUBMITTED',
    'PLANNING',
    'AWAITING_APPROVAL',
    'APPROVED',
    'BOOKING',
    'BOOKED',
    'COMPLETED',
  ];
  if (trip.status === 'FAILED') {
    const failedAt = failedStep(trip.failureStage);
    const idx = STEPS.findIndex((s) => s.key === step.key);
    if (idx < failedAt) return 'done';
    if (idx === failedAt) return 'failed';
    return 'todo';
  }
  if (trip.status === 'CANCELLED' || trip.status === 'CANCELLING') {
    // a trip that was booked before it was cancelled did go through every step; one withdrawn
    // while planning never needed the rest
    if (trip.evidence?.orderId)
      return step.key === 'approval' && !trip.approval ? 'skipped' : 'done';
    return 'skipped';
  }
  if (step.statuses.includes(trip.status)) return 'current';
  const current = order.indexOf(trip.status);
  const first = order.indexOf(step.statuses[0]!);
  if (step.key === 'approval' && !trip.approval && current > first) return 'skipped';
  return current > first ? 'done' : 'todo';
}

function failedStep(stage: string | undefined): number {
  switch (stage) {
    case 'INTENT':
    case 'CONTEXT':
    case 'SEARCH':
    case 'POLICY':
    case 'OPTIMIZATION':
      return 1;
    case 'APPROVAL':
      return 2;
    case 'REVALIDATION':
    case 'BOOKING':
    case 'COMPENSATION':
      return 3;
    default:
      return 1;
  }
}

export function explainFailure(trip: TripResponse): string {
  const code = trip.failureCode ?? 'FAILED';
  switch (code) {
    case 'ALL_CANDIDATES_DENIED':
      return 'Every option was denied by policy. Nothing was booked.';
    case 'NO_FEASIBLE_CANDIDATE':
    case 'NO_FEASIBLE_ITINERARY':
      return 'No permitted option fits the requested windows. Nothing was booked.';
    case 'HOTEL_DETAILS_INSUFFICIENT':
      return 'A hotel was required but the request did not say when: add the stay dates.';
    case 'NO_OFFERS':
      return 'The suppliers returned nothing for this request.';
    case 'REJECTED':
      return 'The approver rejected this trip.';
    default:
      return `The platform stopped at ${trip.failureStage ?? 'an unknown stage'} with ${code}.`;
  }
}

export function isTerminal(status: TripStatus): boolean {
  return (
    status === 'BOOKED' || status === 'COMPLETED' || status === 'CANCELLED' || status === 'FAILED'
  );
}

/** Whether the traveler (or a travel admin) may ask to cancel: not once it is over or already asked. */
export function canRequestCancellation(status: TripStatus): boolean {
  // BOOKED counts as terminal for polling, but a booked trip can still be released
  return status === 'BOOKED' || (!isTerminal(status) && status !== 'CANCELLING');
}

/** Why a cancellation is still in progress, in the platform's own words. */
export function explainCancelling(trip: TripResponse): string {
  switch (trip.failureCode) {
    case 'CANCELLATION_INCOMPLETE':
      return 'A supplier refused to release part of the reservation. It stays confirmed at that supplier until a person resolves the exposure; the trip is not cancelled yet.';
    case 'CANCELLATION_UNRESOLVED':
      return 'The reservation was not fully released within 30 days. A travel admin must finish the cancellation by hand; the trip is not cancelled yet.';
    case undefined:
    case null:
    case '':
      return 'The reservation is being released at the suppliers. The trip is cancelled only once every component is released.';
    default:
      return `The reservation could not be released (${trip.failureCode}). A person must finish the cancellation; the trip is not cancelled yet.`;
  }
}

/** The distinct reasons policy gave for denying options, most frequent first: the backend's words, not a guess. */
export function denyReasons(
  decisions: PolicyDecisionResponse[],
): { code: string; message: string; count: number }[] {
  const seen = new Map<string, { code: string; message: string; count: number }>();
  for (const d of decisions) {
    if (d.outcome !== 'DENY') continue;
    const reasons = d.decision['reasons'];
    if (!Array.isArray(reasons)) continue;
    for (const r of reasons as { code?: unknown; message?: unknown }[]) {
      const code = typeof r.code === 'string' ? r.code : 'DENIED';
      const message = typeof r.message === 'string' ? r.message : '';
      const key = `${code}|${message}`;
      const cur = seen.get(key) ?? { code, message, count: 0 };
      cur.count += 1;
      seen.set(key, cur);
    }
  }
  return [...seen.values()].sort((a, b) => b.count - a.count);
}
