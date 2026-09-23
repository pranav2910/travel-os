# QA run qa-20260923-cda5ccb — configuration and environment

| Item | Value |
|---|---|
| APP_URL | http://localhost:8080 (web edge: React app + nginx proxy to the services) |
| Repository | ~/travel-os, branch main, commit `cda5ccb` (working tree clean apart from this `qa/` folder) |
| Test environment | isolated local Docker stack (`make stack-up`), 21 containers, images tagged `:local` built from `cda5ccb` |
| Accounts | alice (acme, TRAVELER), bob (acme, MANAGER+TRAVELER), carol (acme, TRAVEL_ADMIN+FINANCE+TRAVELER), dan (acme, TRAVELER), zoe (globex, TRAVELER). Verified from live token claims, not assumed. Password is the realm's seeded dev password; never written to evidence. |
| Credential source | Keycloak realm import `platform/local/keycloak/travelos-realm.json` (dev seed); browser flow = public client `travelos-web` (Authorization Code + PKCE); API flow = public client `travelos-dev-cli` (password grant, dev only) |
| Token lifetimes | access token 900 s, SSO idle 1800 s, no SSO max |
| BASE_DATE | execution clock 2026-09-23 02:54 UTC = 2026-09-22 22:54 America/New_York (no frozen clock; dates resolved from this clock) |
| USER_TIMEZONE | America/New_York (the sandbox airline schedules in UTC by documented design) |
| PROVIDER_MODE | sandbox only: sandbox-air, sandbox-hotel, sandbox-ground, sandbox-calendar/crm/hris/expense; no live provider exists |
| AUTHORIZED_SERVICE_FAULTS | this local stack only; containers may be stopped/started one at a time; named volumes are never deleted |
| LOAD_LIMIT | 10 concurrent users; no separate load environment exists, so 50/100 is out of scope |
| OUTPUT_DIRECTORY | `qa/qa-20260923-cda5ccb/` (this folder): `results.csv` ledger, `evidence/`, `harness/` |
| Browsers | Playwright 1.63.0: Chromium 1243 (Desktop Chrome + Pixel 7 emulation), WebKit 2359 (Desktop Safari). Firefox not installed (would need a download: `npx playwright install firefox`) → BLOCKED. Real Safari/Chrome/Edge/private mode/real phone: not automated → BLOCKED. |
| Tools | curl, python3 (urllib), Playwright, docker, psql (read-only inspection of the stack's Postgres), Temporal CLI inside the temporal container, Kafka CLI inside the broker |
| Rules | product code is not modified during the audit; test data is tagged `qa-20260923-cda5ccb`; only run-tagged resources are mutated; no shared volumes erased |
