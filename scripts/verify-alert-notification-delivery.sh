#!/usr/bin/env bash
# Durable alert notification delivery 的真实 PostgreSQL/HTTP/双实例/WebUI 验收。
set -euo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${ALERT_NOTIFICATION_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${ALERT_NOTIFICATION_VERIFY_LOG_DIR:-.verification/alert-notification-delivery/${RUN_ID}}"
BACKEND_A_PORT="${ALERT_NOTIFICATION_BACKEND_A_PORT:-18281}"
BACKEND_B_PORT="${ALERT_NOTIFICATION_BACKEND_B_PORT:-18282}"
FRONTEND_PORT="${ALERT_NOTIFICATION_FRONTEND_PORT:-15281}"
STUB_PORT="${ALERT_NOTIFICATION_STUB_PORT:-4281}"
PG_IMAGE="${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}"

PG_CONTAINER=""
BACKEND_A_PID=""
BACKEND_B_PID=""
FRONTEND_PID=""
STUB_PID=""
DB_PORT=""
RUNTIME_CLASSPATH=""
ROOT_KEY="notification-root-$(openssl rand -hex 32)"
PASS_COUNT=0
STEP_INDEX=0

mkdir -p "$LOG_DIR/private"
chmod 700 "$LOG_DIR/private"
: >"$LOG_DIR/summary.tsv"

slugify() {
  printf '%s' "$1" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9._-'
}

run_step() {
  local name="$1"
  shift
  STEP_INDEX=$((STEP_INDEX + 1))
  local log="$LOG_DIR/${STEP_INDEX}-$(slugify "$name").log"
  echo
  echo "=== ${name} ==="
  "$@" > >(tee "$log") 2>&1
  PASS_COUNT=$((PASS_COUNT + 1))
  printf '%s\tPASS\t%s\n' "$name" "$log" >>"$LOG_DIR/summary.tsv"
}

stop_pid() {
  local pid="$1"
  if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
    kill "$pid" >/dev/null 2>&1 || true
    wait "$pid" >/dev/null 2>&1 || true
  fi
}

write_summary() {
  {
    echo "# Alert notification delivery verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Generated: \`$(date '+%Y-%m-%d %H:%M:%S %z')\`"
    echo "- Branch: \`$(git branch --show-current)\`"
    echo "- Commit: \`$(git rev-parse --short HEAD)\`"
    echo "- Passed steps: **${PASS_COUNT}**"
    echo
    echo "| Step | Status | Evidence |"
    echo "|------|--------|----------|"
    while IFS=$'\t' read -r name status evidence; do
      echo "| ${name} | ${status} | \`${evidence}\` |"
    done <"$LOG_DIR/summary.tsv"
  } >"$LOG_DIR/summary.md"
}

cleanup() {
  stop_pid "$FRONTEND_PID"
  stop_pid "$BACKEND_A_PID"
  stop_pid "$BACKEND_B_PID"
  stop_pid "$STUB_PID"
  if [[ -n "$PG_CONTAINER" ]]; then
    docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
  fi
  write_summary
}
trap cleanup EXIT

prerequisites() {
  local command_name port
  for command_name in curl docker git java jq lsof mvn node npm npx openssl; do
    command -v "$command_name" >/dev/null || {
      echo "Missing required command: ${command_name}" >&2
      return 1
    }
  done
  docker version >/dev/null
  for port in "$BACKEND_A_PORT" "$BACKEND_B_PORT" "$FRONTEND_PORT" "$STUB_PORT"; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "Verification port is already in use: ${port}" >&2
      return 1
    fi
  done
}

focused_tests() {
  TESTCONTAINERS_RYUK_DISABLED=true \
    mvn -q -pl spring-ai-rag-core -am \
      -Dalert-notification-delivery.it.enabled=true \
      -Dtest='AlertNotificationDeliveryPostgresIntegrationTest,AlertNotificationDeliveryControllerWebTest,AlertServiceImplTest,DingTalkNotificationServiceTest,EmailNotificationServiceTest,NotificationConfigTest' \
      -Dsurefire.failIfNoSpecifiedTests=false test
}

prepare_runtime() {
  local classpath_file="$LOG_DIR/runtime-classpath.txt"
  mvn -q -pl spring-ai-rag-core -am -DskipTests compile
  mvn -q -pl spring-ai-rag-core -am dependency:build-classpath \
    "-Dmdep.outputFile=${PWD}/${classpath_file}" -DincludeScope=runtime
  RUNTIME_CLASSPATH="spring-ai-rag-core/target/classes:"
  RUNTIME_CLASSPATH+="spring-ai-rag-api/target/classes:"
  RUNTIME_CLASSPATH+="spring-ai-rag-documents/target/classes:"
  RUNTIME_CLASSPATH+="spring-ai-rag-starter/target/classes:"
  RUNTIME_CLASSPATH+="$(cat "$classpath_file")"
}

start_database() {
  PG_CONTAINER="$(docker run -d --rm \
    -e POSTGRES_PASSWORD=postgres \
    -e POSTGRES_DB=alert_notification_gate \
    -p 127.0.0.1::5432 "$PG_IMAGE")"
  local attempt
  for attempt in $(seq 1 30); do
    DB_PORT="$(docker port "$PG_CONTAINER" 5432/tcp \
      | awk -F: 'NR == 1 {print $NF}')"
    [[ -n "$DB_PORT" ]] && break
    sleep 1
  done
  [[ -n "$DB_PORT" ]] || return 1
  for attempt in $(seq 1 120); do
    if docker exec "$PG_CONTAINER" pg_isready -U postgres \
        -d alert_notification_gate >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  docker logs "$PG_CONTAINER" >&2 || true
  return 1
}

start_stub() {
  node scripts/test-support/alert-notification-provider-stub.mjs "$STUB_PORT" \
    >"$LOG_DIR/provider-stub.log" 2>&1 &
  STUB_PID=$!
  local attempt
  for attempt in $(seq 1 30); do
    curl -fsS "http://127.0.0.1:${STUB_PORT}/health" >/dev/null 2>&1 \
      && return 0
    kill -0 "$STUB_PID" >/dev/null 2>&1 || return 1
    sleep 1
  done
  return 1
}

start_backend() {
  local port="$1" path="$2" log="$3"
  local app_json
  app_json="$(jq -nc \
    --arg webhook "http://127.0.0.1:${STUB_PORT}${path}" '
      {
        rag: {
          notifications: {
            dingtalk: [{
              name: "verification",
              enabled: true,
              "webhook-url": $webhook,
              "alert-types": ["SLO_BREACH"]
            }]
          }
        }
      }
    ')"
  env \
    SPRING_PROFILES_ACTIVE=postgresql \
    SERVER_PORT="$port" \
    SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${DB_PORT}/alert_notification_gate" \
    SPRING_DATASOURCE_USERNAME=postgres \
    SPRING_DATASOURCE_PASSWORD=postgres \
    RAG_SECURITY_ENABLED=true \
    RAG_ROOT_API_KEY="$ROOT_KEY" \
    APP_LLM_PROVIDER=openai \
    LLM_PROVIDER=openai \
    SPRING_AI_OPENAI_API_KEY=dummy \
    SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:9 \
    SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=dummy-chat \
    RAG_EMBEDDING_API_KEY=dummy \
    RAG_EMBEDDING_BASE_URL=http://127.0.0.1:9 \
    RAG_EMBEDDING_MODEL=dummy-embedding \
    RAG_EMBEDDING_DIMENSIONS=1024 \
    RAG_EMBEDDING_PROFILE_KEY=alert-notification-gate-1024-v1 \
    RAG_EMBEDDING_PROVIDER=verification \
    RAG_EMBEDDING_MODEL_REVISION=v1 \
    RAG_NOTIFICATIONS_ENABLED=true \
    RAG_NOTIFICATION_DELIVERY_ENABLED=true \
    RAG_NOTIFICATION_DELIVERY_FALLBACK_SCAN_INTERVAL=PT1M \
    RAG_NOTIFICATION_DELIVERY_PROVIDER_ATTEMPT_TIMEOUT=PT5S \
    RAG_NOTIFICATION_DELIVERY_LEASE_DURATION=PT30S \
    RAG_NOTIFICATION_DELIVERY_INITIAL_BACKOFF=PT1S \
    RAG_NOTIFICATION_DELIVERY_MAX_BACKOFF=PT2S \
    SPRING_APPLICATION_JSON="$app_json" \
    java -cp "$RUNTIME_CLASSPATH" \
      com.springairag.core.SpringAiRagApplication >"$log" 2>&1 &
  printf '%s\n' "$!"
}

wait_backend() {
  local port="$1" pid="$2" log="$3"
  local attempt
  for attempt in $(seq 1 120); do
    kill -0 "$pid" >/dev/null 2>&1 || {
      tail -100 "$log" >&2 || true
      return 1
    }
    curl -fsS "http://127.0.0.1:${port}/actuator/health" \
      | jq -e '.status == "UP"' >/dev/null 2>&1 && return 0
    sleep 1
  done
  tail -100 "$log" >&2 || true
  return 1
}

start_pair() {
  local path="$1"
  BACKEND_A_PID="$(start_backend "$BACKEND_A_PORT" "$path" "$LOG_DIR/backend-a.log")"
  wait_backend "$BACKEND_A_PORT" "$BACKEND_A_PID" "$LOG_DIR/backend-a.log"
  BACKEND_B_PID="$(start_backend "$BACKEND_B_PORT" "$path" "$LOG_DIR/backend-b.log")"
  wait_backend "$BACKEND_B_PORT" "$BACKEND_B_PID" "$LOG_DIR/backend-b.log"
}

stop_pair() {
  stop_pid "$BACKEND_A_PID"
  stop_pid "$BACKEND_B_PID"
  BACKEND_A_PID=""
  BACKEND_B_PID=""
}

root_curl() {
  curl -sS -H "X-API-Key: ${ROOT_KEY}" "$@"
}

fire_alert() {
  local base="$1" name="$2" marker="$3" output="$4"
  root_curl -X POST "${base}/api/v1/rag/alerts/fire" \
    -H 'Content-Type: application/json' \
    -d "$(
      jq -nc --arg name "$name" --arg marker "$marker" '{
        alertType: "SLO_BREACH",
        alertName: $name,
        message: ("Bearer verification-private-token " + $marker),
        severity: "CRITICAL",
        metrics: {
          authorization: "Bearer verification-private-token",
          marker: $marker
        }
      }'
    )" \
    -o "$output" -w '%{http_code}'
}

