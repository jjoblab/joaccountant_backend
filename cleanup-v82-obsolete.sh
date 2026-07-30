#!/usr/bin/env bash
# ============================================================
# cleanup-v82-obsolete.sh — Supprime TOUS les fichiers v8.2 obsolètes
# ============================================================
# Ces fichiers v8.2 ont été créés dans une session précédente mais référencent
# des méthodes/classes qui n'existent pas dans AuthService et CompanyService.
# Ils sont REMPLACÉS par les nouveaux fichiers V9 dans demo-data/.
#
# Le script supprime :
#   1. Tous les fichiers du package app/.../wizard/ (DemoAuthService,
#      WizardOrchestrationService, DemoAuthController, WizardController, etc.)
#   2. Les seeders v8.2 (DemoUserSeeder, DemoInfrastructureSeeder)
#   3. Les doublons de DemoLoginController (s'ils existent ailleurs que dans demo-data)
#
# Puis vérifie qu'aucune référence aux classes/méthodes supprimées ne reste.
# ============================================================

set -euo pipefail

REPO_ROOT="${1:-.}"
cd "$REPO_ROOT"

echo "🧹 Suppression des fichiers v8.2 obsolètes..."
echo ""

# ─── 1. Supprimer tout le package wizard/ (v8.2 obsolète) ───
WIZARD_DIR="app/src/main/java/jo/accountant/app/wizard"
if [[ -d "$WIZARD_DIR" ]]; then
  echo "  📁 Package wizard/ trouvé — suppression de tous les fichiers :"
  find "$WIZARD_DIR" -name "*.java" -type f | while read -r f; do
    echo "    ✗ Supprimé : $f"
    rm -f "$f"
  done
  # Supprimer le dossier s'il est vide
  rmdir "$WIZARD_DIR" 2>/dev/null || true
else
  echo "  ℹ️  Package wizard/ introuvable (déjà supprimé ?)"
fi

# ─── 2. Supprimer les seeders v8.2 obsolètes s'ils existent ───
V82_SEEDERS=(
  "app/src/main/java/jo/accountant/app/dev/DemoUserSeeder.java"
  "app/src/main/java/jo/accountant/app/dev/DemoInfrastructureSeeder.java"
  "app/src/main/java/jo/accountant/app/DemoUserSeeder.java"
  "app/src/main/java/jo/accountant/app/DemoInfrastructureSeeder.java"
  "auth/src/main/java/jo/accountant/auth/DemoUserSeeder.java"
  "auth/src/main/java/jo/accountant/auth/DemoInfrastructureSeeder.java"
)

echo ""
echo "  📁 Seeders v8.2 obsolètes :"
for f in "${V82_SEEDERS[@]}"; do
  if [[ -f "$f" ]]; then
    echo "    ✗ Supprimé : $f"
    rm -f "$f"
  fi
done

# ─── 3. Supprimer les DemoLoginController v8.2 s'ils existent ailleurs que dans demo-data ───
# (mon DemoLoginController V9 est dans demo-data/.../controller/, on le garde)
echo ""
echo "  📁 Doublons DemoLoginController v8.2 :"
find app/ auth/ -name "DemoLoginController.java" -type f 2>/dev/null | while read -r f; do
  echo "    ✗ Supprimé : $f"
  rm -f "$f"
done

# ─── 4. Vérifier qu'aucune référence aux classes supprimées ne reste ───
echo ""
echo "🔍 Vérification des références restantes..."
refs=$(grep -rn \
  "DemoAuthService\|WizardOrchestrationService\|DemoAuthController\|WizardController\|DemoUserSeeder\|DemoInfrastructureSeeder\|buildCompaniesClaimPublic\|issueRefreshTokenPublic\|companyService\.toResponse" \
  --include="*.java" \
  app/ auth/ company/ 2>/dev/null || true)

if [[ -n "$refs" ]]; then
  echo "⚠️  Références restantes trouvées :"
  echo "$refs"
  echo ""
  echo "   Vous devez manuellement corriger ces fichiers (supprimer les imports et usages)."
  exit 1
else
  echo "✅ Aucune référence restante aux classes/méthodes supprimées."
fi

# ─── 5. Vérifier que le DemoLoginController V9 est bien présent ───
echo ""
echo "🔍 Vérification de la présence du DemoLoginController V9..."
if [[ -f "demo-data/src/main/java/jo/accountant/demo/controller/DemoLoginController.java" ]]; then
  echo "✅ DemoLoginController V9 présent dans demo-data/"
else
  echo "⚠️  DemoLoginController V9 MANQUANT — vérifiez que vous avez bien dézippé joaccountant_backend_v8.3_demo_complete.zip"
fi

# ─── 6. Vérifier que les 4 seeders V9 sont présents ───
echo ""
echo "🔍 Vérification des 4 seeders V9..."
for seeder in RetailCommerceSeeder ProfessionalServicesSeeder NgoHumanitarianSeeder FreeZoneIndustrySeeder; do
  f="demo-data/src/main/java/jo/accountant/demo/seeders/${seeder}.java"
  if [[ -f "$f" ]]; then
    echo "  ✅ $seeder présent"
  else
    echo "  ⚠️  $seeder MANQUANT"
  fi
done

echo ""
echo "📊 Nettoyage terminé."
echo ""
echo "📌 Prochaines étapes :"
echo "  1. git add -A && git commit -m 'cleanup: remove obsolete v8.2 wizard package + demo auth (replaced by V9 DemoLoginController)'"
echo "  2. git push"
echo "  3. Render re-déploie automatiquement"
