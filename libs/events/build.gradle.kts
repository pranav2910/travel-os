plugins {
    id("travelos.java-library")
}

description = "The standard event envelope, its JSON codec, and the topic registry — plus the contract tests that keep contracts/events honest."

dependencies {
    api(project(":libs:common"))
    api(platform(libs.spring.boot.bom))
    api(libs.jackson.databind)

    testImplementation(libs.jackson.dataformat.yaml)
    testImplementation(libs.json.schema.validator)
}

// Contract files are test resources: the schemas, the examples and the local topic script are all
// validated against the Java code so they cannot drift apart silently.
sourceSets {
    test {
        resources {
            srcDir(layout.settingsDirectory.dir("contracts/events"))
            srcDir(layout.settingsDirectory.dir("platform/local/kafka"))
        }
    }
}
