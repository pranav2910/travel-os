# Performance measurement (Phase 10)

`perf/load.py` measures the platform as a person and the workflow experience it, against the local
Docker stack (`make images && make stack-up`), with nothing but the Python standard library:

- warm, sequential latency of the calls a traveler and an administrator make most (login, trip
  list, trip create, trip detail, itinerary export, case list, notification inbox, spend report);
- the end-to-end time from submitting a trip to `BOOKED` (the whole machine: planning, policy,
  optimization, purchase authorization, the Order saga against the SIMULATED suppliers, the
  Kafka/outbox hops);
- the same under 10 concurrent users, and the throughput of a burst of 25 trip creations.

Every run writes `docs/program/performance/<label>.json` (all samples) and `<label>.md` (p50 /
p95 / p99 / max per scenario). Runs are compared by label (`before-<sha>` / `after-<sha>`), on the
same machine, the same stack, the same seed policy. Numbers are what this laptop's Docker VM
(8 GB, SIMULATED suppliers, no network) produced: a relative measure for before/after, not a
capacity claim for any deployment.

```bash
make images && make stack-up
python3 perf/load.py --label before-$(git rev-parse --short HEAD) --samples 20 --concurrency 10
```
