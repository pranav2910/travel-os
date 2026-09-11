plugins {
    id("travelos.spring-boot-service")
}

description = "Trip-planning worker: the Temporal workflow that coordinates context, search, policy, optimization, approval and booking for one trip. Durable, resumable, explainable."

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:events"))
    implementation(project(":libs:spring-grpc-support"))
    implementation(project(":libs:workflow-contracts"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.temporal.sdk)

    testImplementation(libs.temporal.testing)
}
