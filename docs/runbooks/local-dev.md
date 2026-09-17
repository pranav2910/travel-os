# Runbook: local development

## Start / stop

```bash
make up      # starts everything, waits for health, creates Kafka topics
make ps      # status
make logs SVC=temporal
make down    # stop, keep data
make nuke    # stop, delete volumes (Keycloak realm + Postgres re-initialise on next up)
```

Docker Desktop needs ~3 GB for the platform; Java services run on the host.

## What runs where (all DEV-ONLY credentials)

| Component | Endpoint | Credentials |
|---|---|---|
| Postgres 17 | `localhost:5432` | superuser `travelos` / `travelos-dev`; per-service roles `<db>_app` / `<db>-dev` (e.g. `travel_core_app` / `travel_core-dev`) |
| Redis 8 | `localhost:6379` | none |
| Kafka 4 (KRaft) | `localhost:9092` | PLAINTEXT; topics from `contracts/events/topics.yaml`, auto-create disabled |
| Temporal | gRPC `localhost:7233`, namespace `travelos` | — |
| Temporal UI | http://localhost:8233 | — |
| Keycloak | http://localhost:8180 (realm `travelos`) | admin console `admin` / `admin` |
| LLM gateway | gRPC `localhost:9087` (`make run-llm-gateway`) | `LLM_PROVIDER=fake` offline; set `ANTHROPIC_API_KEY` for `anthropic` (Claude Opus 5) |
| Disruption | http://localhost:8089 / gRPC :9089 (`make run SVC=disruption`) | Slice 2: consumer group `disruption` on `travel.disruption`; `GET /api/v1/trips/{tripId}/disruptions`, `GET /api/v1/disruptions/{id}`, `POST /api/v1/disruptions/{id}/approval` (MANAGER/TRAVEL_ADMIN) |
| Enterprise Context | http://localhost:8090 / gRPC :9090 (`make run SVC=enterprise-context`) | Slice 4: connectors (`/api/v1/connectors`, TRAVEL_ADMIN), demand candidates (`/api/v1/demand`), signed webhooks `POST /api/v1/connectors/{provider}/events`; gRPC `SyncPage/CompleteSync/FailSync/GetEmployee` for the worker |
| Learning | http://localhost:8091 / gRPC :9091 (`make run SVC=learning`) | Slice 5: consumer group `learning` on `travel.trip/order/disruption/optimization`; `/api/v1/learning/{config,profiles,history,summary}` (TRAVEL_ADMIN; FINANCE reads), `/outcomes`, `/feedback`, `/preferences` (travelers, own trips), `/outcomes/refunds` (FINANCE); gRPC `ResolveProfile` for the planner, `BeginBuild/ComputeProfile/EvaluateProfile/FailBuild` for the build workflow |
| Web app | http://localhost:8080 (Docker stack) · http://localhost:5173 (`make web-dev`) | the React workspace; nginx proxies `/api/v1/*` to the services; signs in through Keycloak's `travelos-web` client (PKCE) |
| Audit | http://localhost:8088 (`make run SVC=audit`) | consumer group `audit` on every `travel.*` topic; `GET /api/v1/audit/trips/{id}`, `/decisions`, `/events?type=` (TRAVEL_ADMIN/FINANCE) |

Service ports (HTTP 808x pairs with gRPC 908x): travel-core 8081 · policy 8082 / 9082 · optimization 8083 / 9083 · supplier-gateway 8084 / 9084 · order 8085 / 9085.

Databases: `enterprise_context`, `travel_core`, `policy`, `approval`, `orders`, `audit`, plus `temporal`, `temporal_visibility`.

## Dev users (realm `travelos`, password `password`)

| user | tenant | roles | employee_id |
|---|---|---|---|
| alice | acme | TRAVELER | emp_1001 |
| bob | acme | TRAVELER, MANAGER | emp_1002 |
| carol | acme | TRAVELER, TRAVEL_ADMIN, FINANCE | emp_1003 |
| zoe | globex | TRAVELER | emp_2001 (for cross-tenant isolation tests) |

## Mint an access token

```bash
curl -s -X POST http://localhost:8180/realms/travelos/protocol/openid-connect/token \
  -d client_id=travelos-dev-cli -d grant_type=password -d username=alice -d password=password \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])'
```

