# syntax=docker/dockerfile:1.7
# One image recipe for every Spring Boot service and the worker.
#   ./gradlew :services:<name>:bootJar
#   docker build -f docker/java.Dockerfile --build-arg JAR=services/<name>/build/libs/<name>-0.1.0-SNAPSHOT.jar -t <image> .
# The jar is split into Boot's layers (dependencies / loader / snapshots / application) so a code
# change re-pushes megabytes, not the whole runtime. Runs as an unprivileged fixed uid.
ARG JRE=eclipse-temurin:21-jre

FROM ${JRE} AS extract
ARG JAR
WORKDIR /work
COPY ${JAR} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM ${JRE}
LABEL org.opencontainers.image.source="https://github.com/pranav2910/travel-os" \
      org.opencontainers.image.licenses="Proprietary"
RUN groupadd -r -g 10001 travelos && useradd -r -u 10001 -g travelos -d /app -s /usr/sbin/nologin travelos
WORKDIR /app
COPY --from=extract --chown=10001:10001 /work/extracted/dependencies/ ./
COPY --from=extract --chown=10001:10001 /work/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=10001:10001 /work/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=10001:10001 /work/extracted/application/ ./
USER 10001
# Container-aware heap; die loudly on OOM so the orchestrator restarts a healthy instance.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
