plugins {
    id("travelos.java-library")
}

description = "Hosts grpc-java inside a Spring Boot service: every BindableService bean is served on one port, with health, MDC and exception mapping. Deliberately small; no framework between us and grpc-java."

dependencies {
    api(project(":libs:common"))
    api(project(":contracts:protobuf"))
    api(platform(libs.spring.boot.bom))
    api(libs.grpc.api)
    api(libs.grpc.netty.shaded)
    api(libs.grpc.services)
    implementation(libs.grpc.inprocess)
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-context")
    implementation("org.slf4j:slf4j-api")
    // Tracing: Micrometer's gRPC observation interceptors propagate W3C traceparent over metadata;
    // the OTel API lets a handler tag the current span with tenant/trip/principal.
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.observation)
    implementation(libs.opentelemetry.api)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.micrometer.tracing.bridge.otel)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
}
