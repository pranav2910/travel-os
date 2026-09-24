# Capability matrix — baseline at `61fcf14`, updated per phase (branch `platform/complete-backend`, 2026-09-24)

Status legend: **verified** = executed tests or the QA runs prove it on the current code; **unverified** = code exists,
no test exercises it; **partial** = some of the capability; **simulated** = works only against the sandbox
adapters/sources; **absent** = no code; **blocked** = implementable but its verification needs external
access (credentials, accounts, partners). Evidence points at source, tests and the QA ledgers
(`qa/qa-20260923-cda5ccb`, `qa/qa-20260923-repair-cda5ccb`, historical references — present behaviour is
determined from the code and the tests run on this branch).

| Area | Capability | Status | Evidence |
|---|---|---|---|
| Identity | OIDC sign-in (Keycloak dev realm, PKCE web client, JWT resource servers, tenant + roles from claims) | verified (simulated IdP) | `libs/spring-web` (`RequestPrincipal`), `TestTokens`; web `auth.spec.ts`; QA P2-* |
| Identity | Enterprise federation (SAML/OIDC to a customer IdP), SCIM provisioning, MFA policy | absent | — |
| Traveler & org | Verified employee identity from HRIS (id, email, display name, work location, time zone, manager, active) | verified (simulated HRIS) | `services/enterprise-context` `Employee`, `HrisSource`; `DemandServiceTest`/integration; QA P4 slice-4 |
| Traveler & org | Traveler profile: passenger-name details, contact, documents, loyalty, preferences; encrypted at rest, redacted by default, logged disclosure, change history, document retention | verified (Phase 2) | `services/enterprise-context` `ProfileService`, `ProfileRepository`, `FieldCipher`; `/api/v1/travelers`; `ProfileIntegrationTest` |
| Traveler & org | Departments, legal entities, offices, cost centers, projects/clients (restricted projects with members); allocation snapshot on every trip | verified (Phase 2) | `OrgService`, `/api/v1/org`, `trip_allocation` (`V7`), `TripArrangerIntegrationTest` |
| Traveler & org | Arranged travel under the passenger's profile identity (request fields cannot substitute an identity or widen permissions) | verified (Phase 2) | `TripService.create` + `ContextClient`, `TripArrangerIntegrationTest.anArrangerBooksUnderThePassengersOwnIdentity…` |
| Traveler & org | Explicit arranger grants (scope employee/org unit/project/tenant, separate document permission, expiry, revocation), guest travelers with sponsors, deactivation (grants end, documents enter retention) | verified (Phase 2) | `ArrangerService`, `/api/v1/arrangers`, `/api/v1/travelers/guests`, `/api/v1/employees/{id}/deactivation`, `ProfileIntegrationTest` |
| Traveler & org | Several travelers per trip | absent (single-traveler trip model; group travel = trips sharing a `source_reference`) | ADR-0014 |
| Request & planning | Structured request (round trip, one way, multi-city with stays/transfers), catalog/clock/currency validation | verified | `IntentValidation`, `Itinerary`, API/lifecycle integration tests; QA repair run |
| Request & planning | Free text → intent (single shot, deterministic offline extractor; real model when a key is configured) | verified (fake) / blocked (model) | `intelligence/llm-gateway`, `TripWorkflowImpl` understanding step |
| Request & planning | Draft requests, search/compare without booking (QUOTED), option selection, price refresh, explicit purchase authorization bound to plan/price/currency/conditions/traveler/expiry, consumed once at booking; policy-granted autonomy recorded as an auditable authorization | verified (Phase 3) | ADR-0015; `purchase_authorization` (`V8`); `/api/v1/trips/{id}/purchase`, `/selection`, `/quote-refresh`, `/submission`, `/draft`; `PurchaseApiIntegrationTest`, `TripWorkflowTest` (Phase 3 cases) books after APPROVED without a separate authorization |
| Request & planning | Booking horizon / trip-length rules (`trip.maxAdvanceDays`, `minLeadHours`, `maxDurationDays`, `onHorizonViolation`), judged against the workflow's reference time | verified (Phase 3) | `PolicyEngine.BookingHorizonRule`, `PolicyEngineTest`; seed policy |
| Request & planning | Conversational (multi-turn) request flow: persisted conversations whose turns are regular trips through the same service; clarifying questions park the thread; answers carry the transcript | verified (Phase 3; understanding itself is the fake extractor unless a model key is configured) | `ConversationService`, `/api/v1/conversations`, `PurchaseApiIntegrationTest.aConversationPersists…` |
| Request & planning | Search preferences (cabin, nonstop, refundable-only, preferred carriers, max stops) narrowing the search; fare conditions bound by the authorization | verified (Phase 3, simulated suppliers) | `TravelIntent.SearchPreferences`, `Purchase.filter/cabins`, `TripWorkflowTest.searchPreferencesNarrow…` |
| Policy | Versioned policy documents, evaluation with evidence and economics, approval threshold, autonomy for rebooking | verified | `services/policy`, `PolicyEngineTest`, QA P5-POL-* |
| Policy | Organizationally scoped policies, approval chains, delegates, expiry/escalation, budgets, supplier agreements | absent (one default policy per tenant; `policy_assignment` table exists) | `V1__policy.sql` |
| Approval | Manager approval with self-approval refusal, stale-version conflicts, signals + re-read; the approver must be the allocation's manager or arranger (or TRAVEL_ADMIN) | verified | `TripService.decideApproval`, `TripAccess.canApprove`, `approvals.spec.ts` |
| Suppliers | Air/hotel/ground search, price, quote, create, change, cancel, booking status, capabilities, signed notifications — sandbox adapters | verified (simulated) | `services/supplier-gateway/sandbox`, `Sandbox*IntegrationTest`, QA P5-SUP-* |
| Suppliers | Real air (NDC/aggregator), hotel, transfer adapters; rail; car rental; negotiated rates | absent / blocked (no accounts) | `AirSupplier`/`HotelSupplier`/`GroundSupplier` seams |
| Suppliers | Per-mutation attempt persistence before external side effects; reconciliation of lost answers | partial (idempotent create + status lookup reconciliation in Order; sandbox-side idempotency) | `OrderService.reconcile`, `SupplierClient` |
| Locations | Airport catalog (~100) with time zones; city codes explained | verified | `Locations`, `LocationsTest` |
| Booking | Order saga: create, confirm, compensate in reverse, exposures, resumable cancellation, refunds recorded per item, change (disruption) | verified (simulated suppliers) | `OrderService`, `OrderIntegrationTest` |
| Booking | Purchase authorization bound to travelers/itinerary/price/conditions; revalidation before execution supersedes a stale one; BOOKING refused without a covering authorization | verified (Phase 3) | `TripService.reconcilePurchase`, `ItineraryFlow.awaitConfirmation`, `PurchaseApiIntegrationTest.bookingWithoutAnAuthorization…` (was: `ItineraryFlow` revalidation |
| Payments | Payment authorization/capture/void/refund, tokenized instruments, supplier balances/invoicing, FX provenance | absent (a placeholder `paymentToken` string) | `TripWorkflowImpl.paymentToken()` |
| Finance | Exposures with resolution; Finance-recorded settled refunds (a statement in Learning) | verified | `OrderService.resolveExposure`, `LearningController.refund` |
| Finance | Authoritative settlement records, reconciliation with provider records, receipts/invoices | absent | — |
| Servicing | Disruption recovery (supplier notice → impact → search → policy → optimize → autonomy or approval → change) | verified (simulated) | `services/disruption`, `RecoveryWorkflowImpl`, `operations.spec.ts` |
| Servicing | Traveler-requested changes, component-level cancellation, per-passenger changes, credits | absent (whole-trip cancellation only) | — |
| Servicing | Operational cases with owner/status/next action/escalation | partial (exposures only) | `ExposureRecord` |
| Governance | Tenant isolation in every query; 404 semantics; roles TRAVELER/MANAGER/TRAVEL_ADMIN/FINANCE | verified | QA P2-ISO-*, `TripAccess` |
| Governance | Manager scope limited to own reports and arranged trips; restricted project travel invisible to unrelated managers; MANAGER-by-role arranges nothing | verified (Phase 2; legacy trips without an allocation keep the Slice 1 rule) | `TripAccess`, `TripRepository.listForManager`, `TripArrangerIntegrationTest.restrictedProjectTravelIsInvisible…` |
| Enterprise sources | Calendar/CRM/HRIS/expense connectors: sync runs, revisions, sandbox faults, demand detection with evidence | verified (simulated sources) | `services/enterprise-context`, QA slice-4 |
| Enterprise sources | Genuine Google/Microsoft/Salesforce/Workday adapters with OAuth, incremental sync, revocation | absent / blocked | `EnterpriseSource` seam |
| Notifications | Durable notifications to travelers/approvers (email/chat/in-app), templates, preferences | absent (events on Kafka only) | — |
| Assistance & safety | Case management, traveler-safety (affected travelers, advisories, acknowledgements) | absent | — |
| Reporting | Spend/exception/outcome reporting and exports | partial (Learning summary; audit event query) | `LearningController.summary`, `/api/v1/audit/events` |
| Learning | Bounded, evaluated, reversible learned preferences (Shadow/Active), outcomes ledger | verified | `services/learning`, `learning.spec.ts` |
| Audit | Immutable event store, per-trip decision ledger with narrative, demand trail | verified | `services/audit`, `AuditServiceIntegrationTest` |
| Workflows | Temporal trip planning, cancellation, disruption recovery, demand sync, learning build; restarts and lost answers | verified (simulated) | `workflows/trip-planning`, `TripWorkflowTest`, `TripCancellationWorkflowTest`, QA P12 |
| Observability | OpenTelemetry traces, metrics, Grafana/Tempo locally | verified locally | `platform/local`, QA P12-OBS-01 |
| Deployment | Docker compose stack, kind chart, EKS values, Terraform static checks, CI (gradle, web, images, helm, terraform, kind e2e) | verified (local/CI) / blocked (no cloud account) | `.github/workflows/ci.yml`, `deploy/` |
| Performance | Latency percentiles measured on the local stack (QA P13); no baselines under supplier delay/concurrent tenants | partial | QA P13-LAT-01 |

Baseline regression: `JAVA_HOME=<jdk21> ./gradlew check` and `cd web && npm run check` on `61fcf14`
(results recorded in `docs/program/verification.md`).
