/**
 * k6 Load Test Suite for Spring AI RAG Service
 * ============================================
 * Comprehensive performance benchmark covering:
 *   - Health & Metrics (observability)
 *   - Collection CRUD (data management)
 *   - Document Ingestion (chunking + embedding)
 *   - Hybrid Search (vector + fulltext fusion)
 *   - Chat / RAG Pipeline (end-to-end)
 *   - SSE Streaming (chat stream)
 *   - Concurrent burst (resilience)
 *
 * Prerequisites:
 *   1. Start the server: mvn spring-boot:run -pl spring-ai-rag-core
 *   2. Install k6: brew install k6  (macOS) or see https://k6.io/docs/getting-started/installation/
 *   3. Run: k6 run scripts/k6-load-test.js
 *      - Single run:   k6 run scripts/k6-load-test.js
 *      - Smoke test:   k6 run -e K6_PROFILE=smoke scripts/k6-load-test.js
 *      - Load test:    k6 run -e K6_PROFILE=load scripts/k6-load-test.js
 *      - Stress test:  k6 run -e K6_PROFILE=stress scripts/k6-load-test.js
 *      - With tags:     k6 run -e K6_ENV=prod -e K6_PROFILE=load scripts/k6-load-test.js
 *
 * Environment Variables:
 *   BASE_URL         – API base URL (default: http://localhost:8081)
 *   API_KEY          – API key for auth (default: test-api-key)
 *   K6_PROFILE       – Test profile: smoke|load|stress (default: load)
 *   K6_ENV           – Environment label (prod|staging|dev) (default: dev)
 *   MAX_VUS          – Max virtual users for stress (default: 200)
 *   THRESHOLD_P95    – Max acceptable p95 latency in ms (default: 500)
 */

import http from 'k6/http';
import { check, sleep, group, fail } from 'k6';
import { Rate, Trend, Counter, Gauge } from 'k6/metrics';

// ─────────────────────────────────────────────────────────────────
// Configuration
// ─────────────────────────────────────────────────────────────────

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const API_KEY = __ENV.API_KEY || 'test-api-key';
const PROFILE = __ENV.K6_PROFILE || 'load';
const ENV_LABEL = __ENV.K6_ENV || 'dev';
const THRESHOLD_P95 = parseInt(__ENV.THRESHOLD_P95 || '500');

// Profile definitions
const PROFILES = {
  smoke: {
    vus: 1,
    duration: '10s',
    warmup: '0s',
    thresholds: { http_req_duration: ['p(95)<800'] },
  },
  load: {
    vus: 20,
    duration: '60s',
    warmup: '5s',
    thresholds: { http_req_duration: [`p(95)<${THRESHOLD_P95}`] },
  },
  stress: {
    vus: parseInt(__ENV.MAX_VUS) || 200,
    duration: '120s',
    warmup: '10s',
    // Stress: ramp up → steady → ramp down, no hard failure threshold
    thresholds: { http_req_duration: ['p(95)<2000'] },
  },
};

const cfg = PROFILES[PROFILE] || PROFILES.load;

// ─────────────────────────────────────────────────────────────────
// Custom Metrics
// ─────────────────────────────────────────────────────────────────

const httpReqDuration = new Trend('http_req_duration', true);  // true = rates show as time
const searchLatency   = new Trend('rag_search_latency');
const chatNonStreamLatency = new Trend('rag_chat_nonstream_latency');  // Non-streaming /chat/ask
const chatStreamLatency    = new Trend('rag_chat_stream_latency');      // Streaming /chat/stream (total time to receive all SSE chunks)
const embedLatency   = new Trend('rag_embed_latency');
const httpErrors     = new Counter('http_errors_total');
const apiCallsTotal  = new Counter('api_calls_total');
const cacheHitRate   = new Gauge('cache_hit_rate');

// ─────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────

const HEADERS_JSON = {
  'Content-Type': 'application/json',
  'X-API-Key': API_KEY,
};

const HEADERS_SSE = {
  'Accept': 'text/event-stream',
  'X-API-Key': API_KEY,
};

function req(method, url, body, headers, tags) {
  const res = http.request(method, url, body, { headers, tags: tags || {} });
  apiCallsTotal.add(1);
  if (res.status >= 400) {
    httpErrors.add(1, { status: res.status, url });
  }
  return res;
}

