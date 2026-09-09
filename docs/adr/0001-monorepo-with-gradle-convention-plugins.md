# ADR-0001: Monorepo, Gradle 9 + Kotlin DSL, convention plugins, one version catalog

**Status:** accepted · **Date:** 2026-09-09

## Context
One or two engineers, ~10 deployable services, shared contracts that change often. Separate repos
would turn every contract change into a multi-PR dance; a single build would turn every deploy into a
monolith.

## Decision
- One repository. Each service is an independently deployable Gradle subproject with its own image.
- Gradle 9 with the Kotlin DSL. Build logic lives in `build-logic/` as precompiled convention plugins
  (`travelos.java-library`, `travelos.spring-boot-service`) so a new service is ~10 lines of build file.
- Every dependency version lives in `gradle/libs.versions.toml`. Spring Boot's BOM governs what it
  can; only unmanaged libraries are pinned explicitly.
- Java 21 toolchain, auto-provisioned by the foojay resolver when absent.
- google-java-format via Spotless; `check` fails on formatting drift so reviews never discuss style.
- Python subprojects (`intelligence/`) use `uv` and are wired into the same `make check`.

## Consequences
- Contract changes and their consumers land in one commit and one CI run.
- Gradle's configuration cache keeps incremental builds fast as the module count grows.
- Maven-only shops need a minute to read `build-logic/`; the convention plugins are deliberately small.
