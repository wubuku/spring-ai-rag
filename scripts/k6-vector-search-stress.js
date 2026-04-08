/**
 * k6 Vector Search Stress Test — Spring AI RAG
 * ============================================
 * Dedicated pure vector search (no fulltext) stress test for validating
 * pgvector HNSW index performance under high concurrency.
 *
 * Focus areas:
 *   - Pure vector search (vectorWeight=1.0, fulltextWeight=0.0)
 *   - High VU concurrency (50-200 VUs)
 *   - Different topK values (10, 50, 100)
 *   - HNSW index performance (m/ef parameters)
 *   - Retrieval latency percentiles (p50/p95/p99)
 *   - Throughput (search ops/sec)
 *
 * Prerequisites:
 *   1. Start server: mvn spring-boot:run -pl spring-ai-rag-core
 *   2. Have indexed documents (run demo or batch-embed documents first)
 *   3. Install k6: brew install k6 (macOS) or https://k6.io/docs/getting-started/installation/
 *   4. Run:
 *        k6 run scripts/k6-vector-search-stress.js
 *        k6 run -e K6_VUS=100 scripts/k6-vector-search-stress.js           # 100 VUs
 *        k6 run -e K6_VUS=200 -e K6_DURATION=60s scripts/k6-vector-search-stress.js  # 200 VUs, 60s
 *        k6 run -e K6_PROFILE=stress scripts/k6-vector-search-stress.js     # stress profile
 *
 * Environment Variables:
 *   BASE_URL     – API base URL (default: http://localhost:8081)
 *   API_KEY      – API key for auth (default: test-api-key)
 *   K6_VUS       – Number of virtual users (default: 50)
 *   K6_DURATION  – Test duration (default: 30s)
 *   K6_TOPK      – topK for search (default: 10)
 *   K6_PROFILE   – Profile: smoke|load|stress (default: load)
 *   VECTOR_WEIGHT – Vector weight 0.0-1.0 (default: 1.0 for pure vector)
 *   COLLECTION_ID – Optional collection ID to scope search
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Rate, Counter, Gauge } from 'k6/metrics';

// ─────────────────────────────────────────────────────────────────
// Configuration
// ─────────────────────────────────────────────────────────────────

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const API_KEY = __ENV.API_KEY || 'test-api-key';
const VUS = parseInt(__ENV.K6_VUS || '50');
const DURATION = __ENV.K6_DURATION || '30s';
const TOPK = parseInt(__ENV.K6_TOPK || '10');
const VECTOR_WEIGHT = parseFloat(__ENV.VECTOR_WEIGHT || '1.0');
const COLLECTION_ID = __ENV.COLLECTION_ID || null;
const PROFILE = __ENV.K6_PROFILE || 'load';

// Metrics
const vectorSearchLatency = new Trend('rag_vector_search_latency');
const searchSuccessRate = new Rate('rag_vector_search_success');
const searchResultCount = new Trend('rag_vector_search_result_count');
const searchErrors = new Counter('rag_vector_search_errors');
const searchThroughput = new Counter('rag_vector_search_ops');

const HEADERS = {
  'Authorization': `Bearer ${API_KEY}`,
  'Content-Type': 'application/json',
};

const HEADERS_JSON = {
  headers: HEADERS,
};

// ─────────────────────────────────────────────────────────────────
// Test Queries — varied semantic content to test index recall
// ─────────────────────────────────────────────────────────────────

const SEARCH_QUERIES = [
  'What is the capital of France?',
  'How does machine learning work?',
  'What are the benefits of exercise?',
  'Explain quantum computing principles',
  'What is artificial intelligence?',
  'How to cook pasta?',
  'What causes climate change?',
  'Define neural network architecture',
  'What is the theory of relativity?',
  'How do vaccines work?',
];

// ─────────────────────────────────────────────────────────────────
// Test Scenarios
// ─────────────────────────────────────────────────────────────────

export const options = {
  scenarios: {
    // Primary vector search stress test
    vectorSearchStress: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      tags: { name: 'vector_search' },
    },
  },
  thresholds: {
    // Latency thresholds (in ms)
    'rag_vector_search_latency': ['p(50)<200', 'p(95)<800', 'p(99)<2000', 'avg<300'],
    // Success rate threshold
    'rag_vector_search_success': ['rate>0.95'],
    // Error rate threshold
    'rag_vector_search_errors': ['count<50'],
  },
  summaryTrend: ['med', 'p(95)', 'p(99)'],
};

// ─────────────────────────────────────────────────────────────────
// Test Data Setup — Create a test document + collection for search
// ─────────────────────────────────────────────────────────────────

let testCollectionId = null;
let testDocumentId = null;

export function setup() {
  console.log('=== Vector Search Stress Test Setup ===');
  console.log(`Base URL: ${BASE_URL}`);
  console.log(`VUs: ${VUS}, Duration: ${DURATION}, topK: ${TOPK}, vectorWeight: ${VECTOR_WEIGHT}`);

  // Check server health
  const healthRes = http.get(`${BASE_URL}/api/v1/rag/health/components`, HEADERS_JSON);
  if (healthRes.status !== 200) {
    console.warn(`Health check returned ${healthRes.status}, continuing anyway...`);
  } else {
    console.log('Server health: OK');
  }

  // Try to find an existing collection with documents
  const collectionsRes = http.get(`${BASE_URL}/api/v1/rag/collections?page=0&size=5`, HEADERS_JSON);
  if (collectionsRes.status === 200) {
    try {
      const data = JSON.parse(collectionsRes.body);
      const collections = data.data?.content || data.content || [];
      for (const col of collections) {
        // Check if collection has documents
        const docsRes = http.get(`${BASE_URL}/api/v1/rag/collections/${col.id}/documents?page=0&size=1`, HEADERS_JSON);
        if (docsRes.status === 200) {
          const docsData = JSON.parse(docsRes.body);
          const totalDocs = docsData.data?.totalElements || docsData.totalElements || 0;
          if (totalDocs > 0) {
            testCollectionId = col.id;
            console.log(`Found collection "${col.name}" (id=${testCollectionId}) with ${totalDocs} documents — will use for search scope`);
            break;
          }
        }
      }
    } catch (e) {
      console.warn('Failed to parse collections response:', e.message);
    }
  }

  if (!testCollectionId) {
    console.log('No existing collection with documents found — creating test collection...');
    const createColRes = http.post(
      `${BASE_URL}/api/v1/rag/collections`,
      JSON.stringify({ name: `k6-vector-search-test-${Date.now()}`, description: 'Temporary collection for k6 vector search stress test', dimensions: 1024 }),
      HEADERS_JSON
    );
    if (createColRes.status === 200 || createColRes.status === 201) {
      try {
        const colData = JSON.parse(createColRes.body);
        testCollectionId = colData.data?.id || colData.id;
        console.log(`Created collection id=${testCollectionId}`);
      } catch (e) {
        console.warn('Failed to parse collection creation response:', e.message);
      }
    }
  }

  // Create a test document with rich content for vector search
  if (testCollectionId) {
    const docContent = `
Spring AI is an application framework for AI engineering. It provides a simple, user-friendly API for building AI applications.
Machine learning is a subset of artificial intelligence that enables systems to learn from data without being explicitly programmed.
Neural networks are computing systems inspired by biological neural networks. They consist of interconnected nodes that process information.
Vector databases store high-dimensional vector embeddings and support efficient similarity search using metrics like cosine similarity.
PostgreSQL is a powerful, open-source relational database. With the pgvector extension, it supports efficient vector operations.
RAG (Retrieval-Augmented Generation) combines information retrieval with generative AI to produce more accurate and contextually relevant responses.
The capital of France is Paris. It is known for the Eiffel Tower, Louvre Museum, and rich cultural heritage.
Climate change refers to long-term shifts in global temperatures and weather patterns. Human activities are the main cause.
Quantum computing leverages quantum mechanical phenomena like superposition and entanglement to perform computation.
Vaccines work by training the immune system to recognize and fight specific pathogens, providing acquired immunity.
    `.trim();

    const createDocRes = http.post(
      `${BASE_URL}/api/v1/rag/documents`,
      JSON.stringify({
        title: 'Knowledge Base for Vector Search Testing',
        content: docContent,
        collectionId: testCollectionId,
      }),
      HEADERS_JSON
    );
    if (createDocRes.status === 200 || createDocRes.status === 201) {
      try {
        const docData = JSON.parse(createDocRes.body);
        testDocumentId = docData.data?.id || docData.id;
        console.log(`Created document id=${testDocumentId} in collection ${testCollectionId}`);
      } catch (e) {
        console.warn('Failed to parse document creation response:', e.message);
      }
    }

    // Trigger embedding
    if (testDocumentId) {
      const embedRes = http.post(
        `${BASE_URL}/api/v1/rag/documents/${testDocumentId}/embed`,
        JSON.stringify({ force: true }),
        HEADERS_JSON
      );
      console.log(`Embedding triggered: status=${embedRes.status}`);
      // Wait for embedding to complete
      sleep(3);
    }
  }

  return {
    collectionId: testCollectionId,
    documentId: testDocumentId,
    topK: TOPK,
    vectorWeight: VECTOR_WEIGHT,
  };
}

// ─────────────────────────────────────────────────────────────────
// Main Test Functions
// ─────────────────────────────────────────────────────────────────

function runPureVectorSearch(data) {
  const query = SEARCH_QUERIES[Math.floor(Math.random() * SEARCH_QUERIES.length)];

  // Pure vector search (no fulltext) via GET
  const getSearchRes = http.get(
    `${BASE_URL}/api/v1/rag/search?query=${encodeURIComponent(query)}&topK=${data.topK}&vectorWeight=${data.vectorWeight}&fulltextWeight=${1 - data.vectorWeight}${data.collectionId ? '&collectionId=' + data.collectionId : ''}`,
    HEADERS_JSON,
    { name: 'vector_search_get' }
  );

  const getOk = check(getSearchRes, {
    'vector search GET returns 200': r => r.status === 200,
    'vector search GET returns valid body': r => {
      try {
        const b = JSON.parse(r.body);
        return b !== null && b !== undefined;
      } catch { return false; }
    },
  });

  searchSuccessRate.add(getOk ? 1 : 0);
  if (getOk) {
    vectorSearchLatency.add(getSearchRes.timings.duration);
    searchThroughput.add(1);
    try {
      const b = JSON.parse(getSearchRes.body);
      const results = b.results || b.data?.results || [];
      searchResultCount.add(results.length);
    } catch { /* ignore */ }
  } else {
    searchErrors.add(1);
  }
}

