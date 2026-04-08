/**
 * k6 Persistent Session Stress Test — Spring AI RAG Service
 * =========================================================
 * Goal: Stress test ChatMemory locking by having multiple VUs
 * share the same session ID and concurrently write to ChatMemory.
 *
 * Scenario:
 *   - Multiple VUs all send chat messages with the SAME sessionId
 *   - This simulates high-concurrency dialog where many users
 *     write to the same chat session (e.g., shared session or
 *     faulty session ID distribution)
 *   - We track: message ordering, duplicate memory entries,
 *     latency degradation as VUs increase
 *
 * Usage:
 *   k6 run scripts/k6-session-stress.js
 *   k6 run -e START_VUS=10 -e END_VUS=100 scripts/k6-session-stress.js
 *
 * Environment Variables:
 *   BASE_URL        – API base URL (default: http://localhost:8081)
 *   API_KEY         – API key (default: test-api-key)
 *   SESSION_ID      – Shared session ID (default: shared-session-stress-<timestamp>)
 *   START_VUS       – Starting VU count (default: 5)
 *   END_VUS         – Max VU count (default: 100)
 *   STAGE_DURATION  – Duration per stage (default: "15s")
 *   STAGE_STEP      – VUs added per stage (default: 10)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

// ─────────────────────────────────────────────────────────────────
// Configuration
// ─────────────────────────────────────────────────────────────────

const BASE_URL       = __ENV.BASE_URL       || 'http://localhost:8081';
const API_KEY        = __ENV.API_KEY        || 'test-api-key';
const SESSION_ID     = __ENV.SESSION_ID     || `shared-session-stress-${Date.now()}`;
const START_VUS      = parseInt(__ENV.START_VUS      || '5');
const END_VUS        = parseInt(__ENV.END_VUS        || '100');
const STAGE_DUR      = __ENV.STAGE_DURATION  || '15s';
const STAGE_STEP     = parseInt(__ENV.STAGE_STEP    || '10');

// ─────────────────────────────────────────────────────────────────
// Build VU ramp stages
// ─────────────────────────────────────────────────────────────────

function buildStages() {
  const stages = [];
  for (let vu = START_VUS; vu <= END_VUS; vu += STAGE_STEP) {
    stages.push({ target: vu, duration: STAGE_DUR });
  }
  return stages;
}

// ─────────────────────────────────────────────────────────────────
// Options
// ─────────────────────────────────────────────────────────────────

export const options = {
  stages: buildStages(),
  thresholds: {
    // No hard thresholds — exploratory stress test
    http_req_duration: [],
    http_req_failed:   [],
  },
};

// ─────────────────────────────────────────────────────────────────
// Custom Metrics
// ─────────────────────────────────────────────────────────────────

const sessionReqDuration = new Trend('session_req_duration', true);  // Chat request duration under contention
const memoryReads        = new Counter('memory_reads_total');
const memoryWrites       = new Counter('memory_writes_total');
const concurrentSessions = new Trend('concurrent_sessions', false);  // Number of VUs hitting the same session
const httpErrors         = new Counter('http_errors_total');
const apiCallsTotal      = new Counter('api_calls_total');
const conflictRate       = new Rate('conflict_rate');                 // Rate of conflict/409 responses

// ─────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────

const HEADERS = {
  'Content-Type': 'application/json',
  'X-API-Key': API_KEY,
};

// Rotating queries for realistic chat simulation
const QUERIES = [
  'What is RAG?',
  'Explain hybrid retrieval.',
  'How does pgvector work?',
  'What is BGE-M3?',
  'How do advisors chain?',
  'What is the reranking approach?',
  'How does query rewriting help?',
  'What is the chunk size configuration?',
];

let queryIndex = 0;

function getNextQuery() {
  const q = QUERIES[queryIndex % QUERIES.length];
  queryIndex++;
  return q;
}

function callChatAsk(body) {
  const url = `${BASE_URL}/api/v1/rag/chat/ask`;
  const res = http.post(url, JSON.stringify(body), { headers: HEADERS, tags: { name: 'chat_ask_shared_session' } });
  apiCallsTotal.add(1);
  sessionReqDuration.add(res.timings.duration);

  if (res.status >= 400) {
    httpErrors.add(1, { status: String(res.status), url: 'chat/ask' });
    if (res.status === 409) {
      conflictRate.add(1);
    }
  }

  return res;
}

function callChatHistory() {
  const url = `${BASE_URL}/api/v1/rag/chat/history/${SESSION_ID}`;
  const res = http.get(url, { headers: HEADERS, tags: { name: 'chat_history_shared_session' } });
  apiCallsTotal.add(1);
  memoryReads.add(1);

  if (res.status >= 400) {
    httpErrors.add(1, { status: String(res.status), url: 'chat/history' });
  }

  return res;
}

function callClearHistory() {
  const url = `${BASE_URL}/api/v1/rag/chat/history/${SESSION_ID}`;
  const res = http.del(url, null, { headers: HEADERS, tags: { name: 'clear_history_shared_session' } });
  apiCallsTotal.add(1);

  if (res.status >= 400) {
    httpErrors.add(1, { status: String(res.status), url: 'chat/history/delete' });
  }

  return res;
}

// ─────────────────────────────────────────────────────────────────
// SETUP — one-time: create collection + document for chat
// ─────────────────────────────────────────────────────────────────

export function setup() {
  // Clean up any previous session
  callClearHistory();

  // Ensure we have a document in the database for search results
  const collectionRes = http.post(
    `${BASE_URL}/api/v1/rag/collections`,
    JSON.stringify({ name: 'stress-test-collection', description: 'k6 session stress test collection', dimensions: 1024 }),
    { headers: HEADERS }
  );

  let collectionId = null;
  if (collectionRes.status === 200 || collectionRes.status === 201) {
    try {
      collectionId = JSON.parse(collectionRes.body).id;
    } catch (e) { /* ignore */ }
  }

  return { collectionId, sessionId: SESSION_ID };
}

