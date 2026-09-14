plugins {
    id("travelos.spring-boot-service")
}

description = "Supplier Gateway: the only door to airlines, hotels and GDSs. Adapters normalize vendor APIs into our offers; rate limits, circuit breakers and retries live here."

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:events"))
    implementation(project(":libs:spring-web"))
    implementation(project(":libs:spring-grpc-support"))
    implementation(project(":libs:spring-outbox"))
    implementation(project(":libs:spring-kafka-security"))

    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.ratelimiter)

    testImplementation(testFixtures(project(":libs:events")))
    testImplementation(testFixtures(project(":libs:spring-web")))
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
    testImplementation(libs.testcontainers.junit.jupiter)
}
