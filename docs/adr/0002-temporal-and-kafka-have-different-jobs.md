# ADR-0002: Temporal coordinates processes; Kafka broadcasts facts

**Status:** accepted · **Date:** 2026-09-09

## Context
A trip is a long-running, multi-step process (search → policy → optimize → approve → book → confirm)
with human waits measured in hours and supplier calls that fail. Many downstream systems (audit,
notification, expense, disruption monitoring) need to know what happened without being in the loop.

## Decision
- **Temporal** owns process state: "what step is trip X in, and what happens if the worker dies?"
  Workflows are deterministic; every side effect is an Activity with a retry policy and a timeout.
- **Kafka** owns facts: `travel.order.confirmed` is published once and consumed by whoever cares.
  Topics are declared in `contracts/events/topics.yaml`; brokers never auto-create.
- Kafka is not a workflow engine: no consumer drives the next step of a saga off a topic.
- Temporal is not an event bus: no workflow fans out notifications to N consumers itself.
- Kafka records are keyed by `correlationId` (trip id) so per-trip ordering holds; nothing else is promised.

## Consequences
- Booking sagas with compensation are readable code in one place, with a UI to inspect them.
- Adding a consumer (e.g. duty-of-care) is a new consumer group, not a workflow change.
- Two infrastructure components to run. Accepted: they fail differently and are recovered differently.
