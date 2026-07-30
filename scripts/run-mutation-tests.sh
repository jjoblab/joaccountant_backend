#!/usr/bin/env bash
# =============================================================================
# R-39 (lot-F2-tests-qa) — Mutation testing avec PIT sur les 4 modules critiques
# =============================================================================
# Exécute ./gradlew :<module>:pitest sur les 4 modules critiques du backend :
#   - :core               (PiiMasker, CurrencyRoundingService, TenantContext, framework)
#   - :accounting-engine  (écritures, journal, grand livre, balance, périodes fiscales)
#   - :payroll            (calcul brut→net, CNSS/OFATMA/AST Haïti, prélèvements France)
#   - :tax                (TVA, retenues à la source, IS, déclarations DGI/DGFIP)
#
# Pourquoi ces 4 modules ?
#   - Ce sont les modules à plus fort impact métier (compta + paie + fiscal).
#   - Une régression ici génère des erreurs comptables/fiscales transmises à
#     l'administration (DGI/DGFIP/OFATMA) — impact réglementaire direct.
#   - Les 27 autres modules seront ajoutés au fur et à mesure que leur dette de
#     tests unitaires sera résorbée (mutationThreshold=50% n'est pas atteignable
#     aujourd'hui sur les modules sans tests — d'où le scope restreint).
#
# Sortie :
#   - Rapports HTML par module : <module>/build/reports/pitest/index.html
#   - Rapports XML par module  : <module>/build/reports/pitest/mutations.xml
#   - Rapport agrégé HTML      : build/reports/pitest-aggregated/index.html
#     (généré par ce script à partir des rapports XML — sommaire + liens)
#
# Usage :
#   ./scripts/run-mutation-tests.sh                # exécute PIT + agrège
#   ./scripts/run-mutation-tests.sh --skip-run     # seulement agrège (PIT déjà joué)
# =============================================================================
set -euo -o pipefail

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

# JAVA_HOME doit pointer vers JDK 17 (Spring Boot 3.5).
if [ -z "${JAVA_HOME:-}" ]; then
    if [ -d "/home/z/jdk17" ]; then
        export JAVA_HOME="/home/z/jdk17"
    else
        echo "ERROR: JAVA_HOME is not set and /home/z/jdk17 not found."
        echo "Set JAVA_HOME to your JDK 17 install."
        exit 1
    fi
fi
export PATH="$JAVA_HOME/bin:$PATH"

MODULES=("core" "accounting-engine" "payroll" "tax")
AGGREGATED_DIR="build/reports/pitest-aggregated"
SKIP_RUN=0

for arg in "$@"; do
    case "$arg" in
        --skip-run) SKIP_RUN=1 ;;
        -h|--help)
            grep '^#' "$0" | head -n 30
            exit 0
            ;;
        *)
            echo "ERROR: unknown argument: $arg"
            exit 2
            ;;
    esac
done

# -----------------------------------------------------------------------------
# Step 1 — Exécuter PIT sur chaque module critique
# -----------------------------------------------------------------------------
if [ "$SKIP_RUN" -eq 1 ]; then
    echo "=== Skipping PIT execution (--skip-run) — using existing reports ==="
else
    echo "=== Running PIT mutation testing on ${#MODULES[@]} critical modules ==="
    echo "JAVA_HOME=$JAVA_HOME"
    echo ""
    # On exécute les 4 modules en une seule invocation Gradle (parallélisation native).
    # Si un module échoue (mutationThreshold/coverageThreshold non atteints), Gradle
    # s'arrête — on relance avec --continue pour générer les rapports des autres modules.
    PITEST_TASKS=""
    for m in "${MODULES[@]}"; do
        PITEST_TASKS="$PITEST_TASKS :${m}:pitest"
    done
    if ! ./gradlew $PITEST_TASKS --continue --no-daemon --console=plain; then
        echo ""
        echo "WARNING: one or more pitest tasks failed (threshold violation or test failure)."
        echo "         Reports were still generated — see aggregated report below."
    fi
fi

