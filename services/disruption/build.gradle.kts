plugins {
    id("travelos.spring-boot-service")
}

description = "Disruption: supplier notices become Disruptions tied to the trip and order they hit; the recovery workflow drives the state machine through this service, which keeps the immutable recovery decision record, the recovery approvals, and answers 'what happened to my trip and why'."

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:events"))
    implementation(project(":libs:spring-web"))
    implementation(project(":libs:spring-outbox"))
    implementation(project(":libs:spring-grpc-support"))
    implementation(project(":libs:spring-kafka-security"))
    implementation(project(":libs:workflow-contracts"))

    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.protobuf.java.util)
    implementation(libs.temporal.sdk)
    implementation(libs.temporal.opentracing)
    implementation(libs.opentelemetry.opentracing.shim)
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
