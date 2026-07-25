#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const http = require('node:http');
const test = require('node:test');

const {
  createProxyServer,
  normalizeObjectSchemaRequired,
  patchAnthropicMessageBody,
} = require('./claude-anthropic-schema-proxy');

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
  });
}

function close(server) {
  return new Promise((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()));
  });
}

test('normalizes missing and null required arrays recursively', () => {
  const schema = {
    type: 'object',
    properties: {
      nested: {
        type: 'object',
        properties: {
          value: { type: 'string' },
        },
        required: null,
      },
      existing: {
        type: 'object',
        properties: {
          id: { type: 'string' },
        },
        required: ['id'],
      },
    },
  };

  assert.equal(normalizeObjectSchemaRequired(schema), 2);
  assert.deepEqual(schema.required, []);
  assert.deepEqual(schema.properties.nested.required, []);
  assert.deepEqual(schema.properties.existing.required, ['id']);
});

test('patches only Anthropic tool input schemas', () => {
  const original = Buffer.from(JSON.stringify({
    model: 'grok-4.5',
    metadata: {
      type: 'object',
    },
    tools: [
      {
        name: 'no_args_tool',
        input_schema: {
          type: 'object',
          properties: {},
        },
      },
    ],
  }));

  const patched = patchAnthropicMessageBody(original);
  const payload = JSON.parse(patched.body.toString('utf8'));

  assert.equal(patched.patchCount, 1);
  assert.equal(payload.metadata.required, undefined);
  assert.deepEqual(payload.tools[0].input_schema.required, []);
});

test('proxies patched requests and preserves streaming response bytes', async (t) => {
  let receivedPayload;
  const upstream = http.createServer(async (req, res) => {
    let body = '';
    for await (const chunk of req) {
      body += chunk;
    }
    receivedPayload = JSON.parse(body);

    res.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-cache',
    });
    res.write('event: message_start\n');
    res.end('data: {"type":"message_start"}\n\n');
  });
  const upstreamPort = await listen(upstream);
  t.after(() => close(upstream));

  const proxy = createProxyServer({
    upstreamBaseUrl: `http://127.0.0.1:${upstreamPort}`,
  });
  const proxyPort = await listen(proxy);
  t.after(() => close(proxy));

  const response = await fetch(`http://127.0.0.1:${proxyPort}/v1/messages`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-api-key': 'test-key',
      'anthropic-version': '2023-06-01',
    },
    body: JSON.stringify({
      model: 'grok-4.5',
      max_tokens: 16,
      messages: [{ role: 'user', content: 'hello' }],
      tools: [{
        name: 'no_args_tool',
        input_schema: {
          type: 'object',
          properties: {},
        },
      }],
    }),
  });

  assert.equal(response.status, 200);
  assert.equal(
    await response.text(),
    'event: message_start\ndata: {"type":"message_start"}\n\n',
  );
  assert.deepEqual(receivedPayload.tools[0].input_schema.required, []);
});

test('serves a local health endpoint without contacting upstream', async (t) => {
  const proxy = createProxyServer({
    upstreamBaseUrl: 'http://127.0.0.1:1',
  });
  const proxyPort = await listen(proxy);
  t.after(() => close(proxy));

  const response = await fetch(`http://127.0.0.1:${proxyPort}/__claude_proxy/health`);
  const payload = await response.json();

  assert.equal(response.status, 200);
  assert.equal(payload.service, 'claude-anthropic-schema-proxy');
  assert.equal(payload.version, 1);
  assert.equal(payload.status, 'ok');
  assert.equal(payload.pid, process.pid);
  assert.equal(payload.upstream, 'http://127.0.0.1:1');
});
