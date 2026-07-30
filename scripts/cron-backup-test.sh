#!/usr/bin/env bash
# =============================================================================
# JOAccountant Backend — Cron wrapper pour test de restauration backup
# Finding #6 (audit batch B) — scheduler du test de restauration mensuel
#
# Ce script est conçu pour être appelé par cron TOUS les samedis à 04:00 UTC.
# Il ne déclenche effectivement le test de restauration que le **1er samedi du mois**
# (les autres samedis, il exit silently). Cette approche évite de dépendre d'une
# librairie cron "nth weekday of month" (non supportée par crontab standard POSIX).
#
# Cron entry (à installer sur le serveur de backup, en crontab root) :
#   0 4 * * 6 /opt/joaccountant/scripts/cron-backup-test.sh >> /var/log/joaccountant/cron-backup-test.log 2>&1
#
# Pourquoi un wrapper séparé plutôt que d'appeler test-restore.sh directement depuis
# cron avec une syntaxe "1er samedi" ?
#   1. La syntaxe cron POSIX ne supporte pas nativement "nth weekday of month" —
#      il faut contourner avec `0 4 1-7 * 6` + un check date dans le script, sinon
#      le job tourne TOUS les samedis entre le 1er et le 7 (3 à 4 samedis selon
#      le mois).
#   2. Ce wrapper centralise aussi l'envoi du rapport Slack (succès OU échec) et
#      le nettoyage des variables d'environnement (STAGING_PG_PASSWORD, SLACK_WEBHOOK_URL,
#      etc.) — test-restore.sh reste générique et réutilisable manuellement.
#   3. Permet de tester le déclenchement hors-cron : `FORCE_RUN=1 ./cron-backup-test.sh`
#      bypass le check date pour debug.
#
# Variables d'environnement (à positionner dans /etc/joaccountant/backup.env ou
# dans le systemd EnvironmentFile) :
#   - STAGING_PG_HOST, STAGING_PG_PORT, STAGING_PG_USER, STAGING_PG_PASSWORD
#   - BACKUP_ROOT
#   - SLACK_WEBHOOK_URL (OBLIGATOIRE si on veut recevoir le rapport Slack)
#   - FORCE_RUN=1 (optionnel — bypass le check "1er samedi du mois" pour debug)
#
# Sortie : 0 = succès (ou non-déclenché car pas le 1er samedi), non-0 = échec
# =============================================================================
set -euo pipefail

TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
LOG_PREFIX="[cron-backup-test]"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_RESTORE_SCRIPT="$SCRIPT_DIR/test-restore.sh"

log() { echo "$LOG_PREFIX [$TIMESTAMP] $*"; }
err() { echo "$LOG_PREFIX [$TIMESTAMP] ERROR: $*" >&2; }

