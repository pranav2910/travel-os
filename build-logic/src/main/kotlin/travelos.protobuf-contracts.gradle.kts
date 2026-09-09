import com.google.protobuf.gradle.id

// A module that compiles .proto files into Java messages + gRPC stubs. Only contracts/ uses this;
// services depend on the generated artifact, never on .proto files directly.
plugins {
    id("travelos.java-library")
    id("com.google.protobuf")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    api(libs.findLibrary("grpc-protobuf").get())
    api(libs.findLibrary("grpc-stub").get())
    api(libs.findLibrary("protobuf-java").get())
    api(libs.findLibrary("protobuf-java-util").get())
    // gRPC-generated stubs carry @javax.annotation.Generated.
    compileOnly(libs.findLibrary("javax-annotation-api").get())
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.findVersion("protobuf").get().requiredVersion}"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.findVersion("grpc").get().requiredVersion}"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
            }
        }
    }
}

// Generated code is not held to the hand-written lint bar.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.remove("-Xlint:all")
}
