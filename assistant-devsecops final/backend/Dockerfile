# syntax=docker/dockerfile:1
# Role : construire l image executable du backend et preparer son environnement de lancement.
# ---------------------------------------------------------------------------
# Étape 1 : build de l'application (cache Maven réutilisé entre les builds)
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q clean package -DskipTests

# ---------------------------------------------------------------------------
# Étape 2 : image d'exécution
# Basée sur une image Maven + JDK (et non un simple JRE) car l'assistant peut
# lui-même déclencher "mvn test" et OWASP Dependency-Check sur le projet
# surveillé. Le binaire Trivy est embarqué pour les scans d'images.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=aquasec/trivy:0.56.2 /usr/local/bin/trivy /usr/local/bin/trivy

RUN groupadd --system assistant && useradd --system --gid assistant --create-home --home-dir /home/assistant assistant

WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar
# Copie du projet lui-même : cible par défaut des tests et scans ("dogfooding")
COPY --chown=assistant:assistant pom.xml /workspace/pom.xml
COPY --chown=assistant:assistant src /workspace/src
RUN mkdir -p /app/data /home/assistant/.m2 /home/assistant/.cache/trivy \
    && chown -R assistant:assistant /app /workspace /home/assistant

USER assistant
ENV HOME=/home/assistant \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    TESTS_PROJECT_PATH=/workspace \
    TESTS_REPORTS_PATH=/workspace/target/surefire-reports \
    TESTS_COVERAGE_PATH=/workspace/target/site/jacoco/jacoco.xml \
    SECURITY_PROJECT_PATH=/workspace \
    SECURITY_REPORT_PATH=/workspace/target/dependency-check-report.json

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fs http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