function get(url, params, tags) {
  return req('GET', url, null, { ...HEADERS_JSON, ...params }, tags);
}

function post(url, body, headers, tags) {
  return req('POST', url, JSON.stringify(body), { ...HEADERS_JSON, ...headers }, tags);
}

function del(url, headers, tags) {
  return req('DELETE', url, null, { ...HEADERS_JSON, ...headers }, tags);
}

// ─────────────────────────────────────────────────────────────────
// Test Data – shared across VUs via global
// ─────────────────────────────────────────────────────────────────

// A pre-created collection ID (set by setup())
let COLLECTION_ID = null;
let COLLECTION_NAME = `perf-col-${Date.now()}`;

// A pre-created document ID with embedded chunks
let DOC_ID = null;

// A chat session ID
let SESSION_ID = `k6-session-${Date.now()}`;

// Search query
const SEARCH_QUERY = 'What is RAG architecture?';

// Chat request body
const CHAT_BODY = {
  query: 'Explain the hybrid retrieval approach.',
  collectionId: () => COLLECTION_ID,
  sessionId: () => SESSION_ID,
  retrievalConfig: {
    vectorWeight: 0.7,
    fulltextWeight: 0.3,
    topK: 5,
  },
};

// ─────────────────────────────────────────────────────────────────
// SETUP – runs once before all VUs
// ─────────────────────────────────────────────────────────────────

export function setup() {
  console.log(`\n[setup] Starting k6 load test — profile=${PROFILE} env=${ENV_LABEL} base=${BASE_URL}`);

  // 1. Wait for server readiness
  const maxRetries = 30;
  for (let i = 0; i < maxRetries; i++) {
    const r = http.get(`${BASE_URL}/api/v1/rag/health`, { headers: HEADERS_JSON });
    if (r.status === 200) {
      console.log('[setup] Server is ready ✅');
      break;
    }
    if (i === maxRetries - 1) {
      console.error('[setup] Server did not become ready in time. Aborting.');
      // Don't fail – let the test run so we get clear error metrics
    }
    sleep(1);
  }

  // 2. Create a test collection
  const colRes = post(
    `${BASE_URL}/api/v1/rag/collections`,
    { name: COLLECTION_NAME, description: 'k6 load test collection', metadata: { test: true } },
    {},
    { name: 'setup_create_collection' }
  );
  if (colRes.status !== 201 && colRes.status !== 200) {
    console.warn(`[setup] Collection creation failed (${colRes.status}): ${colRes.body}`);
  } else {
    const colBody = JSON.parse(colRes.body);
    COLLECTION_ID = colBody.data?.id || colBody.id;
    console.log(`[setup] Collection created: ${COLLECTION_ID}`);
  }

  // 3. Create a test document with content (for search/chat tests)
  if (COLLECTION_ID) {
    const docBody = {
      title: 'k6 Load Test Document',
      content: `RAG (Retrieval-Augmented Generation) combines retrieval and generation.
        Spring AI provides a clean abstraction for building RAG pipelines.
        Hybrid search blends dense vector retrieval with sparse full-text search.
        PgVector stores embeddings alongside metadata in PostgreSQL.
        Query rewriting expands user intent using LLM prompting.
        Re-ranking reorders retrieved chunks for relevance to the query.
        Chat memory persists multi-turn conversation context.`,
      collectionId: COLLECTION_ID,
      metadata: { source: 'k6-load-test', category: 'test' },
    };
    const docRes = post(`${BASE_URL}/api/v1/rag/documents`, docBody, {}, { name: 'setup_create_document' });
    if (docRes.status !== 201 && docRes.status !== 200) {
      console.warn(`[setup] Document creation failed (${docRes.status}): ${docRes.body}`);
    } else {
      const docJson = JSON.parse(docRes.body);
      DOC_ID = docJson.data?.id || docJson.id;
      console.log(`[setup] Document created: ${DOC_ID}`);

      // 4. Embed the document (synchronous)
      if (DOC_ID) {
        const embedRes = post(
          `${BASE_URL}/api/v1/rag/documents/${DOC_ID}/embed`,
          { force: false },
          {},
          { name: 'setup_embed_document' }
        );
        if (embedRes.status !== 200 && embedRes.status !== 202) {
          console.warn(`[setup] Embed failed (${embedRes.status}): ${embedRes.body}`);
        } else {
          console.log(`[setup] Document embedded successfully`);
        }
      }
    }
  }

  console.log(`[setup] Complete — collection=${COLLECTION_ID} doc=${DOC_ID} session=${SESSION_ID}\n`);

  return {
    collectionId: COLLECTION_ID,
    documentId: DOC_ID,
    sessionId: SESSION_ID,
    collectionName: COLLECTION_NAME,
  };
}