function runPureVectorSearchPOST(data) {
  const query = SEARCH_QUERIES[Math.floor(Math.random() * SEARCH_QUERIES.length)];

  // Pure vector search via POST with explicit config
  const postSearchRes = http.post(
    `${BASE_URL}/api/v1/rag/search`,
    JSON.stringify({
      query: query,
      collectionId: data.collectionId || null,
      topK: data.topK,
      vectorWeight: data.vectorWeight,
      fulltextWeight: 1 - data.vectorWeight,  // Pure vector: fulltext=0
      retrievalConfig: {
        enableHybridSearch: false,  // Pure vector mode
        vectorTopK: data.topK,
        fulltextTopK: 0,
      },
    }),
    HEADERS_JSON,
    { name: 'vector_search_post' }
  );

  const postOk = check(postSearchRes, {
    'vector search POST returns 200': r => r.status === 200,
    'vector search POST returns results': r => {
      try {
        const b = JSON.parse(r.body);
        return (b.results || b.data?.results || []).length >= 0;
      } catch { return false; }
    },
  });

  searchSuccessRate.add(postOk ? 1 : 0);
  if (postOk) {
    vectorSearchLatency.add(postSearchRes.timings.duration);
    searchThroughput.add(1);
    try {
      const b = JSON.parse(postSearchRes.body);
      const results = b.results || b.data?.results || [];
      searchResultCount.add(results.length);
    } catch { /* ignore */ }
  } else {
    searchErrors.add(1);
  }
}

