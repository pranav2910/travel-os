plugins {
    id("travelos.spring-boot-service")
}

description = "Policy: versioned, per-tenant travel policy documents and the deterministic engine that evaluates trips and agent actions against them. Every decision is evidence."

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

// The seed policy document used for local dev is also the fixture for the engine tests.
sourceSets {
    test {
        resources {
            srcDir(layout.settingsDirectory.dir("platform/local/seed"))
        }
    }
}
