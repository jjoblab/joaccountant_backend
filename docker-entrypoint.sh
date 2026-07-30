#!/bin/sh
# ============================================================
# docker-entrypoint.sh — Entry point du conteneur Render
# ============================================================
# Rôle :
#   1. Convertir DATABASE_URL (format Render : postgres://user:pass@host:port/db)
#      en format JDBC (jdbc:postgresql://host:port/db) + extraire credentials
#   2. Définir SPRING_DATASOURCE_URL / USERNAME / PASSWORD pour Spring Boot
#   3. Exec java avec les options JVM
#
# Sans cette conversion, HikariCP échoue au démarrage car il attend une URL
# au format JDBC (jdbc:postgresql://) alors que Render fournit postgres://.
# ============================================================
set -e

echo "🔧 [entrypoint] Configuration de la datasource..."

# ─── Conversion DATABASE_URL (postgres://) → JDBC URL (jdbc:postgresql://) ───
if [ -n "$DATABASE_URL" ]; then
  echo "🔧 [entrypoint] DATABASE_URL détecté (format Render), conversion en JDBC URL..."

  # Vérifier le format attendu : postgres://user:pass@host:port/db
  # (Render utilise ce format depuis 2022)
  if echo "$DATABASE_URL" | grep -qE '^postgres(ql)?://[^:]+:[^@]+@[^/]+/'; then
    # Extraction user/password (entre '://' et '@')
    DB_USER=$(echo "$DATABASE_URL" | sed -E 's|^postgres(ql)?://([^:]+):([^@]+)@.*|\2|')
    DB_PASS=$(echo "$DATABASE_URL" | sed -E 's|^postgres(ql)?://([^:]+):([^@]+)@.*|\3|')
    # Conversion URL : remplacer 'postgres://' ou 'postgresql://' par 'jdbc:postgresql://'
    # et retirer les credentials de l'URL (ils seront passés via username/password)
    JDBC_URL=$(echo "$DATABASE_URL" | sed -E 's|^postgres(ql)?://([^:]+):([^@]+)@|jdbc:postgresql://|')

    export SPRING_DATASOURCE_URL="$JDBC_URL"
    export SPRING_DATASOURCE_USERNAME="$DB_USER"
    export SPRING_DATASOURCE_PASSWORD="$DB_PASS"

    echo "✅ [entrypoint] JDBC URL: $(echo "$JDBC_URL" | sed -E 's|://([^:]+):[0-9]+|://\1:****|')"
    echo "✅ [entrypoint] Username: $DB_USER"
  else
    echo "⚠️  [entrypoint] DATABASE_URL n'est pas au format postgres://user:pass@host:port/db"
    echo "    Format reçu: $(echo "$DATABASE_URL" | sed -E 's|://([^:]+):([^@]+)@|://\1:****@|')"
    echo "    Si déjà en JDBC, on l'utilise tel quel."
    export SPRING_DATASOURCE_URL="$DATABASE_URL"
  fi
else
  echo "❌ [entrypoint] DATABASE_URL non défini — l'app ne pourra pas se connecter à PostgreSQL"
fi

# ─── Validation des secrets obligatoires ───
if [ -z "$JWT_SECRET" ]; then
  echo "❌ [entrypoint] JWT_SECRET non défini — définissez-le dans le dashboard Render (onglet Environment)"
  exit 1
fi

if [ -z "$ENCRYPTION_KEY" ]; then
  echo "❌ [entrypoint] ENCRYPTION_KEY non défini — définissez-le dans le dashboard Render (onglet Environment)"
  exit 1
fi

echo "🔧 [entrypoint] JWT_SECRET et ENCRYPTION_KEY présents ✅"
echo "🔧 [entrypoint] SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-non défini}"
echo "🔧 [entrypoint] PORT=${PORT:-non défini (Render doit l'injecter)}"
echo "🔧 [entrypoint] Démarrage de l'application Java..."

# ─── Exec Java ───
# exec remplace le shell courant par java → signaux SIGTERM correctement propagés
exec java $JAVA_OPTS -jar /app/app.jar