poll_delivery() {
  local base="$1" alert_id="$2" expected="$3" output="$4" timeout="$5"
  local deadline=$(( $(date +%s) + timeout ))
  local code
  while (( $(date +%s) <= deadline )); do
    code="$(root_curl -G \
      --data-urlencode "alertId=${alert_id}" \
      --data-urlencode 'provider=DINGTALK' \
      -o "$output" -w '%{http_code}' \
      "${base}/api/v1/rag/alerts/notification-deliveries")"
    [[ "$code" == "200" ]] || return 1
    if jq -e --arg expected "$expected" '
        .notificationsEnabled == true
        and .durableDeliveryEnabled == true
        and .configuredProviders == ["DINGTALK"]
        and (.items | length) == 1
        and .items[0].status == $expected
        and (.items[0] | has("payload") | not)
        and (.items[0] | has("leaseToken") | not)
        and (.items[0] | has("leaseUntil") | not)
      ' "$output" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "Delivery ${alert_id} did not reach ${expected}" >&2
  cat "$output" >&2 || true
  return 1
}

stub_requests() {
  curl -fsS "http://127.0.0.1:${STUB_PORT}/requests"
}

assert_stub_payloads() {
  local path="$1" delivery_id="$2" expected_count="$3"
  local output="$LOG_DIR/private/stub-$(basename "$path").json"
  stub_requests >"$output"
  jq -e --arg path "$path" --arg deliveryId "$delivery_id" \
    --arg root "$ROOT_KEY" --argjson count "$expected_count" '
      [.requests[] | select(.path == $path)] as $requests
      | ($requests | length) == $count
      and all($requests[];
        (.body | contains($deliveryId))
        and (.body | contains("[REDACTED]"))
        and ((.body | contains($root)) | not)
        and ((.body | contains("verification-private-token")) | not)
        and ((.body | contains("webhook-url")) | not)
      )
    ' "$output" >/dev/null
}

