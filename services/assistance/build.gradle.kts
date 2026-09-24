plugins {
    id("travelos.spring-boot-service")
}

description = "Assistance (Phase 6): case management for everything the platform cannot finish by itself. Exposures, unknown supplier outcomes, incomplete cancellations, recoveries waiting on a person, failed bookings, declined payments and traveler requests become cases with an owner, a status, a next action, an SLA and an escalation path. Cases are opened from the platform's events (exactly once per event), auto-resolved when the platform reports the fact settled, and worked by people over REST. Nothing here moves money or touches a supplier: the authoritative services stay authoritative."

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:events"))
    implementation(project(":libs:spring-web"))
    implementation(project(":libs:spring-outbox"))
    implementation(project(":libs:spring-kafka-security"))

    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.opentelemetry.api)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":libs:events")))
    testImplementation(testFixtures(project(":libs:spring-web")))
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.awaitility)
}
