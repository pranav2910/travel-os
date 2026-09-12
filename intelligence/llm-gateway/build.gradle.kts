// Wires the Python service into the single Gradle gate. Needs `uv` on PATH (brew install uv / CI action).
import org.gradle.api.tasks.Exec

plugins {
    base
}

val protoDir = layout.settingsDirectory.dir("contracts/protobuf/src/main/proto")

val pySync by tasks.registering(Exec::class) {
    description = "Create/refresh the uv virtualenv (dev group included)."
    group = "python"
    workingDir = projectDir
    commandLine("uv", "sync", "--frozen")
    inputs.files("pyproject.toml", "uv.lock")
    outputs.dir(layout.projectDirectory.dir(".venv"))
}

val pyGenProto by tasks.registering(Exec::class) {
    description = "Generate protobuf/gRPC stubs from contracts/protobuf into src/travelos."
    group = "python"
    dependsOn(pySync)
    workingDir = projectDir
    commandLine("uv", "run", "--frozen", "python", "scripts/gen_proto.py")
    inputs.dir(protoDir)
    inputs.file("scripts/gen_proto.py")
    outputs.dir(layout.projectDirectory.dir("src/travelos"))
}

val pyLint by tasks.registering(Exec::class) {
    description = "ruff check."
    group = "python"
    dependsOn(pyGenProto)
    workingDir = projectDir
    commandLine("uv", "run", "--frozen", "ruff", "check", "src", "tests", "scripts")
    inputs.dir("src/travelos_llm_gateway")
    inputs.dir("tests")
    outputs.upToDateWhen { false }
}

val pyFormatCheck by tasks.registering(Exec::class) {
    description = "ruff format --check."
    group = "python"
    dependsOn(pySync)
    workingDir = projectDir
    commandLine("uv", "run", "--frozen", "ruff", "format", "--check", "src", "tests", "scripts")
    inputs.dir("src/travelos_llm_gateway")
    inputs.dir("tests")
    outputs.upToDateWhen { false }
}

val pyTest by tasks.registering(Exec::class) {
    description = "pytest."
    group = "python"
    dependsOn(pyGenProto)
    workingDir = projectDir
    commandLine("uv", "run", "--frozen", "pytest")
    inputs.dir("src")
    inputs.dir("tests")
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn(pyLint, pyFormatCheck, pyTest)
}
