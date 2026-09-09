import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion.toInt())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = libs.findVersion("java").get().requiredVersion.toInt()
    // -parameters: Spring/Jackson bind constructor parameter names without extra annotations.
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
    // Surface the summary line so a red build is never mistaken for a green one in CI logs.
    afterSuite(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
        if (desc.parent == null) {
            println("Tests: ${result.testCount} run, ${result.successfulTestCount} passed, " +
                "${result.failedTestCount} failed, ${result.skippedTestCount} skipped -> ${result.resultType}")
        }
    }))
}

dependencies {
    implementation(libs.findLibrary("jspecify").get())

    testImplementation(platform(libs.findLibrary("spring-boot-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("assertj-core").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

spotless {
    java {
        // Only hand-written sources; generated protobuf/gRPC code lives under build/ and is excluded.
        target("src/**/*.java")
        googleJavaFormat(libs.findVersion("google-java-format").get().requiredVersion)
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