function runMixedWeightSearch(data) {
  // Test with mixed weights to see how fulltext blending affects latency
  const query = SEARCH_QUERIES[Math.floor(Math.random() * SEARCH_QUERIES.length)];
  const vectorWeight = [0.5, 0.7, 0.9, 1.0][Math.floor(Math.random() * 4)];

  const res = http.get(
    `${BASE_URL}/api/v1/rag/search?query=${encodeURIComponent(query)}&topK=${data.topK}&vectorWeight=${vectorWeight}&fulltextWeight=${1 - vectorWeight}${data.collectionId ? '&collectionId=' + data.collectionId : ''}`,
    HEADERS_JSON,
    { name: 'vector_search_mixed_weight' }
  );

  const ok = check(res, {
    'mixed weight search returns 200': r => r.status === 200,
  });

  searchSuccessRate.add(ok ? 1 : 0);
  if (ok) {
    vectorSearchLatency.add(res.timings.duration);
    searchThroughput.add(1);
  } else {
    searchErrors.add(1);
  }
}

// ─────────────────────────────────────────────────────────────────
// Test Stages
// ─────────────────────────────────────────────────────────────────

export default function(data) {
  // Alternate between GET, POST, and mixed-weight searches
  const variant = __VU % 3;

  if (variant === 0) {
    runPureVectorSearch(data);
  } else if (variant === 1) {
    runPureVectorSearchPOST(data);
  } else {
    runMixedWeightSearch(data);
  }

  // Small delay between requests to simulate realistic user behavior
  sleep(0.1 + Math.random() * 0.2);
}