// ─────────────────────────────────────────────────────────────────
// TEARDOWN – runs once after all VUs finish
// ─────────────────────────────────────────────────────────────────

export function teardown(data) {
  console.log('\n[teardown] Cleaning up test data...');
  const { collectionId, sessionId } = data;

  // Delete chat history
  if (sessionId) {
    const hr = del(`${BASE_URL}/api/v1/rag/chat/history/${sessionId}`, {}, { name: 'teardown_clear_history' });
    if (hr.status === 200 || hr.status === 204) {
      console.log(`[teardown] Chat history cleared: ${sessionId}`);
    }
  }

  // Delete collection (cascades to documents)
  if (collectionId) {
    const cr = del(`${BASE_URL}/api/v1/rag/collections/${collectionId}`, {}, { name: 'teardown_delete_collection' });
    if (cr.status === 200 || cr.status === 204) {
      console.log(`[teardown] Collection deleted: ${collectionId}`);
    } else {
      console.warn(`[teardown] Collection deletion returned ${cr.status}`);
    }
  }

  console.log('[teardown] Done.\n');
}

// ─────────────────────────────────────────────────────────────────
// Options
// ─────────────────────────────────────────────────────────────────

export const options = {
  // Build stages: ramp up → sustain → ramp down
  stages: PROFILE === 'stress'
    ? [
        { duration: '30s', target: Math.floor(cfg.vus * 0.25) },
        { duration: '30s', target: Math.floor(cfg.vus * 0.50) },
        { duration: '30s', target: Math.floor(cfg.vus * 0.75) },
        { duration: '30s', target: cfg.vus },
      ]
    : PROFILE === 'load'
    ? [
        { duration: cfg.warmup, target: Math.floor(cfg.vus * 0.5) },
        { duration: cfg.duration, target: cfg.vus },
      ]
    : PROFILE === 'smoke'
    ? [
        { duration: cfg.duration, target: cfg.vus },
      ]
    : [
        { duration: cfg.duration, target: cfg.vus },
      ],

  vus: cfg.vus,

  thresholds: {
    // All HTTP requests should finish within threshold
    http_req_duration: ['p(95)<' + THRESHOLD_P95],
    // No more than 1% errors
    http_errors_total: ['rate<0.01'],
    // Search specific
    rag_search_latency: ['p(95)<1000', 'p(99)<2000', 'avg<500'],
    // Chat non-stream specific (synchronous, waits for full LLM response)
    rag_chat_nonstream_latency: ['p(95)<5000', 'p(99)<10000', 'avg<2000'],
    // Chat stream specific (SSE, total time to receive all chunks)
    rag_chat_stream_latency: ['p(95)<8000', 'p(99)<15000', 'avg<3000'],
    // Embed specific
    rag_embed_latency: ['p(95)<3000', 'p(99)<8000', 'avg<1500'],
    // Availability
    api_calls_total: [],
  },

  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

// ─────────────────────────────────────────────────────────────────
// Default function – executed by each VU in each iteration
// ─────────────────────────────────────────────────────────────────

export default function(data) {
  // Run test groups in sequence per VU iteration
  runHealthChecks(data);
  runCollectionRead(data);
  runCollectionWrite(data);
  runDocumentCRUD(data);
  runHybridSearch(data);
  runChatAsk(data);
  runChatStream(data);
  runAbTests(data);
  runAlertsAndFeedback(data);
  runMetricsAndCache(data);

  // Small think time between iterations
  sleep(0.5 + Math.random() * 0.5);
}

// ─────────────────────────────────────────────────────────────────
// Test Groups
// ─────────────────────────────────────────────────────────────────

function runHealthChecks(data) {
  group('Health & Observability', () => {
    // Basic health
    const hr = get(`${BASE_URL}/api/v1/rag/health`, HEADERS_JSON, { name: 'health' });
    check(hr, {
      'health returns 200': r => r.status === 200,
      'health has status ok': r => JSON.parse(r.body).status === 'ok' || JSON.parse(r.body).data?.status === 'ok',
    });

    // Component health
    const chr = get(`${BASE_URL}/api/v1/rag/health/components`, HEADERS_JSON, { name: 'health_components' });
    check(chr, {
      'health/components returns 200': r => r.status === 200,
    });

    // Cache stats
    const cshr = get(`${BASE_URL}/api/v1/rag/cache/stats`, HEADERS_JSON, { name: 'cache_stats' });
    check(cshr, {
      'cache/stats returns 200': r => r.status === 200,
    });

    // Metrics overview
    const mr = get(`${BASE_URL}/api/v1/rag/metrics`, HEADERS_JSON, { name: 'metrics' });
    check(mr, {
      'metrics returns 200': r => r.status === 200,
    });
  });
}

function runCollectionRead(data) {
  group('Collection Read', () => {
    // List collections
    const lr = get(`${BASE_URL}/api/v1/rag/collections`, HEADERS_JSON, { name: 'collection_list' });
    check(lr, {
      'collection list returns 200': r => r.status === 200,
    });

    // Get collection by ID
    if (data.collectionId) {
      const gr = get(`${BASE_URL}/api/v1/rag/collections/${data.collectionId}`, HEADERS_JSON, { name: 'collection_get' });
      check(gr, {
        'collection get returns 200': r => r.status === 200,
        'collection get returns correct id': r => {
          try {
            const b = JSON.parse(r.body);
            return (b.id || b.data?.id) === data.collectionId;
          } catch { return false; }
        },
      });
    }
  });
}

function runDocumentCRUD(data) {
  group('Document CRUD', () => {
    // List documents
    const lr = get(`${BASE_URL}/api/v1/rag/documents`, HEADERS_JSON, { name: 'document_list' });
    check(lr, {
      'document list returns 200': r => r.status === 200,
    });

    // List documents with pagination
    const pr = get(`${BASE_URL}/api/v1/rag/documents?page=0&size=10`, HEADERS_JSON, { name: 'document_list_paginated' });
    check(pr, {
      'document list (paginated) returns 200': r => r.status === 200,
    });

    // Document stats
    const sr = get(`${BASE_URL}/api/v1/rag/documents/stats`, HEADERS_JSON, { name: 'document_stats' });
    check(sr, {
      'document stats returns 200': r => r.status === 200,
    });

    // Create a transient document (per-VU unique content to avoid collisions)
    const docId = `k6-doc-${__VU}-${Date.now()}`;
    const docRes = post(`${BASE_URL}/api/v1/rag/documents`, {
      title: `Load Test Doc ${docId}`,
      content: `This is a k6 load test document created by VU ${__VU} at ${new Date().toISOString()}.
        RAG systems use hybrid search combining dense vectors and sparse full-text.
        The vector store is backed by PostgreSQL with the pgvector extension.
        Embedding models convert text into fixed-dimensional floating point arrays.`,
      collectionId: data.collectionId || null,
      metadata: { vu: __VU, test: true },
    }, {}, { name: 'document_create' });

    let localDocId = null;
    const created = check(docRes, {
      'document create returns 201': r => r.status === 201,
      'document create returns id': r => {
        try {
          const b = JSON.parse(r.body);
          localDocId = b.id || b.data?.id;
          return !!localDocId;
        } catch { return false; }
      },
    });

    if (created && localDocId) {
      // Get document
      const gr = get(`${BASE_URL}/api/v1/rag/documents/${localDocId}`, HEADERS_JSON, { name: 'document_get' });
      check(gr, { 'document get returns 200': r => r.status === 200 });

      // Embed document (async, fast)
      const er = post(`${BASE_URL}/api/v1/rag/documents/${localDocId}/embed`, { force: false }, {}, { name: 'document_embed' });
      const embedOk = check(er, {
        'document embed returns 200/202': r => r.status === 200 || r.status === 202,
      });

      if (embedOk) {
        embedLatency.add(er.timings.duration);
      }

      // Delete document
      const dr = del(`${BASE_URL}/api/v1/rag/documents/${localDocId}`, HEADERS_JSON, { name: 'document_delete' });
      check(dr, {
        'document delete returns 200/204': r => r.status === 200 || r.status === 204,
      });
    }
  });
}

function runHybridSearch(data) {
  group('Hybrid Search', () => {
    // GET search
    const getSearchRes = get(
      `${BASE_URL}/api/v1/rag/search?query=${encodeURIComponent(SEARCH_QUERY)}&topK=5&vectorWeight=0.7&fulltextWeight=0.3`,
      HEADERS_JSON,
      { name: 'search_get' }
    );
    const getSearchOk = check(getSearchRes, {
      'search GET returns 200': r => r.status === 200,
      'search GET returns results': r => {
        try {
          const b = JSON.parse(r.body);
          return (b.results || b.data?.results || []).length > 0;
        } catch { return false; }
      },
    });

    if (getSearchOk) {
      searchLatency.add(getSearchRes.timings.duration);
    }

    // POST search (with config)
    const postSearchRes = post(`${BASE_URL}/api/v1/rag/search`,
      {
        query: SEARCH_QUERY,
        collectionId: data.collectionId || null,
        topK: 10,
        vectorWeight: 0.7,
        fulltextWeight: 0.3,
        retrievalConfig: {
          enableHybridSearch: true,
          vectorTopK: 20,
          fulltextTopK: 20,
        },
      },
      {},
      { name: 'search_post' }
    );
    const postSearchOk = check(postSearchRes, {
      'search POST returns 200': r => r.status === 200,
      'search POST returns results': r => {
        try {
          const b = JSON.parse(r.body);
          return (b.results || b.data?.results || []).length > 0;
        } catch { return false; }
      },
    });

    if (postSearchOk) {
      searchLatency.add(postSearchRes.timings.duration);
    }

    // Search with strict weight bounds (boundary test)
    const boundaryRes = post(`${BASE_URL}/api/v1/rag/search`,
      { query: SEARCH_QUERY, topK: 3, vectorWeight: 1.0, fulltextWeight: 0.0 },
      {},
      { name: 'search_boundary_weights' }
    );
    check(boundaryRes, {
      'search boundary weights returns 200': r => r.status === 200,
    });
  });
}

function runChatAsk(data) {
  group('Chat RAG Pipeline', () => {
    // Regular chat ask (non-streaming)
    const chatRes = post(`${BASE_URL}/api/v1/rag/chat/ask`,
      {
        query: 'Explain RAG architecture.',
        collectionId: data.collectionId || null,
        sessionId: data.sessionId || 'k6-session',
        retrievalConfig: { topK: 3, vectorWeight: 0.5, fulltextWeight: 0.5 },
      },
      {},
      { name: 'chat_ask' }
    );

    const chatOk = check(chatRes, {
      'chat/ask returns 200': r => r.status === 200,
      'chat/ask returns answer': r => {
        try {
          const b = JSON.parse(r.body);
          return !!(b.answer || b.data?.answer);
        } catch { return false; }
      },
      'chat/ask returns sessionId': r => {
        try {
          const b = JSON.parse(r.body);
          return !!(b.sessionId || b.data?.sessionId);
        } catch { return false; }
      },
    });

    if (chatOk) {
      chatNonStreamLatency.add(chatRes.timings.duration);
    }
  });
}

function runChatStream(data) {
  group('Chat SSE Stream', () => {
    // SSE streaming chat — sends request and reads first few chunks
    const chatBody = JSON.stringify({
      query: 'What is hybrid search?',
      collectionId: data.collectionId || null,
      sessionId: data.sessionId || 'k6-session',
      retrievalConfig: { topK: 3 },
    });

    const res = http.post(
      `${BASE_URL}/api/v1/rag/chat/stream`,
      chatBody,
      {
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream',
          'X-API-Key': API_KEY,
        },
        timeout: '30s',
      }
    );

    const streamOk = check(res, {
      'chat/stream returns 200': r => r.status === 200,
      'chat/stream is SSE': r => r.headers['Content-Type'] && r.headers['Content-Type'].includes('text/event-stream'),
      'chat/stream has body': r => r.body && r.body.length > 0,
    });

    if (streamOk) {
      // Total SSE round-trip: request sent + all chunks received
      chatStreamLatency.add(res.timings.duration);
    }

    // Validate SSE chunks contain expected fields (id, event, data:)
    if (res.status === 200 && res.body) {
      const lines = res.body.split('\n');
      let dataChunks = 0;
      for (const line of lines) {
        if (line.startsWith('data:')) dataChunks++;
      }
      check(res, {
        'chat/stream has SSE data chunks': () => dataChunks > 0,
      });
    }
  });
}

