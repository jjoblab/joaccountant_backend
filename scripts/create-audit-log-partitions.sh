#!/usr/bin/env bash
# =====================================================================
# R-37 (lot-F-ops-docs) — Pré-création des partitions mensuelles audit_log
# =====================================================================
# À exécuter tous les 25 du mois via cron pour pré-créer la partition du
# mois suivant + 2 mois supplémentaires (marge de sécurité).
#
# Cron recommandé :
#   25 23 * * * /opt/joaccountant/scripts/create-audit-log-partitions.sh >> /var/log/joaccountant/create-partitions.log 2>&1
#
# Prérequis : la fonction create_audit_log_partition() doit exister (migration V62).
# =====================================================================

set -euo pipefail

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_DB="${PG_DB:-joaccountant}"
PG_USER="${PG_USER:-joaccountant}"

log() { echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }

# Calculer le mois prochain + 2 mois (format YYYY_MM)
for OFFSET in 1 2 3; do
    MONTH=$(date -d "+${OFFSET} month" +%Y_%m)
    log "Creating partition audit_log_${MONTH}..."
    if PGPASSWORD="${PG_PASSWORD:-}" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" \
        -c "SELECT create_audit_log_partition('${MONTH}');" 2>&1; then
        log "OK: partition audit_log_${MONTH} created (or already existed)"
    else
        log "ERROR: failed to create partition audit_log_${MONTH}"
        exit 1
    fi
done

log "All future partitions created successfully."
