plugins {
    id("travelos.java-library")
    `java-test-fixtures`
}

description = "The standard event envelope, its JSON codec, and the topic registry — plus the contract tests that keep contracts/events honest."

dependencies {
    api(project(":libs:common"))
    api(platform(libs.spring.boot.bom))
    api(libs.jackson.databind)

    // Test fixtures: EventSchemas lets any service assert "this JSON is a valid <eventType>" in its own tests.
    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(libs.json.schema.validator)
    testFixturesImplementation(libs.jackson.databind)
    testFixturesImplementation(libs.jackson.dataformat.yaml)

    testImplementation(libs.jackson.dataformat.yaml)
}

// The contract files ride along with the test fixtures so consumers validate against the same
// schemas, examples and topic registry — nothing is copied.
sourceSets {
    testFixtures {
        resources {
            srcDir(layout.settingsDirectory.dir("contracts/events"))
        }
    }
    test {
        resources {
            srcDir(layout.settingsDirectory.dir("platform/local/kafka"))
        }
    }
}
