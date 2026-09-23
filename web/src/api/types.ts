/**
 * Explicit types of the REST contracts (the services expose Java records, not OpenAPI). Every shape
 * mirrors a controller's response record; docs/frontend/api-matrix.md maps screens to them.
 */
import type { Money } from '@/lib/money';

export type TripStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'PLANNING'
  | 'AWAITING_APPROVAL'
  | 'APPROVED'
  | 'BOOKING'
  | 'BOOKED'
  | 'COMPLETED'
  /** Booked, and the reservation is being released at the suppliers; CANCELLED only once it is. */
  | 'CANCELLING'
  | 'CANCELLED'
  | 'FAILED';
export const TERMINAL_TRIP: ReadonlySet<TripStatus> = new Set([
  'BOOKED',
  'COMPLETED',
  'CANCELLED',
  'FAILED',
]);
export const WAITING_TRIP: ReadonlySet<TripStatus> = new Set(['AWAITING_APPROVAL']);

export interface MoneyView extends Money {
  display?: string;
}

export interface LegRequest {
  componentId?: string;
  origin: string;
  destination: string;
  earliestDeparture: string;
  arrivalDeadline: string;
}
export interface StayRequest {
  componentId?: string;
  city: string;
  checkInDate: string;
  checkOutDate: string;
  required?: boolean;
}
export interface TransferRequest {
  componentId?: string;
  kind: string;
  city: string;
  fromLocation?: string;
  toLocation?: string;
  pickup?: string;
  required?: boolean;
}
export interface ItineraryRequest {
  legs: LegRequest[];
  stays?: StayRequest[];
  transfers?: TransferRequest[];
  currency?: string;
}
export interface IntentRequest {
  origin?: string;
  destination?: string;
  earliestDeparture?: string;
  arrivalDeadline?: string;
  returnAfter?: string;
  latestReturn?: string;
  purpose?: string;
  hotelRequired?: boolean;
  travelers?: number;
  itinerary?: ItineraryRequest;
}
export interface CreateTripRequest {
  travelerId?: string;
  request?: string;
  intent?: IntentRequest;
  source?: 'WEB' | 'API';
  /** Required when travelerId is someone else: the reservation is made in their name. */
  traveler?: { givenName: string; familyName: string; email: string };
}

export interface ComponentView {
  componentId: string;
  type: 'AIR' | 'HOTEL' | 'GROUND' | string;
  status: string;
  offerId?: string;
  provider?: string;
  externalRef?: string;
  total?: MoneyView;
  failureCode?: string;
  summary?: string;
  updatedAt: string;
}
export interface ApprovalResponse {
  approvalId: string;
  tripId: string;
  requiredRole: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | string;
  policyDecisionId?: string;
  requestedAt: string;
  decidedBy?: string;
  decidedAt?: string;
  comment?: string;
}
export interface TripResponse {
  tripId: string;
  tenantId: string;
  travelerId: string;
  status: TripStatus;
  source: string;
  request?: string;
  intent?: IntentRequest;
  evidence: {
    selectedBundleId?: string;
    optimizationRunId?: string;
    policyDecisionId?: string;
    approvalId?: string;
    orderId?: string;
  };
  createdBy: string;
  version: number;
  createdAt: string;
  updatedAt: string;
  traveler: { travelerId: string; givenName: string; familyName: string; email: string };
  total?: MoneyView;
  approval?: ApprovalResponse;
  failureStage?: string;
  failureCode?: string;
  explanation?: string;
  components?: ComponentView[];
  sourceReference?: string;
}
export interface StatusChange {
  from?: TripStatus | null;
  to: TripStatus;
  reason?: string | null;
  actor: string;
  occurredAt: string;
}
export interface AgentDecision {
  decisionId: string;
  tripId: string;
  decisionType: string;
  result: string;
  confidence?: number | null;
  assumptions: string[];
  detail: Record<string, unknown>;
  call?: Record<string, unknown> | null;
  occurredAt: string;
}