function runMetricsAndCache(data) {
  group('Metrics & Cache', () => {
    // Model metrics (if multi-model is enabled)
    const mmr = get(`${BASE_URL}/api/v1/rag/models`, HEADERS_JSON, { name: 'model_list' });
    check(mmr, {
      'model list returns 200': r => r.status === 200 || r.status === 404,  // 404 if multi-model not configured
    });

    // Model metrics
    const mmr2 = get(`${BASE_URL}/api/v1/rag/metrics/models`, HEADERS_JSON, { name: 'model_metrics' });
    check(mmr2, {
      'model metrics returns 200': r => r.status === 200 || r.status === 404,
    });

    // Chat history
    const chr = get(`${BASE_URL}/api/v1/rag/chat/history/${data.sessionId || 'k6-session'}`, HEADERS_JSON, { name: 'chat_history' });
    check(chr, {
      'chat history returns 200': r => r.status === 200,
    });

    // Cache stats — parse hit rate
    const cshr = get(`${BASE_URL}/api/v1/rag/cache/stats`, HEADERS_JSON, { name: 'cache_stats' });
    if (cshr.status === 200) {
      try {
        const b = JSON.parse(cshr.body);
        const hitRate = b.hitRate || b.data?.hitRate || 0;
        cacheHitRate.add(hitRate);
      } catch { /* ignore */ }
    }
  });
}

