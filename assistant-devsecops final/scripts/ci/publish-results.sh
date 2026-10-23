#!/usr/bin/env bash
# Rôle : transmettre les rapports de tests, couverture et sécurité du pipeline CI/CD au backend.
# -----------------------------------------------------------------------------
# Publie les résultats d'un pipeline CI/CD vers l'Assistant DevSecOps puis
# applique la quality gate (le job échoue si elle n'est pas franchie).
#
# Variables requises : ASSISTANT_URL (ex. https://assistant.exemple.com)
#                      ASSISTANT_API_KEY (= CI_API_KEY du backend)
# Variables optionnelles : BRANCH, COMMIT_SHA, PIPELINE_ID, APP_LOG_FILE,
#                      SUREFIRE_DIR, JACOCO_XML, DEPCHECK_JSON, TRIVY_JSON,
#                      ENFORCE_GATE (true par défaut),
#                      APP_CODE    : code de l'application (multi-applications ; sans lui,
#                                    les résultats vont à l'application par défaut),
#                      SERVER_LOG_FILE : server.log JBoss/WildFly (couche SERVER),
#                      CI_LOG_FILE : sortie du build (couche CICD)
# -----------------------------------------------------------------------------
set -euo pipefail

: "${ASSISTANT_URL:?ASSISTANT_URL non défini}"
: "${ASSISTANT_API_KEY:?ASSISTANT_API_KEY non défini}"
SUREFIRE_DIR="${SUREFIRE_DIR:-backend/target/surefire-reports}"
JACOCO_XML="${JACOCO_XML:-backend/target/site/jacoco/jacoco.xml}"
DEPCHECK_JSON="${DEPCHECK_JSON:-backend/target/dependency-check-report.json}"
TRIVY_JSON="${TRIVY_JSON:-trivy-report.json}"
ENFORCE_GATE="${ENFORCE_GATE:-true}"

api() { curl -sS --fail-with-body -H "X-API-Key: ${ASSISTANT_API_KEY}" "$@"; }
# Paramètre d'application ajouté à chaque appel (vide = application par défaut)
APP_Q=""
[ -n "${APP_CODE:-}" ] && APP_Q="app=${APP_CODE}"
q() { local base="$1"; if [ -n "${APP_Q}" ]; then case "${base}" in *\?*) echo "${base}&${APP_Q}";; *) echo "${base}?${APP_Q}";; esac; else echo "${base}"; fi; }

echo "==> Publication vers ${ASSISTANT_URL}${APP_CODE:+ (application ${APP_CODE})}"

if compgen -G "${SUREFIRE_DIR}/TEST-*.xml" > /dev/null; then
  args=()
  for f in "${SUREFIRE_DIR}"/TEST-*.xml; do args+=(-F "reports=@${f};type=application/xml"); done
  [ -f "${JACOCO_XML}" ] && args+=(-F "coverage=@${JACOCO_XML};type=application/xml")
  echo "--> Tests (${#args[@]} fichier(s))"
  api "${args[@]}" "$(q "${ASSISTANT_URL}/api/ci/tests?branch=${BRANCH:-}&commit=${COMMIT_SHA:-}&pipeline=${PIPELINE_ID:-}")" ; echo
else
  echo "--> Aucun rapport Surefire trouvé dans ${SUREFIRE_DIR}"
fi

if [ -f "${DEPCHECK_JSON}" ]; then
  echo "--> OWASP Dependency-Check"
  api -H "Content-Type: application/json" --data-binary "@${DEPCHECK_JSON}" "$(q "${ASSISTANT_URL}/api/ci/security/dependency-check")"; echo
fi

if [ -f "${TRIVY_JSON}" ]; then
  echo "--> Trivy"
  api -H "Content-Type: application/json" --data-binary "@${TRIVY_JSON}" "$(q "${ASSISTANT_URL}/api/ci/security/trivy")"; echo
fi

if [ -n "${APP_LOG_FILE:-}" ] && [ -f "${APP_LOG_FILE}" ]; then
  echo "--> Logs (${APP_LOG_FILE})"
  api -H "Content-Type: text/plain" --data-binary "@${APP_LOG_FILE}" "$(q "${ASSISTANT_URL}/api/ci/logs?source=pipeline")"; echo
fi

if [ -n "${SERVER_LOG_FILE:-}" ] && [ -f "${SERVER_LOG_FILE}" ]; then
  echo "--> Logs serveur JBoss/WildFly (${SERVER_LOG_FILE})"
  api -H "Content-Type: text/plain" --data-binary "@${SERVER_LOG_FILE}" "$(q "${ASSISTANT_URL}/api/ci/logs?logType=SERVER")"; echo
fi

if [ -n "${CI_LOG_FILE:-}" ] && [ -f "${CI_LOG_FILE}" ]; then
  echo "--> Logs du pipeline (${CI_LOG_FILE})"
  api -H "Content-Type: text/plain" --data-binary "@${CI_LOG_FILE}" "$(q "${ASSISTANT_URL}/api/ci/logs?logType=CICD&source=ci-pipeline")"; echo
fi

echo "==> Quality gate"
status=$(curl -sS -o gate.json -w '%{http_code}' -H "X-API-Key: ${ASSISTANT_API_KEY}" "$(q "${ASSISTANT_URL}/api/ci/quality-gate")")
cat gate.json; echo
if [ "${status}" = "200" ]; then
  echo "Quality gate FRANCHIE"
elif [ "${status}" = "412" ]; then
  echo "Quality gate EN ÉCHEC"
  [ "${ENFORCE_GATE}" = "true" ] && exit 1
else
  echo "Réponse inattendue (${status})" && exit 2
fi
