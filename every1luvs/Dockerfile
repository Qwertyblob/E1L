# Build context is the REPO ROOT (see docker-compose.yml: context: .), because the Maven
# build copies the shared catalog from ../client/src/services.json (pom.xml copy-shared-catalog).
# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first for better layer caching (only re-runs when pom.xml changes).
COPY every1luvs/pom.xml every1luvs/pom.xml
RUN mvn -B -f every1luvs/pom.xml dependency:go-offline

# Shared catalog must sit at ../client/src/services.json relative to the pom (= /build/every1luvs).
COPY client/src/services.json client/src/services.json
COPY every1luvs/src every1luvs/src

# Tests are DB/integration-heavy and run in CI/locally, not in the image build.
RUN mvn -B -f every1luvs/pom.xml clean package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
# curl is only used by the compose healthcheck (GET /actuator/health).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# Container-aware heap sizing; tune if the Hetzner box RAM changes.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
COPY --from=build /build/every1luvs/target/every1luvs-*.jar /app/app.jar

# Run as a non-root user.
RUN useradd -r -u 1001 spring && chown -R spring:spring /app
USER spring

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
