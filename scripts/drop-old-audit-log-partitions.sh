#!/usr/bin/env bash
# =====================================================================
# R-37 (lot-F-ops-docs) — Drop partitions audit_log > 7 ans (rétention)
# =====================================================================
# À exécuter mensuellement (1er du mois) pour supprimer les partitions
# de plus de 7 ans. Conformité Code Fiscal Haïtien (10 ans conservation
# pièces comptables — 7 ans en DB + 10 ans S3 cold storage pour backups).
#
# Cron recommandé :
#   0 2 1 * * /opt/joaccountant/scripts/drop-old-audit-log-partitions.sh >> /var/log/joaccountant/drop-partitions.log 2>&1
#
# SAFETY : dry-run par défaut. Pour exécuter réellement, passer --execute.
# =====================================================================

set -euo pipefail

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_DB="${PG_DB:-joaccountant}"
PG_USER="${PG_USER:-joaccountant}"
RETENTION_YEARS="${RETENTION_YEARS:-7}"
EXECUTE="${1:-}"

log() { echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }

CUTOFF_YEAR_MONTH=$(date -d "${RETENTION_YEARS} years ago" +%Y_%m)
log "Looking for audit_log partitions older than ${CUTOFF_YEAR_MONTH} (retention: ${RETENTION_YEARS} years)"

# Lister les partitions éligibles
PARTITIONS=$(psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -t -c "
    SELECT c.relname
    FROM pg_inherits i
    JOIN pg_class c ON c.oid = i.inhrelid
    JOIN pg_class p ON p.oid = i.inhparent
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE p.relname = 'audit_log'
      AND c.relname ~ '^audit_log_[0-9]{4}_[0-9]{2}$'
      AND c.relname < 'audit_log_${CUTOFF_YEAR_MONTH}'
    ORDER BY c.relname;
" 2>/dev/null || echo "")

if [ -z "$PARTITIONS" ]; then
    log "No partitions older than ${CUTOFF_YEAR_MONTH} found. Nothing to do."
    exit 0
fi

for P in $PARTITIONS; do
    if [ "$EXECUTE" = "--execute" ]; then
        log "DROPPING partition ${P}..."
        psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -c "DROP TABLE IF EXISTS ${P};"
    else
        log "DRY-RUN: would drop partition ${P} (use --execute to actually drop)"
    fi
done

if [ "$EXECUTE" != "--execute" ]; then
    log "Dry-run complete. Re-run with --execute to actually drop partitions."
    log "WARNING: dropped partitions cannot be recovered (unless PITR backup available)."
else
    log "All old partitions dropped."
fi
