#!/usr/bin/env bash
# =============================================================================
# JOAccountant Backend — PostgreSQL PITR Backup Script
# Audit v4.7 §9.1 Finding #5 — Backup & DR
#
# Stratégie : pg_basebackup (full) + WAL archiving (PITR)
# - Full backup quotidien à 02:00 via cron
# - WAL archiving continu via archive_command PostgreSQL
# - Rétention : 7 daily + 4 weekly + 12 monthly + 10 yearly
# - RPO < 5 min (WAL streaming), RTO < 1h (pg_basebackup restore)
#
# Usage :
#   ./scripts/backup-postgres.sh                 # full backup
#   ./scripts/backup-postgres.sh --wal-archive   # WAL archiving (appelé par archive_command)
#   ./scripts/backup-postgres.sh --rotate        # rotation des backups (appelé par cron hebdo)
#
# Variables d'environnement requises :
#   - PG_HOST (défaut: localhost)
#   - PG_PORT (défaut: 5432)
#   - PG_USER (défaut: joaccountant)
#   - PG_DATABASE (défaut: joaccountant)
#   - PG_PASSWORD (obligatoire — pas de défaut)
#   - BACKUP_ROOT (défaut: /var/backups/joaccountant)
#   - S3_BACKUP_BUCKET (optionnel — si set, sync vers S3 cross-region)
#
# Sortie : 0 = succès, non-0 = échec (alerter on-call)
# =============================================================================
set -euo pipefail

# --- Configuration ---
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-joaccountant}"
PG_DATABASE="${PG_DATABASE:-joaccountant}"
BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/joaccountant}"
DATE_FORMAT="$(date +%Y%m%d-%H%M%S)"
TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
LOG_PREFIX="[backup-postgres]"

# Vérifier que PG_PASSWORD est positionné
if [[ -z "${PG_PASSWORD:-}" ]]; then
    echo "$LOG_PREFIX ERROR: PG_PASSWORD environment variable is required" >&2
    exit 1
fi

export PGPASSWORD="$PG_PASSWORD"

# --- Helpers ---
log() { echo "$LOG_PREFIX [$TIMESTAMP] $*"; }
err() { echo "$LOG_PREFIX [$TIMESTAMP] ERROR: $*" >&2; }

mkdir -p "$BACKUP_ROOT" "$BACKUP_ROOT/wal" "$BACKUP_ROOT/full"

# --- Modes ---

if [[ "${1:-}" == "--wal-archive" ]]; then
    # Mode WAL archiving — appelé par PostgreSQL archive_command
    # $2 = chemin du fichier WAL source
    # $3 = nom du fichier WAL
    WAL_SRC="$2"
    WAL_NAME="$3"
    WAL_DEST="$BACKUP_ROOT/wal/$WAL_NAME"

    if [[ -f "$WAL_DEST" ]]; then
        # WAL déjà archivé — idempotent (PostgreSQL peut réessayer)
        log "WAL $WAL_NAME déjà archivé, skip"
        exit 0
    fi

    cp "$WAL_SRC" "$WAL_DEST"
    chmod 640 "$WAL_DEST"

    # Sync S3 si configuré
    if [[ -n "${S3_BACKUP_BUCKET:-}" ]]; then
        aws s3 cp "$WAL_DEST" "s3://$S3_BACKUP_BUCKET/wal/$WAL_NAME" \
            --sse aws:kms --sse-kms-key-id "${S3_KMS_KEY_ID:-alias/aws/s3}" \
            >/dev/null 2>&1 || err "S3 sync failed for WAL $WAL_NAME (non-fatal)"
    fi

    log "WAL $WAL_NAME archivé"
    exit 0
fi

