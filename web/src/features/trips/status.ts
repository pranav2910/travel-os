import type { TripResponse, TripStatus } from '@/api/types';

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
  if (trip.status === 'CANCELLED') return 'skipped';
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
      return 'Every option was denied by policy (for example the trip budget). Nothing was booked.';
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
