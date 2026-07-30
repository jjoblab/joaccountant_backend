# JOAccountant Backend — Docker image for Render deployment
FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

# Copy gradle wrapper and build files
COPY gradle/ ./gradle/
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY libs.versions.toml ./

# Copy source code
COPY core/ ./core/
COPY auth/ ./auth/
COPY company/ ./company/
COPY chart-of-accounts/ ./chart-of-accounts/
COPY accounting-engine/ ./accounting-engine/
COPY financial-statements/ ./financial-statements/
COPY third-parties/ ./third-parties/
COPY invoicing/ ./invoicing/
COPY purchasing/ ./purchasing/
COPY purchase-orders/ ./purchase-orders/
COPY expenses/ ./expenses/
COPY inventory/ ./inventory/
COPY time-billing/ ./time-billing/
COPY payroll/ ./payroll/
COPY tax/ ./tax/
COPY funds-grants/ ./funds-grants/
COPY fx-operations/ ./fx-operations/
COPY bank-reconciliation/ ./bank-reconciliation/
COPY fixed-assets/ ./fixed-assets/
COPY document-generation/ ./document-generation/
COPY document-numbering/ ./document-numbering/
COPY approval-workflow/ ./approval-workflow/
COPY audit-trail/ ./audit-trail/
COPY notifications/ ./notifications/
COPY analytics/ ./analytics/
COPY reporting/ ./reporting/
COPY employees/ ./employees/
COPY demo-data/ ./demo-data/
COPY app/ ./app/
COPY test-support/ ./test-support/

# Build the application
RUN chmod +x gradlew && ./gradlew :app:bootJar --no-daemon --console=plain

# Runtime
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
COPY --from=0 /app/app/build/libs/app-*.jar /app/app.jar

EXPOSE 8080

ENV JAVA_OPTS="-Xmx512m -Xms256m -Dfile.encoding=UTF-8"
ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=$SPRING_PROFILES_ACTIVE"]