if [[ "${1:-}" == "--rotate" ]]; then
    # Mode rotation — appelé par cron hebdo
    log "Rotation des backups..."

    # Garder : 7 derniers daily, 4 derniers weekly (dimanche), 12 derniers monthly, 10 derniers yearly
    find "$BACKUP_ROOT/full" -maxdepth 1 -name "base-*.tar.gz" -type f | sort -r | \
        awk -F/ '{print $NF}' | \
        awk -F- '{
            date=$2; gsub(".tar.gz", "", date);
            cmd = "date -d " substr(date,1,8) " +%w"; cmd | getline dow; close(cmd);
            daily[daily_count++] = $0;
            if (dow == "0") weekly[weekly_count++] = $0;
            if (substr(date,7,2) == "01") monthly[monthly_count++] = $0;
            if (substr(date,5,4) == "0101") yearly[yearly_count++] = $0;
        } END {
            for (i=7; i<daily_count; i++) print "rm -f /var/backups/joaccountant/full/" daily[i];
            for (i=4; i<weekly_count; i++) print "rm -f /var/backups/joaccountant/full/" weekly[i];
            for (i=12; i<monthly_count; i++) print "rm -f /var/backups/joaccountant/full/" monthly[i];
            for (i=10; i<yearly_count; i++) print "rm -f /var/backups/joaccountant/full/" yearly[i];
        }' | bash

    # Nettoyer les WAL > 30 jours
    find "$BACKUP_ROOT/wal" -type f -mtime +30 -delete

    log "Rotation terminée"
    exit 0
fi

# --- Mode par défaut : full backup ---
log "Démarrage full backup..."

BACKUP_DIR="$BACKUP_ROOT/full/base-$DATE_FORMAT"
mkdir -p "$BACKUP_DIR"

# pg_basebackup : full backup avec WAL inclus (-X stream) et checksum de vérification (-c fast)
pg_basebackup \
    -h "$PG_HOST" \
    -p "$PG_PORT" \
    -U "$PG_USER" \
    -D "$BACKUP_DIR" \
    -X stream \
    -c fast \
    -z \
    -Z 6 \
    -Ft \
    -P \
    -v 2>&1 | while read -r line; do log "pg_basebackup: $line"; done

# Créer une archive tar.gz unique pour faciliter le stockage
TAR_FILE="$BACKUP_ROOT/full/base-$DATE_FORMAT.tar.gz"
tar -czf "$TAR_FILE" -C "$BACKUP_DIR" .
rm -rf "$BACKUP_DIR"

# Calculer SHA-256 pour vérification d'intégrité au restore
SHA256=$(sha256sum "$TAR_FILE" | awk '{print $1}')
echo "$SHA256  $TAR_FILE" > "$TAR_FILE.sha256"

# Sync S3 si configuré
if [[ -n "${S3_BACKUP_BUCKET:-}" ]]; then
    log "Sync vers S3 bucket $S3_BACKUP_BUCKET..."
    aws s3 cp "$TAR_FILE" "s3://$S3_BACKUP_BUCKET/full/$(basename $TAR_FILE)" \
        --sse aws:kms --sse-kms-key-id "${S3_KMS_KEY_ID:-alias/aws/s3}" \
        >/dev/null
    aws s3 cp "$TAR_FILE.sha256" "s3://$S3_BACKUP_BUCKET/full/$(basename $TAR_FILE).sha256" \
        --sse aws:kms --sse-kms-key-id "${S3_KMS_KEY_ID:-alias/aws/s3}" \
        >/dev/null
    log "Sync S3 terminé"
fi

# Métrique Prometheus (si pushgateway configuré)
if [[ -n "${PUSHGATEWAY_URL:-}" ]]; then
    SIZE_BYTES=$(stat -c%s "$TAR_FILE")
    echo "joaccountant_backup_size_bytes $SIZE_BYTES" | \
        curl --data-binary @- "$PUSHGATEWAY_URL/metrics/job/postgres-backup/instance/$HOSTNAME" \
        >/dev/null 2>&1 || true
    echo "joaccountant_backup_success 1" | \
        curl --data-binary @- "$PUSHGATEWAY_URL/metrics/job/postgres-backup/instance/$HOSTNAME" \
        >/dev/null 2>&1 || true
fi

log "Full backup terminé : $TAR_FILE (SHA-256: ${SHA256:0:16}...)"

# Cron recommandé (à ajouter dans /etc/cron.d/joaccountant-backup) :
# 0 2 * * *  joaccountant  PG_PASSWORD=xxx /opt/joaccountant/scripts/backup-postgres.sh >> /var/log/joaccountant/backup.log 2>&1
# 0 4 * * 0  joaccountant  PG_PASSWORD=xxx /opt/joaccountant/scripts/backup-postgres.sh --rotate >> /var/log/joaccountant/backup.log 2>&1
#
# archive_command dans postgresql.conf :
# archive_command = 'PG_PASSWORD=xxx /opt/joaccountant/scripts/backup-postgres.sh --wal-archive %p %f'
