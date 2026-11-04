#!/usr/bin/env node
// Self-test for `scripts/verify-endpoint-behavior-matrix.mjs`.
//
// The gate's whole claim is "a newly documented endpoint cannot slip past the
// matrix", so the cases below drive it with fixtures rather than relying on the
// committed matrix being green: an empty matrix, a missing 404 probe, a wrong
// unauthenticated status, a widened invalid-parameter status, and a
// documentation-only endpoint that OpenAPI does not know about. The last case
// runs the shipped matrix over the shipped documents, which is the only
// assertion that can notice the gate and its data drifting apart.

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildMatrix, canonicalPath, operationKey, validate } from '../verify-endpoint-behavior-matrix.mjs';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const specPath = path.join(repoRoot, 'testdata/api-contract/openapi-baseline.json');
const docPath = path.join(repoRoot, 'docs/rest-api-zh-CN.md');
const matrixPath = path.join(repoRoot, 'testdata/api-contract/endpoint-behavior-matrix.json');

const fixtureSpec = JSON.stringify({
  paths: {
    '/api/v1/rag/things/{id}': {
      get: {
        parameters: [{ in: 'path', name: 'id', schema: { type: 'integer' } }],
        responses: { 200: { content: { 'application/json': { schema: {
          type: 'object', required: ['id'], properties: { id: {} } } } } } },
      },
    },
    '/api/v1/rag/things': {
      post: {
        requestBody: { required: true, content: { 'application/json': { schema: {
          type: 'object', required: ['name'], properties: { name: { type: 'string' } } } } } },
        responses: { 200: { content: { 'application/json': { schema: {
          type: 'object', required: ['id'], properties: { id: {} } } } } } },
      },
    },
  },
});

// Documentation may name an endpoint the OpenAPI baseline does not carry — the
// `/v1/**` compatibility surface is one such case. It still owes a matrix entry.
const fixtureDoc = [
  '### `GET /api/v1/rag/things/{id}`',
  '### `POST /api/v1/rag/things`',
  '### `POST /api/v1/rag/things/{id}/archive`',
].join('\n');

const cases = [
  ['path canonicalisation collapses every parameter name', () => {
    assert.equal(canonicalPath('/api/v1/rag/things/{id}/'), '/api/v1/rag/things/{}');
    assert.equal(operationKey('get', '/api/v1/rag/things/{thingId}'), 'GET /api/v1/rag/things/{}');
  }],

  ['documentation-only endpoints are part of the expected inventory', () => {
    const matrix = buildMatrix(fixtureSpec, fixtureDoc);
    const keys = matrix.entries.map((entry) => operationKey(entry.method, entry.path));
    assert.ok(keys.includes('POST /api/v1/rag/things'), keys.join(', '));
    assert.ok(keys.includes('POST /api/v1/rag/things/{}/archive'), keys.join(', '));
  }],

  ['an empty matrix is rejected', () => {
    const matrix = buildMatrix(fixtureSpec, fixtureDoc);
    assert.ok(validate({ ...matrix, entries: [] }, fixtureSpec, fixtureDoc).length > 0);
  }],

  ['a documented endpoint with no entry is rejected', () => {
    const matrix = buildMatrix(fixtureSpec, fixtureDoc);
    const pruned = matrix.entries.filter((entry) =>
      operationKey(entry.method, entry.path) !== 'POST /api/v1/rag/things/{}/archive');
    const errors = validate({ ...matrix, entries: pruned }, fixtureSpec, fixtureDoc);
    assert.ok(errors.some((error) => error.includes('things/{}/archive')), errors.join('\n'));
  }],

  ['a path parameter without a 404 probe is rejected', () => {
    const matrix = buildMatrix(fixtureSpec, fixtureDoc);
    const entries = matrix.entries.map((entry) =>
      operationKey(entry.method, entry.path) === 'GET /api/v1/rag/things/{}'
        ? { ...entry, probes: { ...entry.probes, missing: null } }
        : entry);
    assert.ok(validate({ ...matrix, entries }, fixtureSpec, fixtureDoc).length > 0);
  }],

  ['an unauthenticated status other than 401 is rejected', () => {
    const matrix = buildMatrix(fixtureSpec, fixtureDoc);
    const entries = matrix.entries.map((entry) => ({ ...entry, unauth: { status: 403 } }));
    assert.ok(validate({ ...matrix, entries }, fixtureSpec, fixtureDoc).length > 0);
  }],

  ['widening an invalid-parameter probe past 400 is rejected', () => {
    const matrix = buildMatrix(fixtureSpec, fixtureDoc);
    const entries = matrix.entries.map((entry) => entry.probes?.invalid
      ? { ...entry, probes: { ...entry.probes, invalid: { kind: 'json', status: 500 } } }
      : entry);
    assert.ok(validate({ ...matrix, entries }, fixtureSpec, fixtureDoc).length > 0);
  }],

  ['a non-404 missing-resource status needs a written reason', () => {
    const matrix = buildMatrix(fixtureSpec, fixtureDoc);
    const entries = matrix.entries.map((entry) =>
      operationKey(entry.method, entry.path) === 'GET /api/v1/rag/things/{}'
        ? { ...entry, probes: { ...entry.probes, missing: { status: 200 } } }
        : entry);
    const errors = validate({ ...matrix, entries }, fixtureSpec, fixtureDoc);
    assert.ok(errors.some((error) => error.includes('reason')), errors.join('\n'));
  }],

  ['the committed matrix matches the committed documents', () => {
    const spec = fs.readFileSync(specPath, 'utf8');
    const doc = fs.readFileSync(docPath, 'utf8');
    const matrix = JSON.parse(fs.readFileSync(matrixPath, 'utf8'));
    const errors = validate(matrix, spec, doc);
    assert.deepEqual(errors, []);
    assert.ok(matrix.entries.length > 0);
  }],
];

const failures = [];
for (const [title, fn] of cases) {
  try {
    fn();
    console.log(`[self-test] 通过：${title}`);
  } catch (error) {
    failures.push({ title, error });
  }
}

if (failures.length > 0) {
  for (const { title, error } of failures) {
    console.error(`[self-test] 失败：${title}\n${error.message}`);
  }
  process.exit(1);
}
console.log(`[self-test] ${cases.length} 个用例全部通过。`);