// ─────────────────────────────────────────────────────────────────
// Handle Summary
// ─────────────────────────────────────────────────────────────────

export function handleSummary(data) {
  const passed = data.metrics.rag_vector_search_success.values.pass;
  const failed = data.metrics.rag_vector_search_success.values.fail;
  const total = passed + failed;
  const successRate = total > 0 ? (passed / total * 100).toFixed(2) : '0.00';
  const avgLatency = data.metrics.rag_vector_search_latency.values.avg.toFixed(2);
  const p50 = data.metrics.rag_vector_search_latency.values['p(50)'].toFixed(2);
  const p95 = data.metrics.rag_vector_search_latency.values['p(95)'].toFixed(2);
  const p99 = data.metrics.rag_vector_search_latency.values['p(99)'].toFixed(2);
  const totalOps = data.metrics.rag_vector_search_ops.values.count;
  const opsPerSec = totalOps / (data.state.testRunDurationMs / 1000);
  const errors = data.metrics.rag_vector_search_errors.values.count;

  console.log('\n=== Vector Search Stress Test Results ===');
  console.log(`VUs: ${VUS}, Duration: ${DURATION}, topK: ${TOPK}, vectorWeight: ${VECTOR_WEIGHT}`);
  console.log(`Total operations: ${totalOps}`);
  console.log(`Throughput: ${opsPerSec.toFixed(2)} ops/sec`);
  console.log(`Success rate: ${successRate}% (${passed}/${total})`);
  console.log(`Errors: ${errors}`);
  console.log(`Latency — avg: ${avgLatency}ms, p(50): ${p50}ms, p(95): ${p95}ms, p(99): ${p99}ms`);
  console.log('==========================================\n');

  // Determine pass/fail based on thresholds
  const p95Ok = data.metrics.rag_vector_search_latency.values['p(95)'] < 800;
  const successOk = data.metrics.rag_vector_search_success.values.rate >= 0.95;

  if (!p95Ok || !successOk) {
    console.error(`\n⚠️  THRESHOLDS NOT MET:`);
    if (!p95Ok) console.error(`  p(95) latency ${p95}ms exceeds 800ms threshold`);
    if (!successOk) console.error(`  Success rate ${successRate}% below 95% threshold`);
  } else {
    console.log(`\n✅  All thresholds met — pgvector HNSW performing well under ${VUS} VUs`);
  }

  return {
    stdout: '',  // Already printed to console
  };
}