// ─────────────────────────────────────────────────────────────────
// Collections Write – create / update / delete collections
// ─────────────────────────────────────────────────────────────────

function runCollectionWrite(data) {
  group('Collection Write', () => {
    // Create a transient collection (per-VU unique to avoid collisions)
    const colName = `k6-col-${__VU}-${Date.now()}`;
    const cr = post(`${BASE_URL}/api/v1/rag/collections`, {
      name: colName,
      description: `k6 load test collection by VU ${__VU}`,
      metadata: { vu: __VU, test: true },
    }, {}, { name: 'collection_create' });

    let createdId = null;
    const created = check(cr, {
      'collection create returns 201': r => r.status === 201,
      'collection create returns id': r => {
        try {
          const b = JSON.parse(r.body);
          createdId = b.data?.id || b.id;
          return !!createdId;
        } catch { return false; }
      },
    });

    if (created && createdId) {
      // Get the created collection
      const gr = get(`${BASE_URL}/api/v1/rag/collections/${createdId}`, HEADERS_JSON, { name: 'collection_get' });
      check(gr, { 'collection get returns 200': r => r.status === 200 });

      // Update the collection
      const ur = post(`${BASE_URL}/api/v1/rag/collections/${createdId}`, {
        name: colName + '-updated',
        description: 'updated by k6 load test',
      }, {}, { name: 'collection_update' });
      check(ur, {
        'collection update returns 200': r => r.status === 200 || r.status === 201,
      });

      // Delete the collection
      const dr = del(`${BASE_URL}/api/v1/rag/collections/${createdId}`, HEADERS_JSON, { name: 'collection_delete' });
      check(dr, {
        'collection delete returns 200/204': r => r.status === 200 || r.status === 204,
      });
    }
  });
}

