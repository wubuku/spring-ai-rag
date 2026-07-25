#!/usr/bin/env node
'use strict';

const http = require('node:http');
const https = require('node:https');

const DEFAULT_HOST = '127.0.0.1';
const DEFAULT_PORT = 38473;
const DEFAULT_UPSTREAM_BASE_URL = 'https://api.openai-next.com';
const DEFAULT_MAX_BODY_BYTES = 32 * 1024 * 1024;

const HOP_BY_HOP_HEADERS = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'proxy-connection',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
]);

function normalizeObjectSchemaRequired(schema) {
  let patchCount = 0;
  const visited = new Set();

  function visit(node) {
    if (!node || typeof node !== 'object' || visited.has(node)) {
      return;
    }
    visited.add(node);

    if (!Array.isArray(node) && node.type === 'object' && node.required == null) {
      node.required = [];
      patchCount += 1;
    }

    if (Array.isArray(node)) {
      node.forEach(visit);
      return;
    }

    Object.values(node).forEach(visit);
  }

  visit(schema);
  return patchCount;
}

function patchAnthropicMessageBody(body) {
  const payload = JSON.parse(body.toString('utf8'));
  let patchCount = 0;

  if (Array.isArray(payload.tools)) {
    for (const tool of payload.tools) {
      patchCount += normalizeObjectSchemaRequired(tool?.input_schema);
    }
  }

  return {
    body: patchCount > 0 ? Buffer.from(JSON.stringify(payload)) : body,
    patchCount,
  };
}

function stripHopByHopHeaders(headers, bodyLength) {
  const result = {};

  for (const [name, value] of Object.entries(headers)) {
    const lowerName = name.toLowerCase();
    if (
      value == null
      || lowerName === 'host'
      || lowerName === 'content-length'
      || HOP_BY_HOP_HEADERS.has(lowerName)
    ) {
      continue;
    }
    result[lowerName] = value;
  }

  if (bodyLength != null) {
    result['content-length'] = String(bodyLength);
  }
  return result;
}

