#!/usr/bin/env bash
# Injecte un incident de démonstration dans une instance en cours d'exécution
# (utile pendant une soutenance pour voir les notifications temps réel).
# Usage : ./scripts/demo-seed.sh [http://localhost:8080] [utilisateur] [mot_de_passe]
set -euo pipefail
URL="${1:-http://localhost:8080}"
USER_NAME="${2:-dev}"
PASSWORD="${3:-Dev@2026}"

TOKEN=$(curl -sS -H "Content-Type: application/json" \
  -d "{\"username\":\"${USER_NAME}\",\"password\":\"${PASSWORD}\"}" "${URL}/api/auth/login" \
  | sed -E 's/.*"token":"([^"]+)".*/\1/')

NOW=$(date '+%Y-%m-%d %H:%M:%S')
LOGS=""
for i in $(seq 1 8); do
  LOGS+="${NOW} ERROR [settlement-service] Deadlock detected while updating trade ${RANDOM} in SETTLEMENT table"$'\n'
done
LOGS+="${NOW} FATAL [settlement-service] Service stopped after repeated deadlocks"$'\n'

curl -sS -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: text/plain" --data-binary "${LOGS}" \
  "${URL}/api/logs/ingest?source=settlement-service"
echo
echo "Incident injecté : regardez les notifications du tableau de bord."