// ---- orders
export interface FlightView {
  direction: string;
  carrier: string;
  flightNumber: string;
  origin: string;
  destination: string;
  departure?: string;
  arrival?: string;
  cabin: string;
}
export interface StayView {
  propertyId: string;
  name: string;
  city: string;
  checkInDate: string;
  checkOutDate: string;
  nights: number;
  timeZone: string;
  [k: string]: unknown;
}
export interface TransferView {
  vendorId: string;
  vendorName: string;
  vehicleClass: string;
  pickupLocation: string;
  dropoffLocation: string;
  pickup?: string;
  dropoff?: string;
  timeZone: string;
}
export interface ItemView {
  itemId: string;
  type: string;
  provider: string;
  providerOfferId: string;
  status: string;
  externalRef?: string;
  recordLocator?: string;
  total: MoneyView;
  failureCode?: string;
  flights: FlightView[];
  componentId?: string;
  hotel?: StayView;
  ground?: TransferView;
}
export interface ExposureView {
  exposureId: string;
  itemId: string;
  componentId?: string;
  provider: string;
  externalRef: string;
  amount: MoneyView;
  reason: string;
  detail?: string;
  status: 'OPEN' | 'RESOLVED' | string;
  resolvedBy?: string;
  resolution?: string;
  createdAt: string;
  resolvedAt?: string;
}
export interface ChangeView {
  changeId: string;
  disruptionId?: string;
  status: string;
  idempotencyKey: string;
  previousBundleId: string;
  replacementBundleId: string;
  incrementalCost?: MoneyView;
  policyDecisionId?: string;
  optimizationRunId?: string;
  approvalId?: string;
  externalOrderId?: string;
  recordLocator?: string;
  failureCode?: string;
  requestedBy: string;
  createdAt: string;
  updatedAt: string;
}
export interface OrderResponse {
  orderId: string;
  tripId: string;
  travelerId: string;
  bundleId: string;
  supplier: string;
  externalOrderId?: string;
  status: string;
  total: MoneyView;
  policyDecisionId?: string;
  optimizationRunId?: string;
  approvalId?: string;
  failureCode?: string;
  failureMessage?: string;
  compensated: boolean;
  items: ItemView[];
  changes?: ChangeView[];
  exposures?: ExposureView[];
  [k: string]: unknown;
}
export interface ExposureListItem {
  orderId: string;
  tripId: string;
  travelerId: string;
  exposure: ExposureView;
}

// ---- disruptions (decision / recovery / outcome are proto-JSON documents: int64 money as strings)
export interface DisruptionView {
  disruptionId: string;
  tenantId: string;
  tripId?: string;
  orderId?: string;
  travelerId?: string;
  segmentId?: string;
  type: string;
  supplier: string;
  supplierEventId: string;
  externalOrderId: string;
  recordLocator?: string;
  detectedAt: string;
  status: string;
  severity: string;
  rawReference?: string;
  reason?: string;
  affected: Record<string, unknown>;
  recovery: Record<string, unknown>;
  failureStage?: string;
  failureCode?: string;
  decision?: Record<string, unknown> | null;
  outcome?: Record<string, unknown> | null;
  approval?: {
    approvalId: string;
    requiredRole: string;
    status: string;
    decidedBy?: string;
    decidedAt?: string;
    comment?: string;
  } | null;
  history: { from?: string; to: string; reason?: string; occurredAt: string }[];
  version: number;
  createdAt: string;
  updatedAt: string;
}
export const TERMINAL_DISRUPTION: ReadonlySet<string> = new Set([
  'RESOLVED',
  'NO_ALTERNATIVE',
  'FAILED',
  'MANUAL_INTERVENTION_REQUIRED',
]);

// ---- policy
export interface PolicyDecisionResponse {
  decisionId: string;
  evaluationId: string;
  tripId: string;
  travelerId: string;
  bundleId?: string;
  action?: string;
  policyId: string;
  policyVersion: number;
  outcome: string;
  requiresApproval: boolean;
  evaluatedFor: string;
  evaluatedAt: string;
  decision: Record<string, unknown>;
}

// ---- audit
export interface AuditRecord {
  eventId: string;
  eventType: string;
  eventVersion: number;
  occurredAt: string;
  receivedAt: string;
  tenantId: string;
  correlationId: string;
  causationId?: string;
  producer: string;
  data: Record<string, unknown>;
}
export interface DecisionLedger {
  tripId: string;
  travelerId: string;
  status: string;
  intent?: Record<string, unknown> | null;
  plan?: Record<string, unknown> | null;
  policy?: Record<string, unknown> | null;
  optimization?: Record<string, unknown> | null;
  approval?: Record<string, unknown> | null;
  order?: Record<string, unknown> | null;
  failure?: Record<string, unknown> | null;
  disruptions: Record<string, unknown>[];
  narrative: string[];
  eventCount: number;
  components: Record<string, unknown>[];
  replans: Record<string, unknown>[];
  compensation?: Record<string, unknown> | null;
  origin?: Record<string, unknown> | null;
  learning?: Record<string, unknown> | null;
}

// ---- demand + connectors
export type DemandStatus = 'NEEDS_REVIEW' | 'ACTIONABLE' | 'DISMISSED' | 'WITHDRAWN' | 'CONVERTED';
export interface CandidateView {
  candidateId: string;
  tenantId: string;
  travelerId: string;
  status: DemandStatus;
  origin?: string;
  destination?: string;
  startDate?: string;
  endDate?: string;
  timeZone?: string;
  windowStart?: string;
  windowEnd?: string;
  purpose?: string;
  missing: string[];
  reviewReasons: string[];
  sources: Record<string, unknown>[];
  rulesVersion: string;
  explanation: string;
  tripId?: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}
