plugins {
    id("travelos.java-library")
}

description = "Transactional outbox: events are written in the same transaction as the state change and relayed to Kafka by a poller. At-least-once; consumers dedupe on eventId."

dependencies {
    api(project(":libs:events"))
    api(platform(libs.spring.boot.bom))
    api(libs.spring.boot.starter.jdbc)
    api(libs.spring.boot.starter.kafka)
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("io.micrometer:micrometer-core")
    // The request's trace context is stored with the row and restored when the row is relayed, so
    // the async boundary does not cut the trace.
    implementation(libs.opentelemetry.api)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.awaitility)
    testImplementation(libs.spring.boot.starter.opentelemetry)
    testImplementation(libs.opentelemetry.sdk.testing)
    testRuntimeOnly(libs.postgresql)
}