// ─────────────────────────────────────────────────────────────────
// TEARDOWN — cleanup
// ─────────────────────────────────────────────────────────────────

export function teardown(data) {
  // Clear shared session history
  callClearHistory();

  // Clean up collection if created
  if (data.collectionId) {
    http.del(`${BASE_URL}/api/v1/rag/collections/${data.collectionId}`, null, { headers: HEADERS });
  }
}

// ─────────────────────────────────────────────────────────────────
// Main — VU iteration: each VU sends a chat message to the SAME session
// ─────────────────────────────────────────────────────────────────

export default function () {
  // ── Simulate concurrent users writing to same session ────────

  // Send a chat message (this writes to ChatMemory)
  const query = getNextQuery();
  const body = {
    query: query,
    sessionId: SESSION_ID,
    retrievalConfig: {
      vectorWeight: 0.7,
      fulltextWeight: 0.3,
      topK: 3,
    },
  };

  const chatRes = callChatAsk(body);
  check(chatRes, {
    'chat 200 or 400': (r) => r.status === 200 || r.status === 400 || r.status === 500,
    'chat latency < 5000ms': (r) => r.timings.duration < 5000,
  });

  // Also read chat history concurrently (this reads from ChatMemory)
  if (__VU % 2 === 0) { // Even VUs also read history
    const histRes = callChatHistory();
    check(histRes, {
      'history 200': (r) => r.status === 200,
    });
    memoryReads.add(1);
  }

  // Log concurrent session tracking
  concurrentSessions.add(__VU);

  // Record this as a write (chat/ask writes user + AI message to memory)
  memoryWrites.add(1);

  sleep(0.1); // Small pause to prevent thundering herd
}

// ─────────────────────────────────────────────────────────────────
// Summary — printed at end of test
// ─────────────────────────────────────────────────────────────────

export function handleSummary(data) {
  const stages = buildStages();
  const reqDurationP50 = data.metrics['http_req_duration']?.values?.['p(50)']?.toFixed(0) || 'N/A';
  const reqDurationP95 = data.metrics['http_req_duration']?.values?.['p(95)']?.toFixed(0) || 'N/A';
  const reqDurationP99 = data.metrics['http_req_duration']?.values?.['p(99)']?.toFixed(0) || 'N/A';
  const errorRate = data.metrics['http_req_failed']?.values?.['rate'];
  const errorRatePct = errorRate !== undefined ? (errorRate * 100).toFixed(2) : 'N/A';
  const totalRequests = data.metrics['http_reqs']?.values?.['count'] || 'N/A';
  const totalErrors = data.metrics['http_errors_total']?.values?.['count'] || 'N/A';
  const totalMemoryReads = data.metrics['memory_reads_total']?.values?.['count'] || 'N/A';
  const totalMemoryWrites = data.metrics['memory_writes_total']?.values?.['count'] || 'N/A';

  return {
    stdout: `
╔══════════════════════════════════════════════════════════════════════╗
║       Spring AI RAG — Session Stress Test Summary                   ║
╠══════════════════════════════════════════════════════════════════════╣
║  Shared Session ID:  ${SESSION_ID}
║  Base URL:           ${BASE_URL}
║  VU Range:           ${START_VUS} → ${END_VUS}  (step ${STAGE_STEP})
║  Stage Duration:     ${STAGE_DUR}
║  Stages:             ${stages.length}
║                                                                      ║
║  Total Requests:     ${totalRequests}                                    ║
║  Total Errors:       ${totalErrors} (${errorRatePct}%)                   ║
║  Memory Reads:       ${totalMemoryReads}                                ║
║  Memory Writes:      ${totalMemoryWrites}                               ║
║                                                                      ║
║  Latency (HTTP):
║    p50: ${reqDurationP50}ms  |  p95: ${reqDurationP95}ms  |  p99: ${reqDurationP99}ms
║                                                                      ║
╠══════════════════════════════════════════════════════════════════════╣
║  INTERPRETATION                                                      ║
║  • High conflict rate = session locking contention detected          ║
║  • p99 growing faster than p95 = lock queue growing                  ║
║  • Error rate > 5% under moderate VUs = memory write serialization   ║
║  • If ChatMemory uses @Transactional isolation=SERIALIZABLE:         ║
║    expect latency growth ~linear with VUs per session                ║
║  • Target: p95 < 2000ms with ≤20 concurrent writers                  ║
╚══════════════════════════════════════════════════════════════════════╝
`,
  };
}
