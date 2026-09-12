plugins {
    id("travelos.java-library")
}

description = "Kafka client security from one switch: KAFKA_AUTH=none (kind, laptops) or msk-iam (SASL_SSL + AWS IAM on MSK). The same image talks to both."

dependencies {
    api(platform(libs.spring.boot.bom))
    implementation("org.springframework.boot:spring-boot")
    implementation("org.apache.kafka:kafka-clients")
    // SASL/IAM login module + callback handler for Amazon MSK; credentials come from the pod's IRSA role.
    implementation(libs.aws.msk.iam.auth)

    testImplementation(libs.spring.boot.starter.test)
}
