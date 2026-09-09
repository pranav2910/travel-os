# ADR-0006: One Postgres database and one login role per service

**Status:** accepted · **Date:** 2026-09-09

## Context
"No service writes another service's database" is easy to state and quietly violated the first time a
deadline meets a convenient JOIN.

## Decision
- Each service gets its own database and its own login role that owns it; `CONNECT` is revoked from
  `PUBLIC`. Locally this is `platform/local/postgres/init/01-databases.sql`; in AWS it is one Aurora
  cluster with the same per-database roles, credentials in Secrets Manager scoped per service.
- Schema is owned by the service's Flyway migrations. No shared migration repository.
- Cross-service reads go through the owning service's API or through Kafka-fed read models.

## Consequences
- The rule cannot be broken by accident; breaking it requires a credential change that shows up in review.
- Reporting joins across services are done in the Audit/analytics read models, not in production DBs.
