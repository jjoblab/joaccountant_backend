# Dockerfile multi-stage pour JOAccountant Backend v4.7
# Audit v4.7 §9.4 — Quick win : artefact OCI reproductible
#
# Build:
#   docker build -t joaccountant:v4.7-fixed .
# Run:
#   docker run -p 8080:8080 -p 8081:8081 \
#     -e SPRING_PROFILES_ACTIVE=prod \
#     -e APP_JWT_SECRET=$(openssl rand -base64 32) \
#     -e DB_URL=jdbc:postgresql://db:5432/joaccountant \
#     -e DB_USERNAME=joaccountant \
#     -e DB_PASSWORD=change-me \
#     -e CORS_ALLOWED_ORIGINS=https://app.joaccountant.com \
#     -e APP_STORAGE_ROOT=/var/lib/joaccountant/storage \
#     joaccountant:v4.7-fixed

# ─── Stage 1: Build ──────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Cache Gradle wrapper + dependencies
COPY gradle ./gradle
COPY gradlew gradlew.bat ./
COPY gradle.properties ./
COPY settings.gradle.kts ./
COPY build.gradle.kts ./
COPY */build.gradle.kts ./
RUN chmod +x gradlew

# Copy source
COPY core ./core
COPY audit-trail ./audit-trail
COPY auth ./auth
COPY company ./company
COPY document-numbering ./document-numbering
COPY chart-of-accounts ./chart-of-accounts
COPY approval-workflow ./approval-workflow
COPY analytics ./analytics
COPY accounting-engine ./accounting-engine
COPY financial-statements ./financial-statements
COPY third-parties ./third-parties
COPY fixed-assets ./fixed-assets
COPY inventory ./inventory
COPY time-billing ./time-billing
COPY document-generation ./document-generation
COPY invoicing ./invoicing
COPY bank-reconciliation ./bank-reconciliation
COPY funds-grants ./funds-grants
COPY notifications ./notifications
COPY tax ./tax
COPY reporting ./reporting
COPY purchasing ./purchasing
COPY expenses ./expenses
COPY employees ./employees
COPY payroll ./payroll
COPY fx-operations ./fx-operations
COPY test-support ./test-support
COPY app ./app

# Build fat JAR (skip tests in Docker build — they run in CI)
RUN ./gradlew :app:bootJar --no-daemon -x test

# ─── Stage 2: Runtime ────────────────────────────────────────────────
FROM eclipse-temurin:21-jre

# Non-root user for security
RUN groupadd -r joaccountant && useradd -r -g joaccountant joaccountant

# Storage directory (mount a persistent volume here in prod)
RUN mkdir -p /var/lib/joaccountant/storage && chown -R joaccountant:joaccountant /var/lib/joaccountant

WORKDIR /app
COPY --from=build /workspace/app/build/libs/*.jar /app/app.jar

USER joaccountant

EXPOSE 8080 8081

# JVM args optimized for containers (audit v4.7 §5.3)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump.hprof -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
