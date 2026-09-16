plugins {
    id("travelos.spring-boot-service")
}

description = "Enterprise Context (Slice 4): the verified employee directory (HRIS), the tenant's enterprise connectors (calendar, CRM, HRIS, expense) with durable, page-by-page synchronization, and travel-demand detection: deterministic rules turn confirmed in-person commitments into reviewable or actionable demand candidates that a person converts into a governed trip. Sandbox connectors are SIMULATED and say so."

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:events"))
    implementation(project(":libs:spring-web"))
    implementation(project(":libs:spring-outbox"))
    implementation(project(":libs:spring-grpc-support"))
    implementation(project(":libs:spring-kafka-security"))

    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.protobuf.java.util)
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
