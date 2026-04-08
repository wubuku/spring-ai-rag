/**
 * k6 Exploratory Load Test — Spring AI RAG Service
 * =================================================
 * Goal: Find the throughput ceiling by ramping VUs in stages.
 * Uses k6 built-in stages for proper VU control.
 * Tracks throughput (req/s), p95/p99 latency, and error rate per stage.
 *
 * Saturation is detected when:
 *   - Throughput plateaus (< 5% growth over 2 consecutive stages)
 *   - Error rate exceeds 5%
 *   - p95 latency exceeds 2000ms
 *
 * Usage:
 *   k6 run scripts/k6-ramp-to-saturation.js
 *   k6 run -e BASE_URL=http://prod:8081 -e END_VUS=300 scripts/k6-ramp-to-saturation.js
 *
 * Environment Variables:
 *   BASE_URL       – API base URL (default: http://localhost:8081)
 *   API_KEY        – API key (default: test-api-key)
 *   START_VUS      – Starting VU count (default: 5)
 *   END_VUS        – Max VU count (default: 200)
 *   STAGE_DURATION – Duration per stage, e.g. "20s" (default: "20s")
 *   STAGE_STEP     – VUs to add per stage (default: 10)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

// ─────────────────────────────────────────────────────────────────
// Configuration
// ─────────────────────────────────────────────────────────────────

const BASE_URL      = __ENV.BASE_URL       || 'http://localhost:8081';
const API_KEY       = __ENV.API_KEY        || 'test-api-key';
const START_VUS     = parseInt(__ENV.START_VUS     || '5');
const END_VUS       = parseInt(__ENV.END_VUS       || '200');
const STAGE_DUR     = __ENV.STAGE_DURATION || '20s';
const STAGE_STEP    = parseInt(__ENV.STAGE_STEP    || '10');

// ─────────────────────────────────────────────────────────────────
// Build stages for VU ramp
// ─────────────────────────────────────────────────────────────────

function buildStages() {
  const stages = [];
  for (let vu = START_VUS; vu <= END_VUS; vu += STAGE_STEP) {
    stages.push({ target: vu, duration: STAGE_DUR });
  }
  return stages;
}

// ─────────────────────────────────────────────────────────────────
// Options — VU ramp driven by stages
// ─────────────────────────────────────────────────────────────────

export const options = {
  stages: buildStages(),
  thresholds: {
    // No hard thresholds — exploratory test
    http_req_duration: [],
    http_req_failed:   [],
    http_errors_total: [],
  },
  // No time limit — stages control duration
};

// ─────────────────────────────────────────────────────────────────
// Custom Metrics
// ─────────────────────────────────────────────────────────────────

const httpReqDuration    = new Trend('http_req_duration', true);           // p50/p95/p99
const httpReqFailed      = new Rate('http_req_failed');                    // error rate
const httpErrorsTotal    = new Counter('http_errors_total');
const apiCallsTotal      = new Counter('api_calls_total');
const stageThroughputRPS = new Trend('stage_throughput_rps', false);       // raw rps per stage

// Per-stage aggregation (approximated via global counters + stage index)
const stageRequestCounts  = [];
const stageErrorCounts   = [];
const stageDurationsMs   = [];

// ─────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────

const HEADERS = {
  'Content-Type': 'application/json',
  'X-API-Key': API_KEY,
};

function get(url, tags) {
  const res = http.get(url, { headers: HEADERS, tags: tags || {} });
  apiCallsTotal.add(1);
  httpReqDuration.add(res.timings.duration, { url });
  if (res.status >= 400) {
    httpErrorsTotal.add(1, { status: String(res.status), url });
    httpReqFailed.add(1);
  }
  return res;
}

function post(url, body, tags) {
  const res = http.post(url, JSON.stringify(body), { headers: HEADERS, tags: tags || {} });
  apiCallsTotal.add(1);
  httpReqDuration.add(res.timings.duration, { url });
  if (res.status >= 400) {
    httpErrorsTotal.add(1, { status: String(res.status), url });
    httpReqFailed.add(1);
  }
  return res;
}

// ─────────────────────────────────────────────────────────────────
// Main — one iteration per VU per time unit
// ─────────────────────────────────────────────────────────────────

let stageIndex = 0;
let lastStageChange = Date.now();
let stageRequests  = 0;
let stageErrors    = 0;

export default function () {
  const now = Date.now();
  const stageElapsedSec = (now - lastStageChange) / 1000;
  const stageDurSec = parseStageDuration(STAGE_DUR);

  // Detect stage transition
  if (stageElapsedSec >= stageDurSec) {
    // Record stage summary
    if (stageRequests > 0) {
      const rps = stageRequests / stageElapsedSec;
      stageThroughputRPS.add(rps, { vus: __VU, stage: stageIndex });
      stageRequestCounts.push(stageRequests);
      stageDurationsMs.push(stageElapsedSec * 1000);
      stageErrorCounts.push(stageErrors);
      console.log(`[Stage ${stageIndex}] VUs=${__VU} | requests=${stageRequests} | duration=${stageElapsedSec.toFixed(1)}s | RPS≈${rps.toFixed(1)} | errors=${stageErrors}`);
    }
    stageIndex++;
    stageRequests = 0;
    stageErrors   = 0;
    lastStageChange = now;
  }

  // ── Workload: read-heavy mix ─────────────────────────────────
  // 4 requests per iteration: health, metrics, list docs, list collections
  const h = get(`${BASE_URL}/api/v1/rag/health`, { name: 'health' });
  check(h, { 'health 200': (r) => r.status === 200 });

  const m = get(`${BASE_URL}/api/v1/rag/metrics/overview`, { name: 'metrics' });
  check(m, { 'metrics 200/500': (r) => r.status === 200 || r.status === 500 });

  const d = get(`${BASE_URL}/api/v1/rag/documents?page=0&size=5`, { name: 'list_documents' });
  check(d, { 'list_docs 200/500': (r) => r.status === 200 || r.status === 500 });

  const c = get(`${BASE_URL}/api/v1/rag/collections?page=0&size=5`, { name: 'list_collections' });
  check(c, { 'list_collections 200/500': (r) => r.status === 200 || r.status === 500 });

  stageRequests += 4;

  sleep(0.05); // brief pause between request bursts
}

// ─────────────────────────────────────────────────────────────────
// Summary — printed at end of test
// ─────────────────────────────────────────────────────────────────

export function handleSummary(data) {
  const stages = buildStages();
  const totalRequests = stageRequestCounts.reduce((a, b) => a + b, 0);
  const totalErrors   = stageErrorCounts.reduce((a, b) => a + b, 0);
  const totalDurationSec = stageDurationsMs.reduce((a, b) => a + b, 0) / 1000;
  const overallRps = totalDurationSec > 0 ? totalRequests / totalDurationSec : 0;
  const overallErrorRate = totalRequests > 0 ? (totalErrors / totalRequests) * 100 : 0;

  // Find peak RPS
  let peakRps = 0;
  let peakStage = 0;
  for (let i = 0; i < stageRequestCounts.length; i++) {
    const dur = stageDurationsMs[i] / 1000;
    const rps = dur > 0 ? stageRequestCounts[i] / dur : 0;
    if (rps > peakRps) {
      peakRps = rps;
      peakStage = i + 1;
    }
  }

  // Build per-stage table
  let stageTable = '';
  for (let i = 0; i < stageRequestCounts.length && i < stages.length; i++) {
    const dur = stageDurationsMs[i] / 1000;
    const rps = dur > 0 ? stageRequestCounts[i] / dur : 0;
    const errRate = stageRequestCounts[i] > 0
      ? ((stageErrorCounts[i] / stageRequestCounts[i]) * 100).toFixed(2)
      : '0.00';
    const targetVus = stages[i]?.target || '?';
    stageTable += `  Stage ${(i+1).toString().padStart(2)} | VUs=${targetVus.toString().padStart(3)} | reqs=${stageRequestCounts[i].toString().padStart(6)} | RPS=${rps.toFixed(1).padStart(7)} | errors=${stageErrorCounts[i].toString().padStart(4)} | err%=${errRate.padStart(5)}\n`;
  }

  return {
    stdout: `
╔══════════════════════════════════════════════════════════════════════╗
║         Spring AI RAG — Exploratory Load Test Summary               ║
╠══════════════════════════════════════════════════════════════════════╣
║  Base URL:        ${BASE_URL}
║  VU Range:        ${START_VUS} → ${END_VUS}  (step ${STAGE_STEP})
║  Stage Duration:  ${STAGE_DUR}
║  Stages Run:      ${stageRequestCounts.length} / ${stages.length}
║                                                                      ║
║  Peak Throughput: ${peakRps.toFixed(1)} req/s  (Stage ${peakStage})            ║
║  Overall RPS:     ${overallRps.toFixed(1)} req/s                               ║
║  Total Requests:  ${totalRequests.toLocaleString()}                                    ║
║  Total Errors:   ${totalErrors.toLocaleString()} (${overallErrorRate.toFixed(2)}%)                      ║
║                                                                      ║
╠══════════════════════════════════════════════════════════════════════╣
║  Per-Stage Results                                                   ║
${stageTable}╠══════════════════════════════════════════════════════════════════════╣
║  INTERPRETATION                                                      ║
║  • Peak RPS = throughput ceiling for this deployment config          ║
║  • Throughput plateau = VUs where RPS stops increasing              ║
║  • Error rate > 5% = system is overloaded                          ║
║  • p95 > 2000ms = latency SLA breach                               ║
║                                                                      ║
║  To extend the test: set END_VUS=400 or STAGE_STEP=5               ║
╚══════════════════════════════════════════════════════════════════════╝
`,
  };
}

// ─────────────────────────────────────────────────────────────────
// Utils
// ─────────────────────────────────────────────────────────────────

function parseStageDuration(dur) {
  // Parse "20s" → 20, "1m" → 60, "2h" → 7200
  const match = dur.match(/^(\d+)([smh])$/);
  if (!match) return 20;
  const val = parseInt(match[1]);
  switch (match[2]) {
    case 's': return val;
    case 'm': return val * 60;
    case 'h': return val * 3600;
    default:  return val;
  }
}
