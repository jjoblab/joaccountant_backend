# ============================================================
# Dockerfile multi-stage pour JOAccountant Backend v8.2 — Render
# ============================================================
# Corrections v8.2 (par rapport à la version précédente) :
#   1. JVM : retiré `-XX:+UseStringDeduplication` (incompatible avec UseSerialGC)
#   2. Ajouté docker-entrypoint.sh pour convertir DATABASE_URL (postgres://)
#      vers JDBC URL (jdbc:postgresql://) avant de lancer Java
#   3. JAVA_OPTS passé en ARG pour permettre override au build
#
# Java 17 (aligné sur build.gradle.kts : JavaVersion.VERSION_17)
# Multi-stage : build JDK → runtime JRE (image finale ~300 MB)
# Tuning JVM pour Render free tier (512 MB RAM)
# ============================================================

# ─── Stage 1: Build ──────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# Cache Gradle wrapper + dépendances (ces fichiers changent rarement)
COPY gradle/ ./gradle/
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
# NOTE : PAS de `COPY libs.versions.toml ./` — le fichier est dans gradle/
# et est déjà copié ci-dessus. Gradle le trouve automatiquement à
# gradle/libs.versions.toml (convention standard).
RUN chmod +x gradlew

# Copie des build.gradle.kts des subprojects (pour cache Gradle)
COPY core/build.gradle.kts ./core/build.gradle.kts
COPY audit-trail/build.gradle.kts ./audit-trail/build.gradle.kts
COPY auth/build.gradle.kts ./auth/build.gradle.kts
COPY company/build.gradle.kts ./company/build.gradle.kts
# v2.5.2 fix — le module demo-data manquait du Dockerfile → DemoController
# n'était jamais inclus dans le fat JAR → mode démo cassé sur Render.
COPY demo-data/build.gradle.kts ./demo-data/build.gradle.kts
COPY document-numbering/build.gradle.kts ./document-numbering/build.gradle.kts
COPY chart-of-accounts/build.gradle.kts ./chart-of-accounts/build.gradle.kts
COPY approval-workflow/build.gradle.kts ./approval-workflow/build.gradle.kts
COPY analytics/build.gradle.kts ./analytics/build.gradle.kts
COPY accounting-engine/build.gradle.kts ./accounting-engine/build.gradle.kts
COPY financial-statements/build.gradle.kts ./financial-statements/build.gradle.kts
COPY third-parties/build.gradle.kts ./third-parties/build.gradle.kts
COPY fixed-assets/build.gradle.kts ./fixed-assets/build.gradle.kts
COPY inventory/build.gradle.kts ./inventory/build.gradle.kts
COPY time-billing/build.gradle.kts ./time-billing/build.gradle.kts
COPY document-generation/build.gradle.kts ./document-generation/build.gradle.kts
COPY invoicing/build.gradle.kts ./invoicing/build.gradle.kts
COPY bank-reconciliation/build.gradle.kts ./bank-reconciliation/build.gradle.kts
COPY funds-grants/build.gradle.kts ./funds-grants/build.gradle.kts
COPY notifications/build.gradle.kts ./notifications/build.gradle.kts
COPY tax/build.gradle.kts ./tax/build.gradle.kts
COPY reporting/build.gradle.kts ./reporting/build.gradle.kts
COPY purchasing/build.gradle.kts ./purchasing/build.gradle.kts
COPY expenses/build.gradle.kts ./expenses/build.gradle.kts
COPY employees/build.gradle.kts ./employees/build.gradle.kts
COPY payroll/build.gradle.kts ./payroll/build.gradle.kts
COPY fx-operations/build.gradle.kts ./fx-operations/build.gradle.kts
COPY test-support/build.gradle.kts ./test-support/build.gradle.kts
COPY app/build.gradle.kts ./app/build.gradle.kts

# Pré-téléchargement des dépendances (cache layer — accélère les rebuilds)
RUN ./gradlew --no-daemon dependencies || true

# Copie du source code
COPY core ./core
COPY audit-trail ./audit-trail
COPY auth ./auth
COPY company ./company
# v2.5.2 fix — source du module demo-data (manquait du Dockerfile).
COPY demo-data ./demo-data
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

# Build fat JAR (skip tests en Docker build — ils tournent en CI)
RUN ./gradlew :app:bootJar --no-daemon -x test

# ─── Stage 2: Runtime ────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

# Non-root user pour sécurité
RUN groupadd -r joaccountant && useradd -r -g joaccountant joaccountant

# Dossier de stockage (monter un volume persistant ici en prod)
RUN mkdir -p /var/lib/joaccountant/storage /tmp/uploads && \
    chown -R joaccountant:joaccountant /var/lib/joaccountant /tmp/uploads

WORKDIR /app
COPY --from=build /workspace/app/build/libs/*.jar /app/app.jar

# ─── Entry point : convertit DATABASE_URL (postgres://) → JDBC URL ───
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh && \
    chown joaccountant:joaccountant /app/docker-entrypoint.sh

USER joaccountant

EXPOSE 8080 10000

# JVM args optimisés pour Render free tier (512 MB RAM)
# -Xmx256m : heap max 256 MB (laisse ~256 MB pour metaspace + threads + off-heap)
# -XX:+UseSerialGC : GC single-thread plus économe mémoire que G1 (pertinent sur 1 vCPU)
# -XX:TieredStopAtLevel=1 : C1 only — JIT plus rapide, moins de mémoire compilateur
# NOTE : -XX:+UseStringDeduplication retiré (incompatible avec SerialGC — warning au démarrage)
ENV JAVA_OPTS="-Xmx256m -Xms64m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -Xss256k -XX:MaxDirectMemorySize=32m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump.hprof -Dfile.encoding=UTF-8"

# L'entry point convertit DATABASE_URL puis exec java
ENTRYPOINT ["/app/docker-entrypoint.sh"]
