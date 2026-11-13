# Backend image ([E0-2]). Build context: backend/
#   docker build -f infra/docker/backend.Dockerfile -t hms-backend:<git-sha> backend
# Tests are not run here; CI runs them in the `backend` job.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version
COPY src/main src/main
RUN ./gradlew --no-daemon bootJar \
 && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} app.jar \;

FROM eclipse-temurin:21-jre
# Pick up OS security fixes newer than the base image; the image scan in CI fails on fixable HIGH/CRITICAL.
RUN apt-get update && apt-get upgrade -y --no-install-recommends && rm -rf /var/lib/apt/lists/*
RUN groupadd --system hms && useradd --system --gid hms --no-create-home hms
# Mount point for the first-run bootstrap password file (named volume in compose), writable only by hms.
RUN mkdir -p /var/lib/hms/bootstrap && chown hms:hms /var/lib/hms/bootstrap && chmod 700 /var/lib/hms/bootstrap
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER hms
EXPOSE 8080
# curl ships with the base image; readiness = DB reachable + migrations applied.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