export interface TransitionView {
  from?: string | null;
  to: string;
  reason: string;
  detail?: string;
  actor: string;
  occurredAt: string;
}
export interface EvidenceView {
  connectorId: string;
  kind: string;
  sourceId: string;
  revision: number;
  status: string;
  item: unknown;
  firstSeenAt: string;
  observedAt: string;
  revisions?: {
    revision: number;
    status: string;
    item: unknown;
    runId?: string;
    observedAt: string;
  }[];
}
export interface ConversionView {
  candidateId: string;
  status: string;
  tripId: string;
}
export interface ConnectorView {
  connectorId: string;
  tenantId: string;
  kind: 'CALENDAR' | 'CRM' | 'HRIS' | 'EXPENSE' | string;
  provider: string;
  status: 'ENABLED' | 'DISABLED' | string;
  simulated: boolean;
  config: Record<string, unknown>;
  checkpoint: string;
  runningRunId?: string;
  nextSyncAt?: string;
  lastRunId?: string;
  lastSyncAt?: string;
  lastSuccessAt?: string;
  lastErrorCode?: string;
  lastErrorMessage?: string;
  version: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}
export interface RunView {
  runId: string;
  connectorId: string;
  trigger: string;
  status: string;
  since: string;
  watermark: string;
  pages: number;
  itemsSeen: number;
  itemsChanged: number;
  candidatesTouched: number;
  failureCode?: string;
  failureMessage?: string;
  requestedBy?: string;
  notificationId?: string;
  startedAt: string;
  finishedAt?: string;
}

// ---- learning
export type LearningMode = 'OFF' | 'SHADOW' | 'ACTIVE';
export interface LearningConfig {
  tenantId: string;
  mode: LearningMode;
  deploymentClass: 'SANDBOX' | 'LIVE';
  activeProfileId?: string | null;
  previousProfileId?: string | null;
  version: number;
  updatedBy: string;
  updatedAt: string;
}
export interface SupplierEstimate {
  successes: number;
  failures: number;
  samples: number;
  estimate: number;
  adjustment: number;
  kinds: Record<string, number>;
  reason: string;
}
export interface ProfileView {
  profileId: string;
  tenantId: string;
  status: 'BUILDING' | 'BUILT' | 'ELIGIBLE' | 'REJECTED' | 'FAILED';
  algorithmVersion: string;
  evidenceClass: 'SANDBOX' | 'LIVE';
  synthetic: boolean;
  inputCutoff: string;
  windowStart: string;
  parameters: Record<string, unknown>;
  datasetFingerprint?: string | null;
  hardOutcomes: number;
  feedbackOutcomes: number;
  supplierKeys: number;
  suppliers?: Record<string, SupplierEstimate> | null;
  travelersWithPreferences: number;
  evaluation?: Record<string, unknown> | null;
  verdict?: string | null;
  failureCode?: string | null;
  failureMessage?: string | null;
  requestedBy: string;
  createdAt: string;
  builtAt?: string | null;
  evaluatedAt?: string | null;
}
export interface ActivationRecord {
  id: number;
  action: string;
  profileId?: string | null;
  previousProfileId?: string | null;
  mode: LearningMode;
  previousMode?: LearningMode | null;
  configVersion: number;
  actor: string;
  occurredAt: string;
}
export interface OutcomeView {
  outcomeId: string;
  logicalKey: string;
  revision: number;
  kind: string;
  quality: string;
  supplierKey?: string | null;
  provider?: string | null;
  evidenceClass: string;
  tripId?: string | null;
  orderId?: string | null;
  itemId?: string | null;
  componentId?: string | null;
  disruptionId?: string | null;
  observedAt: string;
  recordedAt: string;
  source: string;
  sourceRef: string;
  provenance: Record<string, unknown>;
}
export interface FeedbackView {
  feedbackId: string;
  tripId: string;
  travelerId: string;
  componentId: string;
  supplierKey?: string | null;
  revision: number;
  rating: number;
  tags: string[];
  comment?: string | null;
  recordedBy: string;
  recordedAt: string;
}
export interface LearningSummary {
  tenantId: string;
  mode: LearningMode;
  deploymentClass: string;
  activeProfileId?: string | null;
  previousProfileId?: string | null;
  configVersion: number;
  outcomes: { kind: string; evidenceClass: string; count: number }[];
  profiles: Record<string, number>;
  algorithmVersion: string;
  maxAdjustment: number;
  minSamples: number;
  windowDays: number;
}
export const FEEDBACK_TAGS = [
  'ON_TIME',
  'DELAYED',
  'CLEAN',
  'NOISY',
  'FRIENDLY_STAFF',
  'POOR_SERVICE',
  'GOOD_VALUE',
  'OVERPRICED',
  'COMFORTABLE',
  'UNCOMFORTABLE',
  'WOULD_REPEAT',
  'AVOID',
] as const;
