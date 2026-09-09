import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

plugins {
    id("travelos.java-conventions")
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(platform(libs.findLibrary("spring-boot-bom").get()))

    // Every service is observable and validates its inputs. No exceptions.
    implementation(libs.findLibrary("spring-boot-starter-actuator").get())
    implementation(libs.findLibrary("spring-boot-starter-validation").get())
    implementation(libs.findLibrary("spring-boot-starter-opentelemetry").get())
    runtimeOnly(libs.findLibrary("micrometer-registry-prometheus").get())

    testImplementation(libs.findLibrary("spring-boot-starter-test").get())
}

springBoot {
    buildInfo()
}

tasks.named<BootBuildImage>("bootBuildImage") {
    imageName = "ghcr.io/travelos/${project.name}:${project.version}"
}
