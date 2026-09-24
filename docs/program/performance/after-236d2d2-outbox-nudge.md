# Performance run `after-236d2d2-outbox-nudge`

2026-09-24T19:47:25.311887+00:00 on local docker compose, SIMULATED suppliers and payments, 8 GB Docker VM; 20 warm sequential samples per scenario.

| Scenario | n | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| login_ms | 20 | 73.2 ms | 85.9 ms | 85.9 ms | 85.9 ms |
| list_ms | 20 | 4.9 ms | 10.9 ms | 10.9 ms | 10.9 ms |
| create_ms | 20 | 11.4 ms | 31.1 ms | 31.1 ms | 31.1 ms |
| detail_ms | 20 | 4.0 ms | 5.6 ms | 5.6 ms | 5.6 ms |
| itinerary_ics_ms | 20 | 2.7 ms | 15.5 ms | 15.5 ms | 15.5 ms |
| cases_ms | 20 | 6.7 ms | 15.3 ms | 15.3 ms | 15.3 ms |
| inbox_ms | 20 | 5.2 ms | 7.7 ms | 7.7 ms | 7.7 ms |
| report_spend_ms | 20 | 5.3 ms | 7.7 ms | 7.7 ms | 7.7 ms |
| e2e_book_s | 20 | 0.6 s | 1.6 s | 1.6 s | 1.6 s |

End-to-end outcomes: {'BOOKED': 20}.

Concurrency: 10 users at once; wall 2.2 s; create p95 55.6 ms; e2e p95 2.1 s; finals {'BOOKED': 10}.

Burst: 25/25 accepted in 0.09 s (273.2 req/s); create p95 41.3 ms.
