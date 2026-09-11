plugins {
    id("travelos.java-library")
    `java-test-fixtures`
}

description = "What every HTTP-facing service needs and must not reinvent: JWT -> tenant-scoped principal, Idempotency-Key handling, RFC 9457 errors, MDC context."

dependencies {
    api(project(":libs:common"))
    api(platform(libs.spring.boot.bom))
    api(libs.spring.boot.starter.webmvc)
    api(libs.spring.boot.starter.security)
    api(libs.spring.boot.starter.oauth2.resource.server)
    api(libs.spring.boot.starter.validation)
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    // Test fixtures: signed test JWTs + a matching JwtDecoder for any service's integration tests.
    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(libs.spring.boot.starter.test)
    testFixturesApi(libs.spring.boot.starter.oauth2.resource.server)

    testImplementation(libs.spring.boot.starter.test)
}
