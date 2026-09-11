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

    testImplementation(libs.spring.boot.starter.test)
}
