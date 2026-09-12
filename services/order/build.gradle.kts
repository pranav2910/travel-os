plugins {
    id("travelos.spring-boot-service")
}

description = "Order: the transactional truth. Idempotent commands, a per-item booking saga against the Supplier Gateway with bounded retries and compensation, and evidence-bearing travel.order.* events."

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:events"))
    implementation(project(":libs:spring-web"))
    implementation(project(":libs:spring-outbox"))
    implementation(project(":libs:spring-kafka-security"))
    implementation(project(":libs:spring-grpc-support"))

    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
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
