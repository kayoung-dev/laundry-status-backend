# Backend image, built on the dev server by /opt/washon/deploy-dev.sh
# (docs/DEPLOY.md). Tests are not run here: they need Docker (Testcontainers)
# and run in CI / locally instead.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# Wrapper + build scripts first so the dependency download layer is cached
# until build.gradle.kts changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null

COPY src src
RUN ./gradlew --no-daemon bootJar \
    && cp "$(ls build/libs/*.jar | grep -v -- '-plain.jar')" /src/app.jar

FROM eclipse-temurin:17-jre
RUN groupadd --system washon && useradd --system --gid washon --home-dir /app washon
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER washon

# Timestamps are stored as UTC (Instant / DATETIME(3)); pin the JVM zone too.
ENV TZ=UTC
# Listens on PORT (default 8081); the dev server sets PORT=8080 for Caddy.
EXPOSE 8081
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