function parsePositiveInteger(name, value, fallback) {
  if (value == null || value === '') {
    return fallback;
  }

  const parsed = Number.parseInt(value, 10);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer, got: ${value}`);
  }
  return parsed;
}

async function readRequestBody(req, maxBodyBytes) {
  const chunks = [];
  let totalBytes = 0;

  for await (const chunk of req) {
    totalBytes += chunk.length;
    if (totalBytes > maxBodyBytes) {
      const error = new Error(`Request body exceeds ${maxBodyBytes} bytes`);
      error.statusCode = 413;
      throw error;
    }
    chunks.push(chunk);
  }

  return Buffer.concat(chunks);
}

function sendJson(res, statusCode, payload) {
  const body = Buffer.from(JSON.stringify(payload));
  res.writeHead(statusCode, {
    'content-type': 'application/json',
    'content-length': String(body.length),
  });
  res.end(body);
}

function createProxyServer(options = {}) {
  const upstreamBaseUrl = new URL(options.upstreamBaseUrl || DEFAULT_UPSTREAM_BASE_URL);
  if (!['http:', 'https:'].includes(upstreamBaseUrl.protocol)) {
    throw new Error(`Unsupported upstream protocol: ${upstreamBaseUrl.protocol}`);
  }

  const maxBodyBytes = options.maxBodyBytes || DEFAULT_MAX_BODY_BYTES;
  const debug = options.debug === true;
  const requestClient = upstreamBaseUrl.protocol === 'https:' ? https : http;

  return http.createServer(async (req, res) => {
    const startedAt = Date.now();
    const requestUrl = new URL(req.url || '/', 'http://claude-schema-proxy.local');

    if (req.method === 'GET' && requestUrl.pathname === '/__claude_proxy/health') {
      sendJson(res, 200, {
        service: 'claude-anthropic-schema-proxy',
        version: 1,
        status: 'ok',
        pid: process.pid,
        upstream: upstreamBaseUrl.origin,
      });
      return;
    }

    let body;
    let patchCount = 0;

    try {
      body = await readRequestBody(req, maxBodyBytes);
      if (
        req.method === 'POST'
        && requestUrl.pathname === '/v1/messages'
        && body.length > 0
      ) {
        try {
          const patched = patchAnthropicMessageBody(body);
          body = patched.body;
          patchCount = patched.patchCount;
        } catch (error) {
          if (debug) {
            console.error(`[claude-schema-proxy] Request JSON was not patched: ${error.message}`);
          }
        }
      }
    } catch (error) {
      sendJson(res, error.statusCode || 400, {
        error: {
          type: 'proxy_request_error',
          message: error.message,
        },
      });
      return;
    }

    const targetUrl = new URL(requestUrl.pathname + requestUrl.search, upstreamBaseUrl);
    const headers = stripHopByHopHeaders(req.headers, body.length);

    const upstreamReq = requestClient.request(
      targetUrl,
      {
        method: req.method,
        headers,
      },
      (upstreamRes) => {
        const responseHeaders = stripHopByHopHeaders(upstreamRes.headers);
        res.writeHead(upstreamRes.statusCode || 502, responseHeaders);
        upstreamRes.pipe(res);

        if (debug) {
          upstreamRes.on('end', () => {
            console.log(
              `[claude-schema-proxy] ${req.method} ${requestUrl.pathname}`
              + ` -> ${upstreamRes.statusCode} patched=${patchCount}`
              + ` duration_ms=${Date.now() - startedAt}`,
            );
          });
        }
      },
    );

    upstreamReq.on('error', (error) => {
      if (!res.headersSent) {
        sendJson(res, 502, {
          error: {
            type: 'proxy_upstream_error',
            message: error.message,
          },
        });
      } else {
        res.destroy(error);
      }
    });

    res.on('close', () => {
      if (!res.writableEnded) {
        upstreamReq.destroy();
      }
    });

    upstreamReq.end(body);
  });
}

function startFromEnvironment() {
  const host = process.env.CLAUDE_PROXY_HOST || DEFAULT_HOST;
  const port = parsePositiveInteger(
    'CLAUDE_PROXY_PORT',
    process.env.CLAUDE_PROXY_PORT,
    DEFAULT_PORT,
  );
  const maxBodyBytes = parsePositiveInteger(
    'CLAUDE_PROXY_MAX_BODY_BYTES',
    process.env.CLAUDE_PROXY_MAX_BODY_BYTES,
    DEFAULT_MAX_BODY_BYTES,
  );
  const upstreamBaseUrl = process.env.CLAUDE_PROXY_UPSTREAM_BASE_URL
    || DEFAULT_UPSTREAM_BASE_URL;
  const debug = process.env.CLAUDE_PROXY_DEBUG === '1';
  const server = createProxyServer({
    upstreamBaseUrl,
    maxBodyBytes,
    debug,
  });

  server.on('error', (error) => {
    console.error(`[claude-schema-proxy] Failed to start: ${error.message}`);
    process.exitCode = 1;
  });

  server.listen(port, host, () => {
    console.log(`[claude-schema-proxy] Listening on http://${host}:${port}`);
    console.log(`[claude-schema-proxy] Upstream: ${upstreamBaseUrl}`);
  });

  let shuttingDown = false;
  const shutdown = () => {
    if (shuttingDown) {
      return;
    }
    shuttingDown = true;

    server.close(() => process.exit(0));
    server.closeIdleConnections?.();

    const forceCloseTimer = setTimeout(() => {
      server.closeAllConnections?.();
      process.exit(0);
    }, 1000);
    forceCloseTimer.unref();
  };
  process.once('SIGINT', shutdown);
  process.once('SIGTERM', shutdown);
}

if (require.main === module) {
  startFromEnvironment();
}

module.exports = {
  createProxyServer,
  normalizeObjectSchemaRequired,
  patchAnthropicMessageBody,
  stripHopByHopHeaders,
};
