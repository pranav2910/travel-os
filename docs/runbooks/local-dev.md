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
