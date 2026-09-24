# API contracts added by the platform-completion program

Frontend handoff reference. Every endpoint is tenant-scoped by the JWT; errors are
`{ "code": "...", "message": "..." }` with the HTTP status the code implies (404 when the caller may
not know the object exists; 403 when they may know but may not act; 409 conflicts; 422 invalid).
Mutations that create or change state take an `Idempotency-Key` header where noted.

## Phase 2 — traveler profiles, organization, arrangers (Enterprise Context, port 8090; ADR-0014)

### Profiles `/api/v1/travelers`

| Method & path | Who | Body / query | Returns |
|---|---|---|---|
| `GET /me?reveal=false` | any employee | `reveal=true` decrypts phone, date of birth, loyalty numbers, emergency contact (logged) | `ProfileView` |
| `PUT /me` | any employee | `ProfileRequest` (partial: only present fields change; `expectedVersion` optional, 409 `PROFILE_VERSION_STALE`) | `ProfileView` |
| `GET /{travelerId}?reveal=&purpose=` | SELF, TRAVEL_ADMIN, SPONSOR, MANAGER (HRIS), GRANT holder, FINANCE | `purpose` `^[A-Z_:0-9-]{1,80}$` (default `PROFILE_VIEW`) written to the access log on reveal | `ProfileView` with `relation` (why you may see it) and `sensitiveRevealed` |
| `PUT /{travelerId}` | SELF, TRAVEL_ADMIN, SPONSOR, GRANT holder | `ProfileRequest` | `ProfileView` |
| `GET /{travelerId}/changes` | anyone who may read the profile | — | `[{version, changedBy, changedAt, fields[]}]` (field names, never values) |
| `GET /{travelerId}/access-log` | SELF, TRAVEL_ADMIN | — | `[{principal, kind, purpose, at}]` newest first |
| `GET /guests` | any employee | — | guests the caller sponsors |
| `POST /guests` | any employee | `ProfileRequest` (givenName, familyName, email required) | 201 `ProfileView`, `travelerId` = `gst_…`, `sponsorEmployeeId` = caller |
| `GET /{travelerId}/documents` | anyone who may read the profile | — | `[DocumentView]` masked (`number` null, `numberLast4`) |
| `POST /{travelerId}/documents` | writers | `{type: PASSPORT|NATIONAL_ID|VISA, number, issuingCountry, nationality?, issuedOn?, expiresOn, holderGivenName?, holderFamilyName?}` | 201 `DocumentView` masked |
| `GET /documents/{documentId}/number?purpose=` | SELF, TRAVEL_ADMIN, SPONSOR, grant with `mayReadDocuments` | — | `DocumentView` with `number`; 403 `DOCUMENT_ACCESS_DENIED`; 409 `DOCUMENT_REVOKED`; logged |
| `DELETE /documents/{documentId}` | writers | — | 204; the row enters retention |

`ProfileView`: `travelerId, kind (EMPLOYEE|GUEST), givenName, familyName, middleName, email, phone, dateOfBirth, gender (M|F|X), nationality (ISO alpha-2), homeAirport (IATA), preferences{}, loyalty[{program, memberNumber, memberNumberLast4}], emergencyContact{name, phone, relation}, sponsorEmployeeId, active, version, relation, sensitiveRevealed, updatedAt`.
Redacted fields are `null` unless revealed.

### Organization `/api/v1/org`

| Method & path | Who | Body | Returns |
|---|---|---|---|
| `GET /units` | any | — | `[OrgUnit{unitId, kind (DEPARTMENT|LEGAL_ENTITY|OFFICE|COST_CENTER), code, name, parentUnitId, legalEntityId, active, version}]` |
| `POST /units` | TRAVEL_ADMIN | `{kind, code, name, parentUnitId?, legalEntityId?}` | 201 `OrgUnit`; 409 `ORG_UNIT_EXISTS` |
| `GET /projects` | any | — | all projects for TRAVEL_ADMIN/FINANCE; for others every open project plus restricted ones they are a member of |
| `GET /projects/{projectId}` | members, TRAVEL_ADMIN, FINANCE (404 otherwise when restricted) | — | `Project{projectId, code, name, client, costCenterId, restricted, active, version}` |
| `POST /projects` | TRAVEL_ADMIN | `{code, name, client?, costCenterId?, restricted}` | 201 `Project` |
| `GET /projects/{projectId}/members` | as above | — | `[{projectId, employeeId, role, since}]` |
| `POST /projects/{projectId}/members` | TRAVEL_ADMIN | `{employeeId, role?}` | 201 |

### Arranger grants `/api/v1/arrangers`

| Method & path | Who | Body | Returns |
|---|---|---|---|
| `GET ?arrangerEmployeeId=` | TRAVEL_ADMIN/FINANCE (all or one arranger's); others see their own | — | `[ArrangerGrant{grantId, arrangerEmployeeId, scope (EMPLOYEE|ORG_UNIT|PROJECT|TENANT), scopeId, mayReadDocuments, grantedBy, grantedAt, expiresAt, revokedAt}]` |
| `POST` | TRAVEL_ADMIN; a traveler for `scope=EMPLOYEE, scopeId=self` | `{arrangerEmployeeId, scope, scopeId?, mayReadDocuments, expiresAt?}` | 201 |
| `DELETE /{grantId}` | TRAVEL_ADMIN; the traveler for a self-delegation | — | 204; 409 `GRANT_ALREADY_REVOKED` |

### Offboarding

`POST /api/v1/employees/{employeeId}/deactivation` (TRAVEL_ADMIN, `Idempotency-Key`) →
`{employeeId, employeeDeactivated, profileDeactivated, grantsRevoked, documentsRevoked}`.

### Travel Core additions (port 8081)

- `POST /api/v1/trips` body gains `projectId?`. Authorization errors: 403 `NOT_AN_ARRANGER`,
  `TRAVELER_INACTIVE`, `TRAVELER_UNKNOWN`, `PROJECT_RESTRICTED`, `PROJECT_UNKNOWN`; 503
  `CONTEXT_UNAVAILABLE` when arranging for someone else while Enterprise Context is down.
  The `traveler` block is used only when Enterprise Context knows no profile.
- `GET /api/v1/trips/{tripId}` gains `allocation?`: `{departmentId, costCenterId, legalEntityId,
  officeId, projectId, projectRestricted, managerEmployeeId, arrangerEmployeeId, arrangerBasis
  (SELF|MANAGER|SPONSOR|GRANT|TRAVEL_ADMIN|LEGACY_ROLE), travelerKind}`; absent on legacy trips.
- `GET /api/v1/trips?scope=arranged` — trips the caller arranged for others. `scope=tenant` for a
  MANAGER now returns their reports' and arranged trips only.
- `POST /api/v1/trips/{tripId}/approval` may answer 403 `NOT_THE_APPROVER`.

### gRPC (`travelos.context.v1.EnterpriseContextService`)

`AuthorizeArranger`, `GetTravelerSnapshot` — see `contracts/protobuf/.../context/v1/context.proto`.
`travelos.trip.v1.Trip.allocation` and `CreateTripRequest.project_id` are additive.
