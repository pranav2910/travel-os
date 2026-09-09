plugins {
    id("travelos.java-library")
}

description = "Framework-free building blocks shared by every service: ids, money, tenant, idempotency, principals."

dependencies {
    api(libs.ulid.creator)
}