Claims services rely on: `iss` = `http://localhost:8180/realms/travelos`, `aud` contains `travelos-api`,
`tenant_id`, `employee_id`, `roles[]`. Inspect with `python3 -c 'import sys,base64,json; t=sys.argv[1].split(".")[1]; print(json.dumps(json.loads(base64.urlsafe_b64decode(t+"==")),indent=2))' "$TOKEN"`.

## Run a service and exercise it

```bash
make run SVC=travel-core            # http://localhost:8081, Flyway migrates travel_core on start
TOKEN=$(curl -s -X POST http://localhost:8180/realms/travelos/protocol/openid-connect/token \
  -d client_id=travelos-dev-cli -d grant_type=password -d username=alice -d password=password \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')

curl -s -X POST http://localhost:8081/api/v1/trips \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"request":"Seattle before 9am Tuesday, back Wednesday evening",
       "intent":{"origin":"BOS","destination":"SEA","earliestDeparture":"2026-10-06T10:00:00Z",
                 "arrivalDeadline":"2026-10-06T17:00:00Z","returnAfter":"2026-10-07T20:00:00Z",
                 "latestReturn":"2026-10-08T06:00:00Z","purpose":"customer meeting","hotelRequired":true}}'
# -> 202 {"tripId":"trip_01...","status":"SUBMITTED",...}; GET /api/v1/trips/{tripId}, /history,
#    POST /api/v1/trips/{tripId}/cancellation {"reason":"..."} (Idempotency-Key required on every POST)
```

Metrics: `curl -s localhost:8081/actuator/prometheus | grep travelos_outbox` — `backlog` should sit at 0.

Free text instead of a structured intent (needs the worker and the LLM gateway running):

```bash
curl -s -X POST http://localhost:8081/api/v1/trips -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"request":"Fly BOS to SEA on 2026-10-06, back 2026-10-08. Hotel needed, purpose: customer meeting"}'
# then: GET /api/v1/trips/{tripId}            -> intent frozen, explanation filled in after planning
#       GET /api/v1/trips/{tripId}/decisions  -> the agent-decision ledger (model, prompt version, cost)
```

