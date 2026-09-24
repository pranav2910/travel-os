# Performance (Phase 10)

Method: `perf/load.py` against the local Docker stack (`make images && make stack-up`; SIMULATED
suppliers and payments; an 8 GB Docker VM on a laptop; no network). Warm, sequential samples of the
calls people make most; the end-to-end time from submitting a trip to `BOOKED`; 10 users at once;
a burst of 25 creations. Every run is stored under `docs/program/performance/<label>.{json,md}`
with all samples. These are relative measurements for before/after on one machine, not capacity
claims for any deployment.

## Baseline (`before-236d2d2`)

| Scenario | p50 | p95 | max |
|---|---|---|---|
| login (Keycloak password grant) | 67 ms | 81 ms | 81 ms |
| list my trips | 5.4 ms | 21 ms | 21 ms |
| create trip (202) | 11 ms | 26 ms | 26 ms |
| trip detail | 3.7 ms | 7.7 ms | 7.7 ms |
| itinerary .ics | 2.2 ms | 9.3 ms | 9.3 ms |
| cases (admin) | 4.6 ms | 12 ms | 12 ms |
| notification inbox | 4.1 ms | 6.9 ms | 6.9 ms |
| spend report by month | 4.7 ms | 18 ms | 18 ms |
| **end-to-end to BOOKED** | **1.1 s** | **1.6 s** | **1.6 s** |

10 concurrent users: wall 2.2 s, create p95 75 ms, end-to-end p95 2.2 s, all BOOKED. Burst: 25/25
accepted in 0.13 s (187 req/s), create p95 93 ms.

## Finding

The REST layer is not the cost: every people-facing call is under 30 ms at p95 on this stack. The
end-to-end time is dominated by the event hops, and the samples cluster at 0.55 s / 1.06 s / 1.56 s:
half-second steps. The transactional outbox in every service relayed events on a 500 ms poll
(`travelos.outbox.poll-interval`), so `travel.trip.created` waited up to 500 ms in Travel Core's
outbox before the worker could start the workflow, and every other consumer (assistance cases and
notifications, the audit trail, budget settlement) inherited the same wait per hop. The workflow's
own gRPC calls are direct and take well under a second in total.

## Change

`libs/spring-outbox`: a committed append nudges the publisher (`JdbcOutbox` registers one
transaction synchronization per transaction; `OutboxPublisher.nudge()` runs a relay on a single
daemon thread, coalescing nudges that arrive while one runs). Nothing is sent inside the appending
transaction and nothing is sent on rollback; the poll stays as the safety net (a crash between
commit and relay is caught by the next poll). Verified by `OutboxNudgeIntegrationTest` (an event
leaves within milliseconds with the poll set to 30 s; a rolled-back append publishes nothing).

## After (`after-236d2d2-outbox-nudge`, same stack, same method, images rebuilt with the change)

| Scenario | before p50 | after p50 | before p95 | after p95 |
|---|---|---|---|---|
| login | 67 ms | 73 ms | 81 ms | 86 ms |
| list my trips | 5.4 ms | 4.9 ms | 21 ms | 11 ms |
| create trip | 11 ms | 11 ms | 26 ms | 31 ms |
| trip detail | 3.7 ms | 4.0 ms | 7.7 ms | 5.6 ms |
| itinerary .ics | 2.2 ms | 2.7 ms | 9.3 ms | 16 ms |
| cases | 4.6 ms | 6.7 ms | 12 ms | 15 ms |
| inbox | 4.1 ms | 5.2 ms | 6.9 ms | 7.7 ms |
| spend report | 4.7 ms | 5.3 ms | 18 ms | 7.7 ms |
| **end-to-end to BOOKED** | **1.1 s** | **0.6 s** | **1.6 s** | **1.6 s** |

10 concurrent users: wall 2.2 s → 2.2 s; create p95 75 → 56 ms; end-to-end p95 2.2 → 2.1 s; all
BOOKED. Burst: 187 → 273 req/s accepted, create p95 93 → 41 ms (run-to-run variance on a warm
laptop; not attributed to the change).

Reading: the median end-to-end time halved (1.06 s → 0.56 s): the `created` event now reaches the
worker milliseconds after the commit instead of at the next poll, and the same holds for every
consumer downstream (cases, notifications, audit, budget settlement). The p95 did not move: 5 of 20
samples still take about 1.6 s in both runs (3 of 20 before). That mode is not the outbox; it
recurs with the same 1 s step, which matches an activity retry interval or a scheduler tick inside
the workflow path (Temporal, the optimizer or the sandbox supplier) and needs a per-trip trace to
attribute. It is recorded here as an open finding, not fixed. The REST calls are flat at single-digit
to low-double-digit milliseconds; the small p95 differences between runs are noise at this sample
size.

## Not measured

Live suppliers, live payments, a real network, more than ten concurrent users, and a cluster with
more than one replica per service. The harness runs unchanged against kind or EKS
(`APP_URL`, `KC_URL`, `POLICY_URL`) when those exist.