# -----------------------------------------------------------------------------
# 1. Vérifier la date : exit si pas le 1er samedi du mois (sauf FORCE_RUN=1)
# -----------------------------------------------------------------------------
#
# Logique : un samedi est le "1er samedi du mois" ssi :
#   - day-of-week == Saturday (`date +%u` retourne 6 pour Saturday, 1 pour Monday)
#   - day-of-month <= 7 (les samedis du 1er au 7 sont toujours le 1er samedi)
#
# La syntaxe cron POSIX `0 4 1-7 * 6` ne fait pas un AND sur day-of-month ET
# day-of-week — elle fait un OR (le job tourne si l'un OU l'autre matche). D'où
# ce check explicite dans le script.
if [[ "${FORCE_RUN:-0}" != "1" ]]; then
    DOW=$(date -u +%u)         # Day of Week : 1=Monday ... 7=Sunday (ISO 8601)
    DOM=$(date -u +%d)         # Day of Month : 01-31
    DOM=$((10#$DOM))           # Strip leading zero (sinon bash interprète 08 comme octal)

    if [[ "$DOW" != "6" || "$DOM" -gt 7 ]]; then
        log "Pas le 1er samedi du mois (DOW=$DOW, DOM=$DOM). Exit sans action."
        exit 0
    fi
    log "1er samedi du mois détecté (DOW=$DOW, DOM=$DOM) — lancement du test."
else
    log "FORCE_RUN=1 — bypass du check date (mode debug)."
fi

# -----------------------------------------------------------------------------
# 2. Vérifier que test-restore.sh existe et est exécutable
# -----------------------------------------------------------------------------
if [[ ! -x "$TEST_RESTORE_SCRIPT" ]]; then
    err "Script test-restore.sh introuvable ou non exécutable : $TEST_RESTORE_SCRIPT"
    notify_slack_failure "Script test-restore.sh introuvable ou non exécutable ($TEST_RESTORE_SCRIPT) — configuration cron cassée."
    exit 2
fi

# Vérifier que SLACK_WEBHOOK_URL est positionné — sinon on alerte quand même
# (par stderr) mais on continue, car test-restore.sh utilise lui-même Slack en
# interne en cas d'échec.
if [[ -z "${SLACK_WEBHOOK_URL:-}" ]]; then
    err "AVERTISSEMENT : SLACK_WEBHOOK_URL non défini — aucun rapport Slack ne sera envoyé."
fi

# -----------------------------------------------------------------------------
# 3. Exécuter test-restore.sh et capturer le résultat
# -----------------------------------------------------------------------------
log "Exécution de $TEST_RESTORE_SCRIPT ..."
START_TIME=$(date +%s)

# Capture stdout + stderr dans un fichier temporaire pour inclusion dans Slack.
OUTPUT_FILE=$(mktemp -t cron-backup-test.XXXXXX.log)
trap 'rm -f "$OUTPUT_FILE"' EXIT

# Note : on ne met PAS set -e autour de ce bloc — on veut capturer le code de
# retour même en cas d'échec, pour notifier Slack.
set +e
bash "$TEST_RESTORE_SCRIPT" > "$OUTPUT_FILE" 2>&1
TEST_EXIT_CODE=$?
set -e

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

log "test-restore.sh terminé en ${DURATION}s avec exit code $TEST_EXIT_CODE"

# -----------------------------------------------------------------------------
# 4. Notifier Slack (succès OU échec)
# -----------------------------------------------------------------------------
# test-restore.sh envoie déjà ses propres alertes Slack en cas d'échec (par son
# helper alert_slack interne), mais on ajoute ici un rapport global pour avoir
# une vue d'ensemble (succès inclus — sinon on n'a aucune visibilité sur les
# tests qui passent, et on ne sait pas si le cron tourne réellement).
notify_slack() {
    local success="$1"   # "true" ou "false"
    local message="$2"

    if [[ -z "${SLACK_WEBHOOK_URL:-}" ]]; then
        return 0  # Pas de webhook configuré — notification skip.
    fi

    local color emoji title
    if [[ "$success" == "true" ]]; then
        color="good"
        emoji=":white_check_mark:"
        title="JOAccountant — Backup restore test SUCCEEDED"
    else
        color="danger"
        emoji=":rotating_light:"
        title="JOAccountant — Backup restore test FAILED"
    fi

    # Inclure les 30 dernières lignes du log pour contexte (les premières lignes
    # sont les logs d'extraction du backup, moins utiles que le smoke test final).
    local log_tail=""
    if [[ -s "$OUTPUT_FILE" ]]; then
        log_tail=$(tail -n 30 "$OUTPUT_FILE" | sed 's/\\/\\\\/g; s/"/\\"/g' | awk '{printf "%s\\n", $0}')
    fi

    local payload
    payload=$(jq -n \
        --arg title "$title" \
        --arg emoji "$emoji" \
        --arg msg "$message" \
        --arg color "$color" \
        --arg ts "$TIMESTAMP" \
        --arg dur "${DURATION}s" \
        --arg exit_code "$TEST_EXIT_CODE" \
        --arg log "$log_tail" \
        '{
            text: ($emoji + " " + $title),
            attachments: [
                {
                    color: $color,
                    fields: [
                        {title: "Durée", value: $dur, short: true},
                        {title: "Exit code", value: $exit_code, short: true},
                        {title: "Timestamp (UTC)", value: $ts, short: true},
                        {title: "Host", value: env.HOSTNAME // "unknown", short: true},
                        {title: "Détails", value: $msg, short: false}
                    ]
                },
                {
                    color: $color,
                    title: "Logs (30 dernières lignes)",
                    text: $log,
                    footer: "test-restore.sh"
                }
            ]
        }')

    # Envoi asynchrone — ne faille pas le script si Slack est down.
    curl -s -X POST -H 'Content-Type: application/json' \
        -d "$payload" "$SLACK_WEBHOOK_URL" >/dev/null 2>&1 || true
}

notify_slack_failure() {
    # Helper pour les échecs AVANT l'exécution de test-restore.sh (ex: script
    # introuvable). On n'a pas encore de log à joindre.
    local message="$1"
    if [[ -z "${SLACK_WEBHOOK_URL:-}" ]]; then
        return 0
    fi
    local payload
    payload=$(jq -n \
        --arg msg "$message" \
        --arg ts "$TIMESTAMP" \
        '{text: ":rotating_light: JOAccountant — cron-backup-test FAILED",
          attachments: [{color: "danger", text: $msg, footer: $ts}]}')
    curl -s -X POST -H 'Content-Type: application/json' \
        -d "$payload" "$SLACK_WEBHOOK_URL" >/dev/null 2>&1 || true
}

if [[ "$TEST_EXIT_CODE" -eq 0 ]]; then
    log "✅ Test de restauration RÉUSSI (duration=${DURATION}s). Notification Slack envoyée."
    notify_slack "true" "Le test de restauration mensuel a réussi. Le backup est valide et restaurable."
else
    err "❌ Test de restauration ÉCHOUÉ (exit=$TEST_EXIT_CODE, duration=${DURATION}s). Notification Slack envoyée."
    # test-restore.sh a déjà envoyé sa propre alerte Slack détaillée (avec cause
    # précise : SHA mismatch, 0 users, FK violée, etc.). On envoie une 2e notification
    # "wrapper" pour confirmer que le cron a tourné et signaler la durée.
    notify_slack "false" "Le test de restauration mensuel a échoué (exit code $TEST_EXIT_CODE). Voir l'alerte Slack détaillée envoyée par test-restore.sh pour la cause racine."
fi

exit "$TEST_EXIT_CODE"