// ─────────────────────────────────────────────────────────────────
// A/B Experiments – list / create experiments
// ─────────────────────────────────────────────────────────────────

function runAbTests(data) {
  group('A/B Experiments', () => {
    // List experiments
    const lr = get(`${BASE_URL}/api/v1/rag/ab-tests`, HEADERS_JSON, { name: 'abtest_list' });
    check(lr, {
      'abtest list returns 200': r => r.status === 200,
    });

    // Create an experiment
    const expName = `k6-exp-${__VU}-${Date.now()}`;
    const cr = post(`${BASE_URL}/api/v1/rag/ab-tests`, {
      name: expName,
      description: 'k6 load test experiment',
      modelA: 'deepseek-chat',
      modelB: 'gpt-4o',
      trafficSplit: 0.5,
      enabled: false,
    }, {}, { name: 'abtest_create' });

    let expId = null;
    const created = check(cr, {
      'abtest create returns 201': r => r.status === 201 || r.status === 200,
      'abtest create returns id': r => {
        try {
          const b = JSON.parse(r.body);
          expId = b.data?.id || b.id;
          return !!expId;
        } catch { return false; }
      },
    });

    if (created && expId) {
      // Get the experiment
      const gr = get(`${BASE_URL}/api/v1/rag/ab-tests/${expId}`, HEADERS_JSON, { name: 'abtest_get' });
      check(gr, { 'abtest get returns 200': r => r.status === 200 });

      // Start the experiment
      const sr = post(`${BASE_URL}/api/v1/rag/ab-tests/${expId}/start`, {}, {}, { name: 'abtest_start' });
      check(sr, {
        'abtest start returns 200': r => r.status === 200 || r.status === 201,
      });

      // Stop the experiment
      const pr = post(`${BASE_URL}/api/v1/rag/ab-tests/${expId}/stop`, {}, {}, { name: 'abtest_stop' });
      check(pr, {
        'abtest stop returns 200': r => r.status === 200 || r.status === 201,
      });

      // Get experiment results
      const rr = get(`${BASE_URL}/api/v1/rag/ab-tests/${expId}/results`, HEADERS_JSON, { name: 'abtest_results' });
      check(rr, {
        'abtest results returns 200': r => r.status === 200,
      });
    }
  });
}

