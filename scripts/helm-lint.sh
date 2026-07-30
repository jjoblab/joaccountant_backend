#!/usr/bin/env bash
# Audit backend #5 — Helm lint + template validation
set -euo -o pipefail

CHART_DIR="${1:-deploy/helm/joaccountant}"

if [ ! -d "$CHART_DIR" ]; then
    echo "ERROR: Chart directory not found: $CHART_DIR"
    exit 1
fi

if ! command -v helm &> /dev/null; then
    echo "WARNING: helm CLI not installed — skipping lint"
    echo "Install: https://helm.sh/docs/intro/install/"
    exit 0
fi

echo "=== Helm lint ==="
helm lint "$CHART_DIR" || { echo "FAIL: helm lint"; exit 1; }

echo ""
echo "=== Helm template (dry-run) ==="
helm template joaccountant "$CHART_DIR" \
    --set image.repository=joaccountant \
    --set image.tag=latest \
    --set secrets.dbPassword=testpass \
    --set secrets.jwtSecret=testsecret256bits \
    > /dev/null 2>&1 || { echo "FAIL: helm template"; exit 1; }

echo ""
echo "✅ Helm chart valid"
