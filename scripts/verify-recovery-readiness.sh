#!/usr/bin/env bash
# Disposable PostgreSQL/Flyway recovery-readiness acceptance.
# This gate validates migration and read-only runtime facts; it is not a backup
# or cloud disaster-recovery product and never touches a caller-owned database.
set -euo pipefail
umask 077

cd "$(dirname "$0")/.."

RUN_ID="${RECOVERY_READINESS_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="${RECOVERY_READINESS_LOG_DIR:-.verification/recovery-readiness/${RUN_ID}}"
PG_IMAGE="${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}"
PG_CONTAINER="spring-ai-rag-recovery-${RUN_ID}-$$"
PG_PORT="${RECOVERY_READINESS_PG_PORT:-15439}"
PG_DB="spring_ai_rag_recovery"
PG_USER="postgres"
PG_PASSWORD="postgres"
mkdir -p "$RUN_DIR"
chmod 700 "$RUN_DIR"

cleanup() {
  docker rm -f "$PG_CONTAINER" >"$RUN_DIR/cleanup.log" 2>&1 || true
}
trap cleanup EXIT INT TERM

command -v docker >/dev/null || { echo "Missing required command: docker" >&2; exit 2; }
command -v mvn >/dev/null || { echo "Missing required command: mvn" >&2; exit 2; }
command -v jq >/dev/null || { echo "Missing required command: jq" >&2; exit 2; }
command -v lsof >/dev/null || { echo "Missing required command: lsof" >&2; exit 2; }
command -v psql >/dev/null || { echo "Missing required command: psql" >&2; exit 2; }

if lsof -nP -iTCP:"$PG_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Recovery readiness port $PG_PORT is already in use" >&2
  exit 2
fi

echo "Starting disposable PostgreSQL: image=$PG_IMAGE port=$PG_PORT"
docker run --rm -d --name "$PG_CONTAINER" \
  -e POSTGRES_DB="$PG_DB" -e POSTGRES_USER="$PG_USER" \
  -e POSTGRES_PASSWORD="$PG_PASSWORD" -p "127.0.0.1:${PG_PORT}:5432" \
  "$PG_IMAGE" >"$RUN_DIR/container-id" 

for _ in $(seq 1 60); do
  if docker exec "$PG_CONTAINER" pg_isready -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

docker exec "$PG_CONTAINER" pg_isready -U "$PG_USER" -d "$PG_DB" >/dev/null

export RECOVERY_READINESS_JDBC_URL="jdbc:postgresql://127.0.0.1:${PG_PORT}/${PG_DB}"
export RECOVERY_READINESS_JDBC_USER="$PG_USER"
export RECOVERY_READINESS_JDBC_PASSWORD="$PG_PASSWORD"

mvn -q -pl spring-ai-rag-core -am \
  -Dtest=LlmBudgetAndGovernancePostgresIntegrationTest \
  -Dllm-budget-governance.it.enabled=true \
  -Dllm-budget-governance.it.jdbc-url="$RECOVERY_READINESS_JDBC_URL" \
  -Dllm-budget-governance.it.username="$PG_USER" \
  -Dllm-budget-governance.it.password="$PG_PASSWORD" \
  -Dtestcontainers.ryuk.disabled=true \
  -Dsurefire.failIfNoSpecifiedTests=false test >"$RUN_DIR/maven.log" 2>&1

latest_migration="$(find spring-ai-rag-core/src/main/resources/db/migration -maxdepth 1 -type f -name 'V*.sql' \
  -print | sed -E 's#^.*/V([0-9]+)__.*#\1#' | sort -n | tail -1)"
[[ "$latest_migration" =~ ^[0-9]+$ ]] || {
  echo "Could not determine the latest repository migration" >&2
  exit 1
}

database_latest_migration="$(PGPASSWORD="$PG_PASSWORD" psql \
  --no-psqlrc --quiet --tuples-only --no-align \
  --host 127.0.0.1 --port "$PG_PORT" --username "$PG_USER" --dbname "$PG_DB" \
  --command "select version from flyway_schema_history where success order by installed_rank desc limit 1;")"
database_latest_migration="${database_latest_migration//[[:space:]]/}"
[[ "$database_latest_migration" == "$latest_migration" ]] || {
  echo "Flyway database history does not match repository migrations: repository=$latest_migration database=$database_latest_migration" >&2
  exit 1
}

jq -n \
  --arg runId "$RUN_ID" \
  --arg migration "V${latest_migration}" \
  --arg database "${PG_DB}" \
  --arg image "$PG_IMAGE" \
  --arg databaseMigration "V${database_latest_migration}" \
  '{runId:$runId, database:"disposable", migration:$migration, image:$image,
    databaseName:$database, databaseMigration:$databaseMigration,
    secretsRecorded:false, payloadsRecorded:false,
    callerOwnedDatabaseTouched:false, status:"PASS"}' \
  >"$RUN_DIR/manifest.json"

cat "$RUN_DIR/manifest.json"
echo "Recovery readiness acceptance passed. Evidence: $RUN_DIR"