// ─────────────────────────────────────────────────────────────────
// Alerts, Feedback & Evaluation
// ─────────────────────────────────────────────────────────────────

function runAlertsAndFeedback(data) {
  group('Alerts & Feedback', () => {
    // List alerts
    const ar = get(`${BASE_URL}/api/v1/rag/alerts`, HEADERS_JSON, { name: 'alert_list' });
    check(ar, {
      'alert list returns 200': r => r.status === 200,
    });

    // Submit user feedback
    const fr = post(`${BASE_URL}/api/v1/rag/feedback`, {
      query: 'k6 load test query',
      response: 'k6 load test response',
      relevanceScore: 4,
      helpful: true,
      sessionId: data.sessionId || 'k6-session',
    }, {}, { name: 'feedback_submit' });
    check(fr, {
      'feedback submit returns 200': r => r.status === 200 || r.status === 201,
    });

    // Retrieval evaluation (if documents exist)
    if (data.documentId) {
      const er = post(`${BASE_URL}/api/v1/rag/evaluate`, {
        collectionId: data.collectionId,
        topK: 5,
      }, {}, { name: 'evaluate' });
      check(er, {
        'evaluate returns 200': r => r.status === 200 || r.status === 404,
      });
    }

    // SLO compliance endpoint
    const sr = get(`${BASE_URL}/api/v1/rag/metrics/slo`, HEADERS_JSON, { name: 'slo_compliance' });
    check(sr, {
      'slo compliance returns 200': r => r.status === 200,
    });

    // Slow queries endpoint
    const sqr = get(`${BASE_URL}/api/v1/rag/metrics/slow-queries`, HEADERS_JSON, { name: 'slow_queries' });
    check(sqr, {
      'slow queries returns 200': r => r.status === 200,
    });

    // Client errors
    const cer = get(`${BASE_URL}/api/v1/rag/client-errors/count`, HEADERS_JSON, { name: 'client_errors_count' });
    check(cer, {
      'client errors count returns 200': r => r.status === 200,
    });
  });
}

// ─────────────────────────────────────────────────────────────────
// Handle Summary
// ─────────────────────────────────────────────────────────────────

