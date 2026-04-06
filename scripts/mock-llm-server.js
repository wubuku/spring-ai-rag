#!/usr/bin/env node
/**
 * Mock LLM Server for spring-ai-rag testing.
 *
 * Implements OpenAI-compatible API endpoints:
 *   POST /v1/chat/completions
 *   POST /v1/embeddings
 *
 * Usage:
 *   node mock-llm-server.js [--port 8082]
 *
 * Environment variables:
 *   MOCK_PORT        - Server port (default: 8082)
 *   MOCK_DELAY_MS    - Simulated response delay in ms (default: 100)
 *   MOCK_ERROR_RATE  - Probability of returning an error 0.0-1.0 (default: 0)
 *   MOCK_MODEL       - Model name to respond with (default: mock-gpt-4)
 */

const http = require('http');

const PORT = parseInt(process.env.MOCK_PORT || '8086', 10);
const DELAY_MS = parseInt(process.env.MOCK_DELAY_MS || '100', 10);
const ERROR_RATE = parseFloat(process.env.MOCK_ERROR_RATE || '0');
const MODEL = process.env.MOCK_MODEL || 'mock-gpt-4';

// ---------- utilities ----------

function jsonResponse(res, statusCode, data) {
  res.writeHead(statusCode, {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization, openai-organization',
  });
  res.end(JSON.stringify(data));
}

function errorResponse(res, statusCode, message) {
  jsonResponse(res, statusCode, {
    error: {
      message,
      type: 'invalid_request_error',
      code: statusCode,
    },
  });
}

function randomDelay() {
  return new Promise((resolve) => setTimeout(resolve, DELAY_MS + Math.random() * 50));
}

function shouldError() {
  return Math.random() < ERROR_RATE;
}

// Simulate a cosine similarity vector (1024-dim)
function mockEmbeddingVector() {
  const dim = 1024;
  // Generate a unit vector with random values
  const vec = [];
  let norm = 0;
  for (let i = 0; i < dim; i++) {
    const v = Math.random() * 2 - 1;
    vec.push(v);
    norm += v * v;
  }
  norm = Math.sqrt(norm);
  return vec.map((v) => v / norm);
}

function mockChatResponse(body) {
  const { messages = [], stream = false, model } = body;
  const lastMessage = messages.length > 0 ? messages[messages.length - 1].content : '';

  if (stream) {
    // For streaming, we return a text/event-stream style response
    // The actual SSE bytes are written directly via res
    return null;
  }

  // Echo back a mock response that references the last user message
  const mockContent = lastMessage
    ? `This is a mock response to: "${lastMessage.substring(0, 80)}${lastMessage.length > 80 ? '...' : ''}"`
    : 'Mock LLM response (no user message provided).';

  return {
    id: `mockchat-${Date.now()}`,
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model: model || MODEL,
    choices: [
      {
        index: 0,
        message: {
          role: 'assistant',
          content: mockContent,
        },
        finish_reason: 'stop',
      },
    ],
    usage: {
      prompt_tokens: Math.floor(Math.random() * 200) + 10,
      completion_tokens: Math.floor(Math.random() * 100) + 5,
      total_tokens:
        Math.floor(Math.random() * 200) + 10 + Math.floor(Math.random() * 100) + 5,
    },
  };
}

// ---------- request handlers ----------

