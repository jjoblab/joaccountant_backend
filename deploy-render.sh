#!/usr/bin/env bash
# ============================================================
# deploy-render.sh — Aide au déploiement Render pour JOAccountant v8.2
# ============================================================
# Ce script :
#   1. Vérifie que render.yaml et application-render.yml sont en place
#   2. Copie application-render.yml dans le backend (si pas déjà fait)
#   3. Affiche les commandes à exécuter pour déployer
#
# Pré-requis :
#   - Compte Render (https://render.com)
#   - Repo GitHub avec le code JOAccountant
#   - render.yaml à la racine du repo
#
# Étapes manuelles après exécution :
#   1. Push le repo sur GitHub
#   2. Render dashboard → New → Blueprint → sélectionner le repo
#   3. Render détecte render.yaml → crée PostgreSQL + Web Service
#   4. Dans l'onglet "Environment" du web service, saisir :
#        JWT_SECRET      = (générer avec : openssl rand -base64 48)
#        ENCRYPTION_KEY  = (générer avec : openssl rand -hex 16)
#   5. Render build & deploy automatiquement
#   6. Vérifier /actuator/health retourne 200
# ============================================================

set -euo pipefail

BACKEND_DIR="${1:-.}"
RENDER_YAML="$BACKEND_DIR/render.yaml"
APP_YAML="$BACKEND_DIR/app/src/main/resources/application-render.yml"

echo "🔍 Vérification des fichiers de déploiement Render..."

if [[ ! -f "$RENDER_YAML" ]]; then
  echo "❌ render.yaml manquant à : $RENDER_YAML"
  exit 1
fi

# Vérifier que render.yaml ne contient PAS postgresqlVersion (cause de l'erreur précédente)
if grep -q "postgresqlVersion" "$RENDER_YAML"; then
  echo "❌ render.yaml contient encore le champ 'postgresqlVersion' — Render le refuse."
  echo "   Supprimez la ligne 'postgresqlVersion: 16' du fichier."
  exit 1
fi
echo "✅ render.yaml OK (pas de champ postgresqlVersion invalide)"

# Copier application-render.yml si pas déjà dans le backend
if [[ ! -f "$APP_YAML" ]]; then
  echo "📋 Copie de application-render.yml dans le backend..."
  mkdir -p "$(dirname "$APP_YAML")"
  # Chercher le fichier source dans le même dossier que render.yaml
  if [[ -f "$(dirname "$RENDER_YAML")/application-render.yml" ]]; then
    cp "$(dirname "$RENDER_YAML")/application-render.yml" "$APP_YAML"
    echo "✅ application-render.yml copié vers : $APP_YAML"
  else
    echo "⚠️  application-render.yml introuvable à côté de render.yaml"
    echo "   Le profil 'render' ne sera pas activé correctement."
    echo "   Copiez application-render.yml dans app/src/main/resources/ manuellement."
  fi
else
  echo "✅ application-render.yml déjà présent dans le backend"
fi

echo ""
echo "🔐 Génération des secrets (à copier dans le dashboard Render) :"
echo "─────────────────────────────────────────────────────────────"
JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
ENCRYPTION_KEY=$(openssl rand -hex 16)
MFA_ENCRYPTION_KEY=$(openssl rand -base64 48 | tr -d '\n')
echo "JWT_SECRET              = $JWT_SECRET"
echo "ENCRYPTION_KEY          = $ENCRYPTION_KEY"
echo "APP_MFA_ENCRYPTION_KEY  = $MFA_ENCRYPTION_KEY"
echo "─────────────────────────────────────────────────────────────"
echo ""
echo "📌 Prochaines étapes :"
echo "  1. Commit + push le repo sur GitHub"
echo "  2. Dashboard Render → New → Blueprint → sélectionner le repo"
echo "  3. Dans 'Environment' du web service, coller les 3 secrets ci-dessus :"
echo "       JWT_SECRET, ENCRYPTION_KEY, APP_MFA_ENCRYPTION_KEY"
echo "  4. Render build automatiquement (~5-10 min première fois)"
echo "  5. Vérifier : https://<votre-service>.onrender.com/actuator/health"
echo "  6. Tester login démo : POST /auth/demo-login avec {\"email\": \"owner@boutik-lakay.demo\", \"password\": \"demo1234\"}"