export function handleSummary(data) {
  const env = ENV_LABEL;
  const profile = PROFILE;
  const passRate = data.metrics.http_errors_total
    ? 1 - (data.metrics.http_errors_total.values?.rate || 0)
    : 1.0;

  return {
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
    'stdout-json': JSON.stringify({
      test: 'spring-ai-rag-k6',
      profile,
      env,
      timestamp: new Date().toISOString(),
      passRate: (passRate * 100).toFixed(2) + '%',
      thresholds: cfg.thresholds,
      metrics: {
        http_req_duration_p95: data.metrics.http_req_duration?.values?.['p(95)'] || 0,
        http_req_duration_avg: data.metrics.http_req_duration?.values?.avg || 0,
        http_req_duration_max: data.metrics.http_req_duration?.values?.max || 0,
        http_errors_rate: data.metrics.http_errors_total?.values?.rate || 0,
        rag_search_latency_p95: data.metrics.rag_search_latency?.values?.['p(95)'] || 0,
        rag_chat_nonstream_latency_p95: data.metrics.rag_chat_nonstream_latency?.values?.['p(95)'] || 0,
        rag_chat_stream_latency_p95: data.metrics.rag_chat_stream_latency?.values?.['p(95)'] || 0,
        rag_embed_latency_p95: data.metrics.rag_embed_latency?.values?.['p(95)'] || 0,
        api_calls_total: data.metrics.api_calls_total?.values?.count || 0,
      },
    }),
  };
}

function textSummary(data, opts) {
  const duration = (data.state.testRunDurationMs / 1000).toFixed(1);
  const passRate = data.metrics.http_errors_total
    ? ((1 - (data.metrics.http_errors_total.values?.rate || 0)) * 100).toFixed(2)
    : '100.00';

  const http_p95 = (data.metrics.http_req_duration?.values?.['p(95)'] / 1000).toFixed(2);
  const http_avg = (data.metrics.http_req_duration?.values?.avg / 1000).toFixed(2);
  const http_max = (data.metrics.http_req_duration?.values?.max / 1000).toFixed(2);

  const search_p95 = (data.metrics.rag_search_latency?.values?.['p(95)'] / 1000).toFixed(2);
  const chat_nonstream_p95 = (data.metrics.rag_chat_nonstream_latency?.values?.['p(95)'] / 1000).toFixed(2);
  const chat_stream_p95    = (data.metrics.rag_chat_stream_latency?.values?.['p(95)'] / 1000).toFixed(2);
  const embed_p95  = (data.metrics.rag_embed_latency?.values?.['p(95)'] / 1000).toFixed(2);

  const total = data.metrics.api_calls_total?.values?.count || 0;

  const lines = [
    '',
    '╔══════════════════════════════════════════════════════════════╗',
    '║           Spring AI RAG — k6 Load Test Summary              ║',
    '╠══════════════════════════════════════════════════════════════╣',
    `║  Profile   : ${profile.padEnd(47)}║`,
    `║  Env       : ${env.padEnd(47)}║`,
    `║  Duration  : ${duration}s`.padEnd(57) + '║',
    `║  VUs max   : ${data.run.config?.vus || cfg.vus}`.padEnd(57) + '║',
    '╠══════════════════════════════════════════════════════════════╣',
    '║  HTTP Latency (all requests)                                ║',
    `║    p(95)   : ${http_p95}s`.padEnd(57) + '║',
    `║    avg     : ${http_avg}s`.padEnd(57) + '║',
    `║    max     : ${http_max}s`.padEnd(57) + '║',
    '╠══════════════════════════════════════════════════════════════╣',
    '║  RAG Pipeline Latency (p95)                                 ║',
    `║    search  : ${search_p95}s`.padEnd(57) + '║',
    `║    chat(ns): ${chat_nonstream_p95}s`.padEnd(57) + '║',
    `║    chat(ss): ${chat_stream_p95}s`.padEnd(57) + '║',
    `║    embed   : ${embed_p95}s`.padEnd(57) + '║',
    '╠══════════════════════════════════════════════════════════════╣',
    '║  Quality                                                  ║',
    `║    pass rate: ${passRate}%`.padEnd(57) + '║',
    `║    total req: ${total}`.padEnd(57) + '║',
    `║    errors   : ${(data.metrics.http_errors_total?.values?.count || 0)}`.padEnd(57) + '║',
    '╚══════════════════════════════════════════════════════════════╝',
    '',
  ];
  return lines.join('\n');
}