transient_scenario() {
  start_pair /robot/transient
  local base_a="http://127.0.0.1:${BACKEND_A_PORT}"
  local base_b="http://127.0.0.1:${BACKEND_B_PORT}"
  local started code alert_id delivery_id elapsed
  started="$(date +%s)"
  code="$(fire_alert "$base_a" "Transient ${RUN_ID}" transient \
    "$LOG_DIR/private/transient-fire.json")"
  [[ "$code" == "200" ]] || return 1
  alert_id="$(jq -er '.alertId' "$LOG_DIR/private/transient-fire.json")"
  poll_delivery "$base_b" "$alert_id" RETRY_WAIT \
    "$LOG_DIR/private/transient-retry-wait.json" 20
  elapsed=$(( $(date +%s) - started ))
  (( elapsed < 60 )) || {
    echo "First attempt was not Event-driven" >&2
    return 1
  }
  poll_delivery "$base_b" "$alert_id" DELIVERED \
    "$LOG_DIR/private/transient-delivered.json" 90
  delivery_id="$(jq -er '.items[0].id' \
    "$LOG_DIR/private/transient-delivered.json")"
  jq -e '
    .items[0].attemptCount == 2
    and .items[0].lastErrorCode == null
    and .items[0].deliveredAt != null
  ' "$LOG_DIR/private/transient-delivered.json" >/dev/null
  assert_stub_payloads /robot/transient "$delivery_id" 2
  printf '%s\n' "$alert_id" >"$LOG_DIR/private/webui-alert-id"
  printf '%s\n' "$delivery_id" >"$LOG_DIR/private/webui-delivery-id"
  echo "transient_event_seconds=${elapsed} attempts=2 delivery=${delivery_id}"
}

