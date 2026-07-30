#!/usr/bin/env bash
# ============================================================
# cleanup-v82-obsolete.sh — Supprime les fichiers v8.2 obsolètes
# ============================================================
# Ces fichiers v8.2 ont été créés dans une session précédente mais référencent
# des méthodes qui n'existent pas dans AuthService et CompanyService :
#   - app/src/main/java/jo/accountant/app/wizard/DemoAuthService.java
#     → appelle authService.buildCompaniesClaimPublic() et issueRefreshTokenPublic()
#       qui n'existent pas
#   - app/src/main/java/jo/accountant/app/wizard/WizardOrchestrationService.java
#     → appelle companyService.toResponse() qui n'existe pas
#
# Ces fichiers sont REMPLACÉS par les nouveaux fichiers V9 :
#   - demo-data/.../controller/DemoLoginController.java (login démo en un clic)
#   - demo-data/.../seeders/*.java (4 seeders complets avec données métier)
#
# Solution : supprimer les fichiers v8.2 obsolètes.
# ============================================================

set -euo pipefail

REPO_ROOT="${1:-.}"
cd "$REPO_ROOT"

echo "🧹 Suppression des fichiers v8.2 obsolètes..."

# Liste des fichiers à supprimer (s'ils existent)
FILES_TO_REMOVE=(
  "app/src/main/java/jo/accountant/app/wizard/DemoAuthService.java"
  "app/src/main/java/jo/accountant/app/wizard/WizardOrchestrationService.java"
  "app/src/main/java/jo/accountant/app/wizard/DemoUserSeeder.java"
  "app/src/main/java/jo/accountant/app/wizard/DemoInfrastructureSeeder.java"
)

# Aussi supprimer le vieux endpoint /auth/demo-login s'il existe (remplacé par /api/v1/demos/login/{code})
# Attention : ne pas supprimer AuthController.java lui-même !
DEMO_LOGIN_FILES=(
  "app/src/main/java/jo/accountant/app/wizard/DemoLoginController.java"
  "auth/src/main/java/jo/accountant/auth/controller/DemoLoginController.java"
)

removed=0
for f in "${FILES_TO_REMOVE[@]}" "${DEMO_LOGIN_FILES[@]}"; do
  if [[ -f "$f" ]]; then
    rm -f "$f"
    echo "  ✗ Supprimé : $f"
    removed=$((removed + 1))
  fi
done

# Vérifier s'il reste des références aux méthodes supprimées
echo ""
echo "🔍 Vérification des références restantes..."
refs=$(grep -rn "buildCompaniesClaimPublic\|issueRefreshTokenPublic\|companyService\.toResponse" \
  --include="*.java" app/ auth/ company/ 2>/dev/null || true)

if [[ -n "$refs" ]]; then
  echo "⚠️  Références restantes trouvées :"
  echo "$refs"
  echo ""
  echo "   Vous devez manuellement corriger ces fichiers."
else
  echo "✅ Aucune référence restante aux méthodes supprimées."
fi

echo ""
echo "📊 Résumé : $removed fichier(s) supprimé(s)."
echo ""
echo "📌 Prochaines étapes :"
echo "  1. git add -A && git commit -m 'cleanup: remove obsolete v8.2 demo auth files (replaced by V9 DemoLoginController)'"
echo "  2. git push"
echo "  3. Render re-déploie automatiquement"
