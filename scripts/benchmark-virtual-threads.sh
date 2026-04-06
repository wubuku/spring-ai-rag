#!/bin/bash
# =============================================================================
# Virtual Threads Performance Benchmark
# Validates Spring Boot 3.5 + Java 21 virtual threads under high concurrency.
#
# Usage:
#   ./benchmark-virtual-threads.sh                        # defaults
#   BASE_URL=http://localhost:8081 CONCURRENT=100 ./...  # custom
#   CONCURRENT=50 REQUESTS=500 DURATION=60 ./...         # heavier load
#
# Prerequisites:
#   1. Server running: mvn spring-boot:run -pl spring-ai-rag-core
#   2. curl, bc installed
# =============================================================================

BASE_URL="${BASE_URL:-http://localhost:8081}"
CONCURRENT="${CONCURRENT:-50}"
REQUESTS="${REQUESTS:-200}"
WARMUP="${WARMUP:-10}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

pass()  { echo -e "${GREEN}✅ PASS${NC}  $1"; }
fail()  { echo -e "${RED}❌ FAIL${NC}  $1"; }
info()  { echo -e "${YELLOW}ℹ️  INFO${NC} $1"; }
phase() { echo -e "${CYAN}▸${NC} $1"; }

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║     Spring AI RAG — Virtual Threads Benchmark           ║"
echo "╠══════════════════════════════════════════════════════════╣"
printf "║  Base URL:    %-42s║\n" "$BASE_URL"
printf "║  Concurrent:  %-42s║\n" "${CONCURRENT} parallel connections"
printf "║  Total reqs:  %-42s║\n" "$REQUESTS requests"
printf "║  Warmup:      %-42s║\n" "$WARMUP requests"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# ── Helper: check server ready ─────────────────────────────────────────────────
wait_for_server() {
  phase "Waiting for server to be ready..."
  local max_retries=30
  for i in $(seq 1 $max_retries); do
    local status
    status=$(curl -s --connect-timeout 2 --max-time 5 \
      "$BASE_URL/api/v1/rag/health" \
      2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','DOWN'))" 2>/dev/null || echo "DOWN")
    if [ "$status" = "UP" ] || [ "$status" = "ok" ]; then
      pass "Server is UP (attempt $i)"
      return 0
    fi
    sleep 1
  done
  fail "Server did not become ready after ${max_retries}s"
  exit 1
}

# ── Phase 1: Server health check ───────────────────────────────────────────────
wait_for_server

# ── Phase 2: Warmup ───────────────────────────────────────────────────────────
phase "Warmup: $WARMUP requests..."
WARMUP_BODY='{"query":"warmup ping","sessionId":"bench-warmup"}'
for i in $(seq 1 $WARMUP); do
  curl -s --connect-timeout 3 --max-time 15 \
    -X POST "$BASE_URL/api/v1/rag/chat/ask" \
    -H "Content-Type: application/json" \
    -d "$WARMUP_BODY" > /dev/null 2>&1 || true
done
pass "Warmup done"

# ── Phase 3: Concurrent benchmark ─────────────────────────────────────────────
phase "Starting benchmark: $REQUESTS requests with $CONCURRENT parallelism..."

# Each trial: returns "HTTP_CODE LATENCY_MS" (space-separated)
do_trial() {
  local trial_id="$1"
  local body
  body=$(printf '{"query":"virtual thread benchmark %d","sessionId":"bench-%s"}' \
    $((trial_id % 20 + 1)) "vt-$trial_id")

  local start_ns end_ns latency http_code
  start_ns=$(date +%s%N)

  # Capture http_code via -w and body via -o
  local curl_out
  curl_out=$(curl -s --connect-timeout 5 --max-time 30 \
    -w "\n%{http_code}" \
    -o /dev/null \
    -X POST "$BASE_URL/api/v1/rag/chat/ask" \
    -H "Content-Type: application/json" \
    -d "$body" 2>/dev/null)

  end_ns=$(date +%s%N)
  latency=$(echo "scale=2; ($end_ns - $start_ns) / 1000000" | bc)
  http_code=$(echo "$curl_out" | tail -1)

  # Output: "HTTP_CODE LATENCY_MS"
  echo "$http_code $latency"
}

export -f do_trial
export BASE_URL

# Generate trial IDs and run in parallel with xargs
TMPRESULTS=$(mktemp)
BENCH_START=$(date +%s%N)
seq 1 $REQUESTS | xargs -P $CONCURRENT -I{} bash -c 'do_trial {}' > "$TMPRESULTS"
BENCH_END=$(date +%s%N)
BENCH_ELAPSED_MS=$(( (BENCH_END - BENCH_START) / 1000000 ))

# ── Phase 4: Parse results ────────────────────────────────────────────────────
total=$(wc -l < "$TMPRESULTS" | tr -d ' ')
success=$(awk '{if($1==200) s++} END{print s+0}' "$TMPRESULTS")
fail_count=$((total - success))

if [ "$total" -eq 0 ]; then
  fail "No responses received"
  rm -f "$TMPRESULTS"
  exit 1
fi

# Latencies in column 2
TMPLAT=$(mktemp)
awk '{print $2}' "$TMPRESULTS" > "$TMPLAT"

count=$(wc -l < "$TMPLAT" | tr -d ' ')

# Compute percentiles using sort + head/tail
p50_lat=$(sort -n "$TMPLAT" | awk -v p=50 -v n="$count" 'BEGIN{c=int(n*p/100); if(c<1)c=1} {if(++lines==c) print}')
p95_lat=$(sort -n "$TMPLAT" | awk -v p=95 -v n="$count" 'BEGIN{c=int(n*p/100); if(c<1)c=1} {if(++lines==c) print}')
p99_lat=$(sort -n "$TMPLAT" | awk -v p=99 -v n="$count" 'BEGIN{c=int(n*p/100); if(c<1)c=1} {if(++lines==c) print}')
avg_latency=$(awk '{sum+=$1} END {printf "%.2f", sum/NR}' "$TMPLAT")
max_latency=$(tail -1 <<< "$(sort -n "$TMPLAT")")
min_latency=$(head -1 <<< "$(sort -n "$TMPLAT")")

# Throughput based on actual wall-clock elapsed time
BENCH_ELAPSED_S=$(echo "scale=2; $BENCH_ELAPSED_MS / 1000" | bc)
qps=$(echo "scale=2; $total / $BENCH_ELAPSED_S" | bc)

success_rate=$(echo "scale=1; $success * 100 / $total" | bc)

# ── Phase 5: Report ────────────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║              Benchmark Results                           ║"
echo "╠══════════════════════════════════════════════════════════╣"
printf "║  Total requests :  %-41s║\n" "$total"
printf "║  Successful     :  %-41s║\n" "$success"
printf "║  Failed         :  %-41s║\n" "$fail_count"
printf "║  Wall time      :  %-41s║\n" "${BENCH_ELAPSED_S}s"
printf "║  Throughput     :  %-41s║\n" "${qps} req/s"
echo "╠══════════════════════════════════════════════════════════╣"
echo "║  Latency (ms)   :                                       ║"
printf "║    min          :  %-41s║\n" "${min_latency} ms"
printf "║    avg          :  %-41s║\n" "${avg_latency} ms"
printf "║    p(50)        :  %-41s║\n" "${p50_lat} ms"
printf "║    p(95)        :  %-41s║\n" "${p95_lat} ms"
printf "║    p(99)        :  %-41s║\n" "${p99_lat} ms"
printf "║    max          :  %-41s║\n" "${max_latency} ms"
echo "╠══════════════════════════════════════════════════════════╣"
printf "║  Success rate   :  %-41s║\n" "${success_rate}%"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# ── Phase 6: Evaluate SLOs ─────────────────────────────────────────────────────
phase "Evaluating SLOs..."

# Chat RAG SLO: p99 < 5000ms (LLM calls included), success >= 95%
status=0

if   [ "$success_rate" -ge 99 ]; then result="EXCELLENT"
elif [ "$success_rate" -ge 95 ]; then result="GOOD"
elif [ "$success_rate" -ge 80 ]; then result="FAIR"
else                                  result="POOR"; status=1
fi

phase "Success rate: ${success_rate}% → $result"

# p99 SLO check
p99_int=${p99_lat%.*}
PHIGH=5000
if [ "$p99_int" -lt "$PHIGH" ]; then
  pass "p99 latency: ${p99_lat}ms < ${PHIGH}ms (chat SLO target)"
else
  fail "p99 latency: ${p99_lat}ms exceeds ${PHIGH}ms (SLO breach)"
  status=1
fi

# p95 SLO check (informational)
p95_int=${p95_lat%.*}
PTGT=2000
if [ "$p95_int" -lt "$PTGT" ]; then
  pass "p95 latency: ${p95_lat}ms < ${PTGT}ms (info)"
else
  info "p95 latency: ${p95_lat}ms exceeds ${PTGT}ms (acceptable)"
fi

if [ "$status" -eq 0 ]; then
  pass "Virtual threads benchmark: all SLOs met"
else
  fail "Virtual threads benchmark: some SLOs breached"
fi

rm -f "$TMPRESULTS" "$TMPLAT"
exit $status