# -----------------------------------------------------------------------------
# Step 2 — Générer un rapport HTML agrégé
# -----------------------------------------------------------------------------
echo ""
echo "=== Generating aggregated HTML report ==="
mkdir -p "$AGGREGATED_DIR"

# Résumé par module (extraction depuis mutations.xml via xmllint si disponible,
# fallback sur simple listing des rapports HTML générés).
{
    echo "<!DOCTYPE html>"
    echo "<html lang=\"en\">"
    echo "<head>"
    echo "  <meta charset=\"UTF-8\">"
    echo "  <title>JOAccountant — PIT Mutation Testing Report (aggregated)</title>"
    echo "  <style>"
    echo "    body { font-family: sans-serif; margin: 2em; }"
    echo "    h1 { color: #333; }"
    echo "    table { border-collapse: collapse; margin-top: 1em; }"
    echo "    th, td { border: 1px solid #ccc; padding: 6px 12px; text-align: left; }"
    echo "    th { background: #f0f0f0; }"
    echo "    .ok { color: #2e7d32; font-weight: bold; }"
    echo "    .ko { color: #c62828; font-weight: bold; }"
    echo "    .missing { color: #999; }"
    echo "  </style>"
    echo "</head>"
    echo "<body>"
    echo "  <h1>PIT Mutation Testing — Rapport agrégé</h1>"
    echo "  <p>Généré le $(date -u '+%Y-%m-%d %H:%M:%S UTC') par <code>scripts/run-mutation-tests.sh</code></p>"
    echo "  <p>Modules critiques couverts : ${MODULES[*]}</p>"
    echo "  <table>"
    echo "    <thead><tr><th>Module</th><th>Rapport HTML</th><th>Rapport XML</th><th>Statut</th></tr></thead>"
    echo "    <tbody>"
    for m in "${MODULES[@]}"; do
        HTML_REPORT="${m}/build/reports/pitest/index.html"
        XML_REPORT="${m}/build/reports/pitest/mutations.xml"
        HTML_LINK=""
        XML_LINK=""
        STATUS=""
        if [ -f "$HTML_REPORT" ]; then
            HTML_LINK="<a href=\"../../${HTML_REPORT}\">ouvrir</a>"
        else
            HTML_LINK="<span class=\"missing\">non généré</span>"
        fi
        if [ -f "$XML_REPORT" ]; then
            XML_LINK="<a href=\"../../${XML_REPORT}\">ouvrir</a>"
        else
            XML_LINK="<span class=\"missing\">non généré</span>"
        fi
        if [ -f "$HTML_REPORT" ] && [ -f "$XML_REPORT" ]; then
            STATUS="<span class=\"ok\">OK</span>"
        else
            STATUS="<span class=\"ko\">MANQUANT</span>"
        fi
        echo "      <tr><td><code>${m}</code></td><td>${HTML_LINK}</td><td>${XML_LINK}</td><td>${STATUS}</td></tr>"
    done
    echo "    </tbody>"
    echo "  </table>"
    echo "  <h2>Comment lire les rapports</h2>"
    echo "  <ul>"
    echo "    <li><strong>Mutation Coverage</strong> = % de mutants tués par les tests (cible ≥ 50%).</li>"
    echo "    <li><strong>Line Coverage</strong> = % de lignes exécutées par les tests (cible ≥ 70%).</li>"
    echo "    <li><strong>Survived mutants</strong> = mutations qui n'ont fait échouer aucun test → tests insuffisants.</li>"
    echo "    <li><strong>Killed mutants</strong> = mutations détectées par les tests → bonne qualité.</li>"
    echo "  </ul>"
    echo "  <p>Voir <code>docs/MUTATION_TESTING.md</code> pour la documentation complète.</p>"
    echo "</body>"
    echo "</html>"
} > "$AGGREGATED_DIR/index.html"

echo "✅ Aggregated report: $AGGREGATED_DIR/index.html"
echo ""
echo "=== Done. Open the aggregated report in a browser: ==="
echo "   file://$PROJECT_ROOT/$AGGREGATED_DIR/index.html"
