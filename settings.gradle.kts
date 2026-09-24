pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions the JDK declared by the toolchain when it is not installed locally (CI, fresh laptops).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "travel-os"

// Contracts first: every service is built against these, never against each other's internals.
include(":contracts:protobuf")

// Shared libraries — small, dependency-light, no framework lock-in.
include(":libs:common")
include(":libs:events")
include(":libs:spring-web")
include(":libs:spring-outbox")
include(":libs:spring-kafka-security")
include(":libs:spring-grpc-support")
include(":libs:workflow-contracts")

// Services — Slice 1 only.
include(":services:travel-core")
include(":services:policy")
include(":services:supplier-gateway")
include(":services:order")
include(":services:audit")
// Slice 2: disruption detection, the recovery decision record, and recovery approvals.
include(":services:disruption")
// Slice 4: the verified employee directory, enterprise connectors and travel-demand detection.
include(":services:enterprise-context")
// Slice 5: learning from outcomes (outcome records, feedback, evaluated profiles).
include(":services:learning")
// Phase 6: assistance (case management for what a person must finish).
include(":services:assistance")

// Temporal workflows: durable coordination of the trip lifecycle.
include(":workflows:trip-planning")

// Python services are wired into the same Gradle check so there is one gate.
include(":intelligence:optimization")
include(":intelligence:llm-gateway")
