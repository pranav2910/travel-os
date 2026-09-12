plugins {
    id("travelos.spring-boot-service")
}

description = "Audit: the append-only record of everything that happened, assembled per trip into the cross-service decision ledger. Consumes every travel.* topic; owns nothing transactional."

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:events"))
    implementation(project(":libs:spring-web"))

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
