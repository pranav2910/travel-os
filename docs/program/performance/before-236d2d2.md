# Performance run `before-236d2d2`

2026-09-24T19:43:12.228693+00:00 on local docker compose, SIMULATED suppliers and payments, 8 GB Docker VM; 20 warm sequential samples per scenario.

| Scenario | n | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| login_ms | 20 | 67.1 ms | 81.4 ms | 81.4 ms | 81.4 ms |
| list_ms | 20 | 5.4 ms | 21.1 ms | 21.1 ms | 21.1 ms |
| create_ms | 20 | 11.2 ms | 25.6 ms | 25.6 ms | 25.6 ms |
| detail_ms | 20 | 3.7 ms | 7.7 ms | 7.7 ms | 7.7 ms |
| itinerary_ics_ms | 20 | 2.2 ms | 9.3 ms | 9.3 ms | 9.3 ms |
| cases_ms | 20 | 4.6 ms | 12.3 ms | 12.3 ms | 12.3 ms |
| inbox_ms | 20 | 4.1 ms | 6.9 ms | 6.9 ms | 6.9 ms |
| report_spend_ms | 20 | 4.7 ms | 17.7 ms | 17.7 ms | 17.7 ms |
| e2e_book_s | 20 | 1.1 s | 1.6 s | 1.6 s | 1.6 s |

End-to-end outcomes: {'BOOKED': 20}.

Concurrency: 10 users at once; wall 2.2 s; create p95 74.7 ms; e2e p95 2.2 s; finals {'BOOKED': 10}.

Burst: 25/25 accepted in 0.13 s (186.8 req/s); create p95 93.4 ms.
