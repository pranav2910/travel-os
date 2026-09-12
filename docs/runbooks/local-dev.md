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
make images       # jars + 8 images tagged ghcr.io/pranav2910/travel-os/<name>:local (~75s)
make stack-up     # infra + services, waits until every container is healthy, creates topics
make stack-e2e    # the live Slice 1 script against the containers
make stack-logs SVC=trip-planning
make stack-down   # or stack-nuke to drop the data volumes
```

Budget about 4 GB of Docker memory for the stack (each JVM is capped at 512 MB, each Python service
at 384 MB). To use Claude inside the stack: `ANTHROPIC_API_KEY=... LLM_PROVIDER=anthropic make stack-up`.

How tokens work across the network boundary: clients (you, the script) mint tokens through
`http://localhost:8180`, so that is the token's issuer. Inside the network Keycloak is `keycloak:8180`.
The `docker` Spring profile (`application-docker.yml` in each service) therefore validates the
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

- Homebrew's `openjdk@21` is not visible to `/usr/libexec/java_home` unless symlinked; the Makefile
  falls back to the keg path. Gradle also auto-provisions a JDK 21 if it finds none.
- Kafka data is intentionally not persisted locally; `make up` recreates topics every time.
- Keycloak runs in dev mode with no persistent volume: the realm is re-imported from `travelos-realm.json` on every start, so edits take effect with `make down && make up`. Anything created in the admin console is lost on restart — put it in the JSON instead.

## Kubernetes

The same platform on a local `kind` cluster (and the path to EKS): [kubernetes.md](kubernetes.md).
`make kind-up && make kind-deploy && make kind-e2e`. Stop the compose stack first; both need the memory.
