plugins {
    id("travelos.spring-boot-service")
}

description = "Learning (Slice 5): verified outcomes and authorized traveler feedback become versioned, evaluated supplier-reliability profiles that adjust the optimizer's soft preferences within bounds. Off, shadow or active per tenant; every learned influence explainable, reproducible, bounded and reversible. Sandbox evidence is marked and never trains a live profile."

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
