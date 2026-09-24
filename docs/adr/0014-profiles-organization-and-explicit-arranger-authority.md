# ADR-0014: Enterprise Context owns traveler profiles, the organization and explicit arranger authority; Travel Core snapshots the allocation

Status: accepted (platform completion, Phase 2, 2026-09-24)

## Context

Up to `61fcf14` a trip carried a four-field traveler snapshot (id, given name, family name, email)
taken from the requester's JWT claims or, for a trip arranged for someone else, typed by the
arranger into the request body. Anyone holding the MANAGER realm role could create trips for any
employee of the tenant and read every trip of the tenant. There were no passenger details a
supplier needs beyond a name (date of birth, phone, documents, loyalty), no departments, cost
centers, legal entities, offices or projects, no guests, no offboarding, and no record of who
looked at what. Real ticketing needs those; governance needs the opposite of "any manager sees
everything".

## Decision

1. **Enterprise Context is the system of record for who a traveler is** (`traveler_profile`,
   `travel_document`, `profile_change`, `sensitive_access`, migration `V2`). A profile extends an
   HRIS employee (kind `EMPLOYEE`) or stands alone for a sponsored guest (kind `GUEST`, id `gst_…`,
   `sponsor_employee_id` = whoever created it). The HRIS remains the only source of the manager
   link, of `active`, and now of the four organizational pointers an HRIS record may carry
   (`departmentId`, `costCenterId`, `legalEntityId`, `officeId`).
2. **Sensitive fields are encrypted at rest with a key the repository never sees.** Phone, date of
   birth, loyalty numbers, emergency contact and document numbers are AES-256-GCM ciphertext
   (`libs/common` `FieldCipher`, `v1:` prefix per value for rotation), keyed by
   `travelos.profiles.field-key` = `TRAVELOS_FIELD_KEY` from the secrets mechanism (kind
   `secrets.sh`, EKS External Secrets `travelos/ENV/enterprise-context/profiles`). The service
   refuses to start without a key. Reads are redacted by default (last four characters); a
   decrypted read needs `reveal=true`, a relationship that allows it, and a stated purpose, and is
   appended to `sensitive_access` (principal, traveler, what, why, when). `toString` on the records
   never prints a secret. Documents have a retention date (expiry + configured days; revocation and
   offboarding set it) after which a scheduled purge deletes the row; the access log outlives it.
3. **Authority to arrange is a recorded relationship, never a request field and never a bare
   role.** `ProfileAccess.relation` resolves, from Enterprise Context's own records: SELF, the
   TRAVEL_ADMIN role, SPONSOR (guest creator), an explicit `arranger_grant` (scope EMPLOYEE,
   ORG_UNIT, PROJECT or TENANT, optional expiry, revocable, with a separate `may_read_documents`
   permission), the HRIS MANAGER link, FINANCE (read of names and allocation only), else NONE.
   Grants are created by travel admins (a traveler may delegate their own travel) through
   `/api/v1/arrangers`. A MANAGER realm role alone is not a relationship: it neither reads a
   profile nor arranges a trip.
4. **Projects can be restricted.** `org_unit` and `project` (+ `project_member`) are administered
   under `/api/v1/org`. Travel charged to a restricted project requires the traveler to be a
   member, and the arranger to be the traveler, the traveler's manager, a member, the holder of a
   grant scoped to that project, or a travel admin. Non-members do not learn a restricted project
   exists (404).
5. **Travel Core asks, then snapshots.** Two gRPC methods on `EnterpriseContextService`:
   `AuthorizeArranger(arranger, roles, traveler, project) → allowed / basis / reason_code /
   may_read_documents` and `GetTravelerSnapshot(arranger, roles, traveler, purpose, project,
   include_documents) → passenger + allocation`, shaped by the same relationship rules and logged.
   `TripService.create` (REST, gRPC demand conversion, every entry point) authorizes through the
   first, takes the passenger identity from the second (the request body's `traveler` block is
   used only when Enterprise Context knows no profile), and writes a `trip_allocation` row (`V7`:
   department, cost center, legal entity, office, project, restricted flag, HRIS manager, arranger,
   basis, traveler kind, profile version, captured_at). Sensitive passenger data is **not** copied
   into Travel Core; suppliers get it at booking time from Enterprise Context with purpose
   `BOOKING` (Phase 4 wiring).
6. **Visibility follows the allocation.** A trip is visible to its traveler, to TRAVEL_ADMIN and
   FINANCE tenant-wide, and to the people its allocation names: the HRIS manager and the arranger.
   Approvals follow the same rule (`NOT_THE_APPROVER`). `scope=tenant` for a MANAGER now lists
   their reports' trips and the ones they arranged; `scope=arranged` lists an arranger's desk.
7. **Backward compatibility is explicit, not accidental.** The client is optional
   (`travelos.grpc.clients.enterprise-context.address`, env `ENTERPRISE_CONTEXT_ADDRESS`): unset,
   the Slice 1 rules stand and no allocation is written. Trips without an allocation row (created
   before `V7`, or while the client was unset, or for a traveler Enterprise Context does not know
   yet) keep the Slice 1 visibility. When Enterprise Context is configured but down, a person may
   still request their own travel on their claims (logged); arranging for someone else answers
   `503 CONTEXT_UNAVAILABLE` rather than falling back to a wider rule. Existing endpoints keep
   their shapes; `CreateTripRequest.projectId` and `TripResponse.allocation` are additive.

## Consequences

- Personal data lives in one service, encrypted, with a disclosure log; deleting a traveler's
  documents is a retention purge, not a manual SQL job.
- Governance questions ("who can book for whom", "who saw a passport number", "who may see this
  project's travel") have a table each: `arranger_grant`, `sensitive_access`, `project_member`.
- Phase 3+ builds on the allocation: policy scoping by department/cost center/project, approval
  routing to the allocation's manager, budgets by cost center, supplier passenger data at booking.
- Not decided here: several travelers per trip (the trip model stays single-traveler; group
  travel is a set of trips sharing a `source_reference`), and profile synchronization from an
  external profile store.

## Verification

`services/enterprise-context` `ProfileIntegrationTest` (encryption at rest, redaction, logged
reveals, manager cannot read document numbers, grants with and without documents, restricted
projects, guests, offboarding + retention purge, both gRPC methods) and `services/travel-core`
`TripArrangerIntegrationTest` (an arranger books under the passenger's profile identity whatever
the request body says; MANAGER-by-role is refused and sees nothing, including a restricted
project's trip; unknown traveler on own claims; Enterprise Context down; idempotent replay keeps
one allocation).