async function handleChatCompletions(req, res, body) {
  await randomDelay();

  if (shouldError()) {
    return errorResponse(res, 500, 'Mock LLM server simulated error');
  }

  if (req.headers['stream'] === 'true' || (body.stream === true)) {
    // SSE streaming response
    const mockContent = body.messages?.length > 0
      ? `Mock streaming response to: "${body.messages[body.messages.length - 1].content?.substring(0, 50)}"`
      : 'Mock streaming response.';

    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'Access-Control-Allow-Origin': '*',
    });

    const chunks = mockContent.split(' ');
    for (let i = 0; i < chunks.length; i++) {
      const data = {
        id: `mockchat-${Date.now()}`,
        object: 'chat.completion.chunk',
        created: Math.floor(Date.now() / 1000),
        model: body.model || MODEL,
        choices: [
          {
            index: 0,
            delta: {
              content: chunks[i] + (i < chunks.length - 1 ? ' ' : ''),
            },
            finish_reason: i === chunks.length - 1 ? 'stop' : null,
          },
        ],
      };
      res.write(`data: ${JSON.stringify(data)}\n\n`);
      await new Promise((r) => setTimeout(r, 20));
    }
    res.write('data: [DONE]\n\n');
    res.end();
    return;
  }

  const response = mockChatResponse(body);
  jsonResponse(res, 200, response);
}

async function handleEmbeddings(req, res, body) {
  await randomDelay();

  if (shouldError()) {
    return errorResponse(res, 500, 'Mock embedding server simulated error');
  }

  const { input = [], model } = body;
  const texts = Array.isArray(input) ? input : [input];

  const embedding = mockEmbeddingVector();

  jsonResponse(res, 200, {
    object: 'list',
    data: texts.map((text, i) => ({
      object: 'embedding',
      embedding,
      index: i,
    })),
    model: model || 'BAAI/bge-m3',
    usage: {
      prompt_tokens: texts.reduce((s, t) => s + Math.ceil(t.length / 4), 0),
      total_tokens: texts.reduce((s, t) => s + Math.ceil(t.length / 4), 0),
    },
  });
}

async function handleHealth(req, res) {
  jsonResponse(res, 200, {
    status: 'ok',
    model: MODEL,
    delay_ms: DELAY_MS,
  });
}

// ---------- router ----------

async function route(req, res) {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const pathname = url.pathname;

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    });
    res.end();
    return;
  }

  if (pathname === '/v1/chat/completions' && req.method === 'POST') {
    let body = '';
    for await (const chunk of req) { body += chunk; }
    try {
      const parsed = JSON.parse(body);
      await handleChatCompletions(req, res, parsed);
    } catch (e) {
      errorResponse(res, 400, `Invalid JSON: ${e.message}`);
    }
    return;
  }

  if (pathname === '/v1/embeddings' && req.method === 'POST') {
    let body = '';
    for await (const chunk of req) { body += chunk; }
    try {
      const parsed = JSON.parse(body);
      await handleEmbeddings(req, res, parsed);
    } catch (e) {
      errorResponse(res, 400, `Invalid JSON: ${e.message}`);
    }
    return;
  }

  if ((pathname === '/health' || pathname === '/') && req.method === 'GET') {
    await handleHealth(req, res);
    return;
  }

  // Fallback: 404
  jsonResponse(res, 404, { error: { message: `Not found: ${pathname}`, code: 404 } });
}

// ---------- main ----------

const server = http.createServer(async (req, res) => {
  try {
    await route(req, res);
  } catch (err) {
    console.error('[mock-llm-server] Unhandled error:', err);
    try {
      jsonResponse(res, 500, { error: { message: 'Internal server error', code: 500 } });
    } catch (_) {
      // ignore
    }
  }
});

server.listen(PORT, () => {
  console.log(`[mock-llm-server] Mock LLM server running on http://localhost:${PORT}`);
  console.log(`[mock-llm-server] Endpoints:`);
  console.log(`[mock-llm-server]   POST /v1/chat/completions  — mock chat completions`);
  console.log(`[mock-llm-server]   POST /v1/embeddings       — mock embeddings (1024-dim)`);
  console.log(`[mock-llm-server]   GET  /health              — health check`);
  console.log(`[mock-llm-server] Config: model=${MODEL}, delay=${DELAY_MS}ms, error_rate=${ERROR_RATE}`);
});

process.on('SIGTERM', () => {
  console.log('[mock-llm-server] Shutting down...');
  server.close(() => process.exit(0));
});
