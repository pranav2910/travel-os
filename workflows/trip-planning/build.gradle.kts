plugins {
    id("travelos.spring-boot-service")
}

description = "Trip-planning worker: the Temporal workflow that coordinates context, search, policy, optimization, approval and booking for one trip. Durable, resumable, explainable."

dependencies {
    implementation(project(":libs:spring-kafka-security"))
    implementation(project(":libs:common"))
    implementation(project(":libs:events"))
    implementation(project(":libs:spring-grpc-support"))
    implementation(project(":libs:workflow-contracts"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.temporal.sdk)
    // Workflow + activity spans, linked to the Kafka record that started the workflow, via
    // Temporal's OpenTracing interceptors bridged onto OpenTelemetry.
    implementation(libs.temporal.opentracing)
    implementation(libs.opentelemetry.opentracing.shim)
    implementation(libs.opentelemetry.api)

    testImplementation(libs.temporal.testing)
}