With the fake provider the text must contain IATA codes and ISO dates; with `ANTHROPIC_API_KEY`
exported before `make run-llm-gateway`, natural phrasing ("Seattle before 9am next Tuesday, back
Wednesday evening") works and relative dates resolve against `DEFAULT_TIMEZONE` (America/New_York).
`LLM_TENANT_DAILY_BUDGET_USD` (default 5) caps model spend per tenant per day.

Policy service: `make run SVC=policy`, then `make seed-policy` publishes
`platform/local/seed/policies/acme-us-standard.json` for tenant acme as carol (TRAVEL_ADMIN).
`GET /api/v1/policies` lists versions; `GET /api/v1/policy-decisions?tripId=...` explains decisions.
gRPC (`travelos.policy.v1.PolicyService`) listens on 9082 with reflection enabled, so
`grpcurl -plaintext localhost:9082 list` works if you have grpcurl installed.

## Run the whole thing (Slice 1)

In separate terminals (or `&`): `make run SVC=travel-core`, `make run SVC=policy`,
`make run SVC=supplier-gateway`, `make run SVC=order`, `make run-worker`, `make run-optimization`.
Then:

```bash
./scripts/e2e-slice1.sh    # two trips: one through manager approval, one in policy; asserts every step
```

Temporal UI: http://localhost:8233 (namespace `travelos`, workflow id = trip id). The worker's
Kafka consumer group is `trip-planning-worker`; on a fresh group it replays history, which is safe
(workflow id = trip id, already-terminal trips complete immediately).

## Run the whole stack in Docker

Every runnable component has an image (`docker/java.Dockerfile`, `docker/python.Dockerfile`), and
`platform/local/docker-compose.app.yml` runs all eight next to the infrastructure on the same ports
as running them on the host, so `scripts/e2e-slice1.sh` works unchanged.

```bash
make images       # jars + 12 images (incl. the web app) tagged ghcr.io/pranav2910/travel-os/<name>:local
make stack-up     # infra + services + the web app on http://localhost:8080, waits until healthy, creates topics
make stack-e2e    # the live Slice 1 script against the containers
make stack-logs SVC=trip-planning
make stack-down   # or stack-nuke to drop the data volumes
```

Budget about 4 GB of Docker memory for the stack (each JVM is capped at 512 MB, each Python service
at 384 MB). To use Claude inside the stack: `ANTHROPIC_API_KEY=... LLM_PROVIDER=anthropic make stack-up`.

How tokens work across the network boundary: clients (you, the script) mint tokens through
`http://localhost:8180`, so that is the token's issuer. Inside the network Keycloak is `keycloak:8180`.
The `container` Spring profile (`application-container.yml` in each service) therefore validates the
issuer `http://localhost:8180/realms/travelos` but fetches the signing keys from
`http://keycloak:8180/...`. Change one without the other and every request is a 401.

CI builds all eight images on every run and pushes them to GHCR (`:main` and `:<sha>`) on pushes to
main, so `TAG=<sha> make stack-up` runs exactly what CI tested.

## Tracing: one trace per trip

`make up` also starts an OpenTelemetry collector (:4317 gRPC, :4318 HTTP), Tempo (:3200) and Grafana
(http://localhost:3000, anonymous admin, Tempo pre-provisioned). Every service exports spans when
`TRACING_EXPORT_ENABLED=true` (set by `make run*` and the Docker stack; off in tests). The trace
crosses every boundary:

| Hop | Mechanism |
|---|---|
| HTTP request -> service | Spring's observation on the server; `trip.id` tagged once the trip exists |
| service -> outbox -> Kafka | the appending transaction's `traceparent` is stored on the row and restored at relay; the record carries it as a header |
| Kafka -> consumer (worker, audit) | Spring Kafka consumer observation (`spring.kafka.listener.observation-enabled`) |
| consumer -> Temporal workflow -> activities | Temporal's OpenTracing interceptors over the OTel shim (workflow headers) |
| activity -> gRPC service | Micrometer gRPC client/server interceptors (`libs/spring-grpc-support`); `RequestContexts.require` tags `trip.id`, `tenant.id`, `principal.id` |
| gRPC -> Python (optimization, llm-gateway) | OpenTelemetry gRPC server instrumentation; `OTEL_EXPORTER_OTLP_ENDPOINT` |

Find a trip in Grafana -> Explore -> Tempo -> TraceQL: `{ span.trip.id = "trip_01..." }`. From the
shell: `curl -s 'localhost:3200/api/search?q=%7B%20span.trip.id%20%3D%20%22trip_01...%22%20%7D'`.
The e2e script's last section asserts that one trace id covers at least five services.

Boot 4 gotcha: `management.tracing.export.enabled=false` also installs a no-op propagator, so with
export off the trace does not cross hops even locally. That is why the switch is on for any real run.

## Kafka

```bash
docker compose -f platform/local/docker-compose.yml exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic travel.trip --from-beginning
```

## Postgres

```bash
docker compose -f platform/local/docker-compose.yml exec postgres psql -U travelos -d travel_core
```

## Gotchas

- The Postgres init script (`platform/local/postgres/init/01-databases.sql`) runs only when the
  `postgres-data` volume is created. A volume from before a slice that added a database (Slice 2:
  `disruption`, Slice 5: `learning`) makes that service restart-loop with "password authentication
  failed for user <svc>_app". Either `make stack-nuke` (drops the local data volumes) or create just
  the missing role and database with the statements from that file.
- `make stack-up` creates the Kafka topics only after every container is healthy. If the `--wait`
  step fails (a restart-looping service), no topic exists, the outboxes hold every event and trips
  stay SUBMITTED forever: fix the service, then rerun `make stack-up` (or
  `docker compose -f platform/local/docker-compose.yml run --rm kafka-init`).

- Homebrew's `openjdk@21` is not visible to `/usr/libexec/java_home` unless symlinked; the Makefile
  falls back to the keg path. Gradle also auto-provisions a JDK 21 if it finds none.
- Kafka data is intentionally not persisted locally; `make up` recreates topics every time.
- Keycloak runs in dev mode with no persistent volume: the realm is re-imported from `travelos-realm.json` on every start, so edits take effect with `make down && make up`. Anything created in the admin console is lost on restart — put it in the JSON instead.

## Kubernetes

The same platform on a local `kind` cluster (and the path to EKS): [kubernetes.md](kubernetes.md).
`make kind-up && make kind-deploy && make kind-e2e`. Stop the compose stack first; both need the memory.

## Slice 2: cancel a flight and watch the recovery

The sandbox airline sends signed notices to the gateway's webhook (`POST /api/v1/suppliers/sandbox-air/events`,
`X-Supplier-Signature: sha256=<hmac of the body>` with `SANDBOX_AIR_WEBHOOK_SECRET`, dev default
`sandbox-air-dev-webhook-secret`). `scripts/e2e-slice2.sh` does the whole thing (book, cancel, recover
autonomously, cancel again with a +$180 replacement and approve as bob, try an injected instruction,
redeliver the webhook, check the ledger and the trace). `make stack-e2e2` runs it against the Docker
stack, `make kind-e2e2` against kind, `make kind-chaos2` takes the order service away at
cancellation (impact is deferred, not dropped), parks the recovery at `Optimize` by removing the
optimizer, removes the order service underneath it, lets the optimizer back so `ChangeOrder` retries
with nobody to call, kills the recovery worker mid-retry, and proves one logical recovery. Each hold
is confirmed by a Temporal pending-activity tripwire (attempt ≥ 2) before the next fault is injected.

## Slice 3: hotels, ground and multi-city itineraries

`POST /api/v1/trips` accepts an `intent.itinerary` (ordered `legs`, `stays` with local dates,
`transfers` by kind) instead of origin/destination; the response echoes it with stable `cmp_`
component ids and, once planning starts, `components` with a status, supplier reference and total
per leg, stay and transfer (`GET /api/v1/trips/{id}/components`). Orders carry one item per
component with `hotel` / `ground` views and, after a failed compensation, `exposures` that a
TRAVEL_ADMIN or FINANCE person resolves with
`POST /api/v1/orders/{id}/exposures/{exposureId}/resolution`.

The SIMULATED hotel (`sandbox-hotel`) and ground (`sandbox-ground`) adapters live in the Supplier
Gateway. Hotels are priced per property and night; ground offers are pickups on the quarter hour
every 30 minutes across the whole requested window (the workflow asks for a window wide enough for
any flight the optimizer may still choose; the optimizer then pairs each flight with a pickup 45
minutes to 4 hours after landing, or a drop-off 90 minutes before departure). Their faults are
catalog fixtures chosen by city, always the city's cheapest entry:

| City | Fixture |
|---|---|
| SFO | cheapest hotel re-prices +USD 40/night on revalidation (stale approval) |
| ORD | cheapest hotel's quote lives ten seconds (re-quoted at the same price) |
| DEN | cheapest hotel and shuttle commit the booking, then lose the answer (status lookup) |
| AUS | cheapest hotel refuses the booking (compensation of the legs) |
| LAX | cheapest hotel refuses cancellation, cheapest shuttle refuses the booking (exposure) |
| MIA | cheapest hotel's description tries to instruct the platform (data, never an order) |
| LHR | rates in GBP (an unsupported currency combination, denied explicitly) |
| ZZZ | simulated outage (retryable UNAVAILABLE) |

`scripts/e2e-slice3.sh` walks every acceptance scenario (`make stack-e2e3` against the Docker
stack, `make kind-e2e3` against kind); `make kind-chaos3` is the deterministic chaos run.

## Slice 4: travel-demand detection (SIMULATED connectors)

`services/enterprise-context` owns the verified employee directory, the tenant's connectors and
the demand candidates. Everything below is the SIMULATED path: `sandbox-calendar`, `sandbox-crm`,
`sandbox-hris` and `sandbox-expense` are tables inside the service with the same paging contract a
live provider would have (`EnterpriseSource<T>`); nothing talks to a real calendar, CRM, HRIS or
expense system yet.

Demo, against the Docker stack or kind (the E2E script does all of this and more):

```bash
# 1. connectors (carol, TRAVEL_ADMIN). `scheduled:false` keeps the demo deterministic; without it the
#    scheduler requests a run every CONTEXT_SYNC_INTERVAL (60s, 30s on kind).
curl -X POST $CONTEXT/api/v1/connectors -H "Authorization: Bearer $CAROL" -H 'Content-Type: application/json' \
  -d '{"kind":"HRIS","provider":"sandbox-hris","config":{"scheduled":false}}'
# 2. what the sandbox system holds next (one item per source id + revision; a live provider would page these)
curl -X POST $CONTEXT/api/v1/connectors/$HRIS/sandbox/items -H "Authorization: Bearer $CAROL" -H 'Content-Type: application/json' \
  -d '{"items":[{"sourceId":"emp_1001","revision":1,"payload":{"employeeId":"emp_1001","email":"alice@acme.example","displayName":"Alice Nguyen","workLocation":"BOS","timeZone":"America/New_York","managerEmployeeId":"emp_1002","active":true}}]}'
# 3. a run now (or a signed webhook: POST /api/v1/connectors/sandbox-calendar/events, X-Connector-Signature: sha256=<hmac>)
curl -X POST $CONTEXT/api/v1/connectors/$CAL/sync -H "Authorization: Bearer $CAROL" -H "Idempotency-Key: $(uuidgen)"
curl $CONTEXT/api/v1/connectors/$CAL/runs -H "Authorization: Bearer $CAROL"       # status, pages, items, candidates touched
# 4. the traveler's demand, its evidence, and the three actions
curl $CONTEXT/api/v1/demand -H "Authorization: Bearer $ALICE"
curl $CONTEXT/api/v1/demand/$DMD/evidence -H "Authorization: Bearer $ALICE"
curl -X POST $CONTEXT/api/v1/demand/$DMD/details -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' -d '{"destination":"ORD"}'
curl -X POST $CONTEXT/api/v1/demand/$DMD/conversion -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: $(uuidgen)"   # -> tripId
```

Normalized item shapes (the sandbox stores them as they are; a live adapter maps its vendor's shape):

| Kind | Payload | Rule (`rules-v1`) |
|---|---|---|
| CALENDAR | `sourceId, seriesId?, title, description?, organizerEmail, attendees[{email,status}], start, end, timeZone, location{text,city,kind}?, conferencingUrl?, status, attendanceMode` | confirmed + accepted (or organized) + in person + city known and not the work location + in the future → candidate; unknown place → `NEEDS_REVIEW` (missing destination); virtual / declined / tentative / cancelled / local / past → named exclusion |
| CRM | `sourceId, kind(VISIT/DEAL/NOTE), ownerEmail, accountName?, accountCity?, scheduledStart?, scheduledEnd?, timeZone?, onSite, status, calendarEventId?, dealValueMinor?, stage?, notes?` | only a scheduled on-site VISIT; `calendarEventId` links it to the calendar's candidate, else same traveler + city + overlapping dates |
| HRIS | `employeeId, email, displayName, workLocation, timeZone, managerEmployeeId?, active` | upserts the directory (newer revision wins); an inactive employee withdraws their open candidates |
| EXPENSE | `sourceId, employeeEmail, kind(RECEIPT/PREAPPROVAL/TRIP_REPORT), city?, startDate, endDate, amountMinor, currency, merchant?, tripId?` | never creates demand; past spend in the city is ENRICHMENT, an overlapping pre-approval or trip report is a DUPLICATE_SIGNAL (`POSSIBLE_DUPLICATE:<id>` → `NEEDS_REVIEW`) |

Sandbox faults, per connector: `POST /api/v1/connectors/{id}/sandbox/faults {"unavailableCalls": n, "rateLimitPage": p}`
(the next `n` fetches fail retryably; page `p` is rate limited once). `scripts/e2e-slice4.sh` walks
every acceptance scenario (`make stack-e2e4` / `make kind-e2e4`); `make kind-chaos4` is the
deterministic chaos run. Slice 3 carry-over fixture: a cancellation notice with
`"reaccommodation": {"fareDeltaMinor": 7300, "nextDay": true}` leaves the disrupted passenger nothing
on the cancelled date and reprices the next date, so the recovery re-dates the hotel.

## Slice 5: learning from outcomes (SANDBOX evidence)

`services/learning` owns the outcome ledger, feedback and the versioned profiles. Everything the
platform can observe here comes from SIMULATED suppliers, so every outcome is `SANDBOX` evidence and
the local deployment class is `SANDBOX` (`LEARNING_DEPLOYMENT_CLASS`); a production deployment says
`LIVE` and never activates a sandbox-trained profile. The stack and kind run `LEARNING_SCALE=100` so a
demo's handful of outcomes reaches the ±10 bound; the production default is 40.

Demo, against the Docker stack or kind (`scripts/e2e-slice5.sh` does all of this and more):

```bash
# 0. the tenant's mode (default SHADOW). OFF reproduces the baseline; ACTIVE applies an eligible profile.
curl $LEARNING/api/v1/learning/config -H "Authorization: Bearer $CAROL"
curl -X PUT $LEARNING/api/v1/learning/config -H "Authorization: Bearer $CAROL" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' -d '{"mode":"SHADOW"}'
# 1. book trips, let the sandbox airline cancel some (scripts/e2e-slice2.sh notify), attest a completion as an admin,
#    let Finance record a settled refund; every outcome of a trip, with revisions and provenance:
curl "$LEARNING/api/v1/learning/outcomes?tripId=$TRIP" -H "Authorization: Bearer $ALICE"
curl -X POST $CORE/api/v1/trips/$TRIP/completion -H "Authorization: Bearer $CAROL" -H "Idempotency-Key: $(uuidgen)"
curl -X POST $LEARNING/api/v1/learning/outcomes/refunds -H "Authorization: Bearer $CAROL" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"tripId":"'$TRIP'","orderId":"'$ORDER'","itemId":"'$ITEM'","amountMinor":52000,"currency":"USD","reference":"RF-1"}'
# 2. the traveler's structured feedback (tags from a fixed vocabulary; the comment is stored as text, never used)
curl -X POST $LEARNING/api/v1/learning/feedback -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"tripId":"'$TRIP'","componentId":"'$ITEM'","rating":2,"tags":["DELAYED"],"comment":"late both ways"}'
# 3. build + evaluate a profile (the worker runs LearningBuildWorkflow), inspect it, activate it, roll back
curl -X POST $LEARNING/api/v1/learning/profiles -H "Authorization: Bearer $CAROL" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' -d '{"window":"PT2H"}'
curl $LEARNING/api/v1/learning/profiles/$LP -H "Authorization: Bearer $CAROL"          # status, suppliers, evaluation report, fingerprint
curl -X POST $LEARNING/api/v1/learning/profiles/$LP/activation -H "Authorization: Bearer $CAROL" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' -d '{"expectedVersion":3}'
curl -X POST $LEARNING/api/v1/learning/rollback -H "Authorization: Bearer $CAROL" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' -d '{}'   # previous eligible profile, else baseline
curl -X POST $LEARNING/api/v1/learning/rollback -H "Authorization: Bearer $CAROL" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' -d '{"toBaseline":true}'   # no profile at all
# 4. what a plan used: the ledger's learning section (mode, profile, applied, both selections, contributions)
curl $AUDIT/api/v1/audit/trips/$TRIP/decisions -H "Authorization: Bearer $ALICE" | jq .learning
```

Fallbacks a plan can record instead of a profile: `MODE_OFF`, `NO_ACTIVE_PROFILE`, `STALE_PROFILE`,
`CLASS_MISMATCH`, `INCOMPATIBLE_ALGORITHM`, `LEARNING_UNAVAILABLE` (the service did not answer within
3 attempts; the trip proceeds on the baseline). `make stack-e2e5` / `make kind-e2e5` run the
acceptance script; `make kind-chaos5` the deterministic chaos run.

## The web app

`web/` (React + TypeScript, Vite). Sign in as one of the realm's users (`password`): alice
(traveler), bob (traveler + manager), carol (traveler + travel admin + Finance), dan (traveler), zoe
(another tenant). The header always says "Sandbox — simulated bookings".

- The edge container runs with a read-only root filesystem on Kubernetes **and** in compose
  (`read_only: true`, tmpfs `/tmp`): everything nginx renders or writes (`/tmp/conf.d`, the proxy
  snippet, `config.json`, pid, temp dirs) lives under `/tmp`. A quick check of the image alone:
  `docker run --rm --read-only --tmpfs /tmp -p 8079:8080 ghcr.io/pranav2910/travel-os/web:local`
  then `curl localhost:8079/healthz`.

```bash
make web-install                  # npm ci (pinned versions)
make web-dev                      # http://localhost:5173, /api proxied to the services on their host ports (make up + make run ...)
make web-check                    # eslint, tsc, vitest, production build
make images && make stack-up      # the app in the Docker stack on http://localhost:8080
make web-e2e                      # Playwright against the stack (needs: npx playwright install chromium webkit)
#   E2E_BASE_URL (web edge), E2E_KEYCLOAK_URL, E2E_SUPPLIER_URL (supplier notices go to the gateway's own
#   port: the edge only proxies browser routes) and E2E_WEBHOOK_SECRET are the knobs; the Makefile sets them.
```

Demo path: alice → New trip (round trip BOS→SEA, a wide UTC window) → the page follows the
workflow to Booked and shows the bookings, the timeline and "Why this option"; bob → Approvals when
a policy requires a manager (`managerRequiredAbove`); carol → Finance / Connectors / Learning. The
request page states that submitting books; there is no preview mode.
