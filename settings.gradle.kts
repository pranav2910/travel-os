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

// Services — Slice 1 only.
include(":services:travel-core")