start_frontend() {
  (
    cd spring-ai-rag-webui
    exec env \
      VITE_DEV_PORT="$FRONTEND_PORT" \
      VITE_DEV_PROXY_TARGET="http://127.0.0.1:${BACKEND_A_PORT}" \
      VITE_DEV_ORIGIN="http://127.0.0.1:${FRONTEND_PORT}/webui" \
      npm run dev -- --host 127.0.0.1 --strictPort
  ) >"$LOG_DIR/frontend.log" 2>&1 &
  FRONTEND_PID=$!
  local attempt
  for attempt in $(seq 1 60); do
    curl -fsS "http://127.0.0.1:${FRONTEND_PORT}/webui/unlock" \
      >/dev/null 2>&1 && return 0
    kill -0 "$FRONTEND_PID" >/dev/null 2>&1 || return 1
    sleep 1
  done
  return 1
}

real_webui() {
  start_frontend
  local alert_id delivery_id
  alert_id="$(cat "$LOG_DIR/private/webui-alert-id")"
  delivery_id="$(cat "$LOG_DIR/private/webui-delivery-id")"
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${FRONTEND_PORT}" \
      RAG_ROOT_API_KEY="$ROOT_KEY" \
      ALERT_DELIVERY_EXPECTED_ALERT_ID="$alert_id" \
      ALERT_DELIVERY_EXPECTED_DELIVERY_ID="$delivery_id" \
      ALERT_DELIVERY_EXPECTED_STATUS=DELIVERED \
      npx playwright test e2e/alerts-real.spec.ts --project=chromium
  )
  stop_pid "$FRONTEND_PID"
  FRONTEND_PID=""
}

crash_recovery_scenario() {
  stop_pair
  start_pair /robot/block
  local base_a="http://127.0.0.1:${BACKEND_A_PORT}"
  local base_b="http://127.0.0.1:${BACKEND_B_PORT}"
  local code alert_id delivery_id
  code="$(fire_alert "$base_a" "Crash ${RUN_ID}" crash \
    "$LOG_DIR/private/crash-fire.json")"
  [[ "$code" == "200" ]] || return 1
  alert_id="$(jq -er '.alertId' "$LOG_DIR/private/crash-fire.json")"
  poll_delivery "$base_b" "$alert_id" IN_PROGRESS \
    "$LOG_DIR/private/crash-in-progress.json" 20
  delivery_id="$(jq -er '.items[0].id' \
    "$LOG_DIR/private/crash-in-progress.json")"
  local request_deadline=$(( $(date +%s) + 20 ))
  while (( $(date +%s) <= request_deadline )); do
    if stub_requests | jq -e \
        '[.requests[] | select(.path == "/robot/block")] | length == 1' \
        >/dev/null; then
      break
    fi
    sleep 1
  done
  stop_pid "$BACKEND_A_PID"
  BACKEND_A_PID=""
  poll_delivery "$base_b" "$alert_id" DELIVERED \
    "$LOG_DIR/private/crash-delivered.json" 100
  jq -e '
    .items[0].attemptCount == 2
    and .items[0].deliveredAt != null
  ' "$LOG_DIR/private/crash-delivered.json" >/dev/null
  assert_stub_payloads /robot/block "$delivery_id" 2
  echo "crash_recovery_attempts=2 delivery=${delivery_id}"
}

database_facts() {
  local facts
  facts="$(docker exec "$PG_CONTAINER" psql -U postgres \
    -d alert_notification_gate -At -F '|' -c "
      SELECT
        (SELECT version FROM flyway_schema_history
          WHERE success ORDER BY installed_rank DESC LIMIT 1),
        COUNT(*),
        COUNT(*) FILTER (WHERE status = 'DELIVERED'),
        COUNT(*) FILTER (WHERE lease_token IS NOT NULL),
        COUNT(*) FILTER (
          WHERE payload::text LIKE '%verification-private-token%'
        )
      FROM rag_alert_notification_delivery;
    ")"
  [[ "$facts" == "58|2|2|0|0" ]] || {
    echo "Unexpected database facts: ${facts}" >&2
    return 1
  }
  echo "database_facts migration=58 deliveries=2 delivered=2 leases=0 secrets=0"
}

run_step "Prerequisites" prerequisites
run_step "Focused backend and PostgreSQL tests" focused_tests
run_step "Prepare runtime" prepare_runtime
run_step "Start disposable PostgreSQL" start_database
run_step "Start HTTP provider stub" start_stub
run_step "Event and transient retry lifecycle" transient_scenario
run_step "Real WebUI DOM and network contract" real_webui
run_step "Crash and second-instance lease recovery" crash_recovery_scenario
run_step "Final PostgreSQL facts" database_facts

echo
echo "Alert notification delivery verification passed: ${PASS_COUNT} steps"
echo "Summary: ${LOG_DIR}/summary.md"
