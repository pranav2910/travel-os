# Runbook: enabling a real supplier (Duffel, Hotelbeds)

The gateway runs on SIMULATED suppliers until a real one's credentials are present. Nothing in
this repository holds or defaults a credential; `GetCapabilities` tells you what is live.

## Where credentials go

| Environment | How |
|---|---|
| Local compose | export `DUFFEL_ACCESS_TOKEN`, `HOTELBEDS_API_KEY`, `HOTELBEDS_SECRET` in the shell before `make stack-up`; compose passes them through (`platform/local/docker-compose.app.yml`). |
| kind | export the same variables before `deploy/kind/secrets.sh`; they land in the `supplier-gateway-secrets` Secret and are not written to `.secrets.env`. |
| EKS | create `travelos/<env>/supplier-gateway/duffel` (`access_token`) and `travelos/<env>/supplier-gateway/hotelbeds` (`api_key`, `secret`) in Secrets Manager; External Secrets maps them (`deploy/helm/travelos/values-eks.yaml`). Open egress to `api.duffel.com` / `api.hotelbeds.com` in the supplier-gateway network policy (`extraEgress`). |

Start with **test** credentials: a Duffel token that starts with `duffel_test_`, and the Hotelbeds
test host (`HOTELBEDS_BASE_URL=https://api.test.hotelbeds.com`, the default). The gateway logs
which environment it registered at startup and never guesses.

## Verifying

1. Contract tests (no network): `./gradlew :services:supplier-gateway:test`.
2. Live search smoke (credentials in the shell): `DUFFEL_ACCESS_TOKEN=... ./gradlew :services:supplier-gateway:test --tests '*LiveSupplierSmokeTest*'` — a search only; it never books.
3. On the stack: `GetCapabilities(provider=duffel)` answers `integration: LIVE`; a search on the
   web shows `duffel` offers. A booking in the Duffel test environment issues test tickets and
   charges the test balance; treat it as a real reservation of test inventory.

## When an answer is lost

`supplier_mutation_attempt` in the gateway database lists STARTED/UNKNOWN attempts
(`status IN ('STARTED','UNKNOWN')`). For each: the `external_ref` when known, the provider and the
key. Check the supplier's dashboard (Duffel: Orders; Hotelbeds: bookings by client reference =
first 20 hex characters of SHA-256 of the key, upper case). Then resolve the order's exposure in
the Order service (`POST /api/v1/orders/{id}/exposures/{exposureId}/resolution`) with what you
found. Never retry the mutation by hand with the same key: the ledger refuses it on purpose.

## Rotating

Rotate at the secret store, restart the gateway. The adapters read credentials at startup only.
