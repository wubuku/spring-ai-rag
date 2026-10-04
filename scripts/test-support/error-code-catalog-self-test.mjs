#!/usr/bin/env node
// Negative tests for scripts/verify-error-code-catalog.mjs.
//
// The rule guards three things that Batch 873 measured as broken: two codes the
// API emits that ErrorCode never declared, two bodies whose status contradicted
// the catalog, and five hand-assembled bodies missing fields ErrorResponse's own
// Javadoc promises. Most of what follows pins non-findings, because a gate that
// cannot stay quiet is a gate that becomes a decoration.
//
// The last case in the first block exists because of how this batch started.
// A survey scoped to `ErrorResponse.builder()` reported "13 construction sites,
// zero incomplete" while the real number was 31 and 18 of them shipped a human
// phrase in the `error` field. A detector that cannot see a shape must be known
// to be blind to it, not assumed to have cleared it.

import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import {
  parseCatalog,
  findEmittedCodes,
  findBuilderChains,
  checkCodes,
  checkBodyShape,
  stripComments,
  HTTP_STATUS,
} from '../verify-error-code-catalog.mjs';

const here = fileURLToPath(import.meta.url);
const GATE = join(here, '..', '..', 'verify-error-code-catalog.mjs');
const ENUM_PATH = 'com/springairag/api/enums/ErrorCode.java';

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

const ENUM_SRC = `package com.springairag.api.enums;\n
public enum ErrorCode {\n
    BAD_REQUEST(400, "Bad Request"),\n
    UNAUTHORIZED(401, "Unauthorized"),\n
    NOT_FOUND(404, "Not Found"),\n
    TOO_MANY_REQUESTS(429, "Too Many Requests"),\n
    SERVICE_UNAVAILABLE(503, "Service Unavailable"),\n
    ;\n
}\n`;

const catalog = parseCatalog(ENUM_SRC);
const cls = (body) => `class Sample {\n${body}\n}\n`;

// ── catalog parsing ───────────────────────────────────────────────────────

test('reads the code -> status mapping out of the enum', () => {
  assert.equal(catalog.get('BAD_REQUEST'), 400);
  assert.equal(catalog.get('TOO_MANY_REQUESTS'), 429);
  assert.equal(catalog.size, 5);
});

test('knows the HttpStatus constants the handlers actually pass', () => {
  // A status name missing from this table would silently become `null` and be
  // reported as "no status in the body" — a wrong reason for a real finding.
  for (const name of ['BAD_REQUEST', 'UNAUTHORIZED', 'FORBIDDEN', 'NOT_FOUND',
    'TOO_MANY_REQUESTS', 'SERVICE_UNAVAILABLE', 'INTERNAL_SERVER_ERROR']) {
    assert.equal(typeof HTTP_STATUS[name], 'number', `${name} is not mapped`);
  }
});

// ── finding the codes ─────────────────────────────────────────────────────

test('finds a code passed to buildResponse across line breaks', () => {
  const src = cls(`
    void handle() {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "BAD_REQUEST",
                "nope", request);
    }`);
  const found = findEmittedCodes(src);
  assert.equal(found.length, 1);
  assert.equal(found[0].code, 'BAD_REQUEST');
  assert.equal(found[0].status, 400);
});

test('finds a code passed through the builder together with its status', () => {
  const src = cls(`
    void write() {
        ErrorResponse.builder()
                .error("TOO_MANY_REQUESTS")
                .status(HttpStatus.TOO_MANY_REQUESTS.value())
                .message("slow down").build();
    }`);
  const found = findEmittedCodes(src);
  assert.equal(found.length, 1);
  assert.equal(found[0].code, 'TOO_MANY_REQUESTS');
  assert.equal(found[0].status, 429);
});

test('reports a missing status as null rather than guessing one', () => {
  const src = cls(`
    void write() {
        ErrorResponse.builder()
                .error("TOO_MANY_REQUESTS")
                .message("slow down").build();
    }`);
  const found = findEmittedCodes(src);
  assert.equal(found[0].code, 'TOO_MANY_REQUESTS');
  assert.equal(found[0].status, null);
});

test('does not read a code out of a javadoc example', () => {
  const src = cls(`
    /**
     * Example: buildResponse(HttpStatus.BAD_REQUEST, "MADE_UP_CODE", "x", request);
     */
    void handle() {
        return buildResponse(HttpStatus.FORBIDDEN, "SERVICE_UNAVAILABLE", "x", request);
    }`);
  const found = findEmittedCodes(src);
  assert.equal(found.length, 1, 'the javadoc sample must not be counted as a second site');
  assert.equal(found[0].code, 'SERVICE_UNAVAILABLE');
});

test('stripComments leaves the call intact', () => {
  const out = stripComments('a(); /* b("X") */ c(); // d("Y")\n');
  assert.equal(out.includes('b('), false);
  assert.equal(out.includes('d('), false);
  assert.equal(out.includes('a();'), true);
});

// ── rule 1: the code must be registered ───────────────────────────────────

test('flags a code the enum does not declare', () => {
  const src = cls(`
    void handle() {
        return buildResponse(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "x", request);
    }`);
  const found = checkCodes(src, catalog);
  assert.equal(found.length, 1);
  assert.equal(found[0].kind, 'registered');
  assert.match(found[0].message, /MISSING_HEADER/);
  assert.match(found[0].message, /single source of truth/);
});

test('does not flag a registered code', () => {
  const src = cls(`
    void handle() {
        return buildResponse(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "x", request);
    }`);
  assert.deepEqual(checkCodes(src, catalog), []);
});

// ── rule 2: the status must agree with the catalog ────────────────────────

test('flags a status that contradicts the catalog for the same code', () => {
  const src = cls(`
    void handle() {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "NOT_FOUND", "x", request);
    }`);
  const found = checkCodes(src, catalog);
  assert.equal(found.length, 1);
  assert.equal(found[0].kind, 'status-drift');
  assert.match(found[0].message, /404/);
  assert.match(found[0].message, /two different stories/);
});

test('flags a registered code that carries no status at all', () => {
  const src = cls(`
    void write() {
        ErrorResponse.builder().error("BAD_REQUEST").message("nope").build();
    }`);
  const found = checkCodes(src, catalog);
  assert.equal(found.length, 1);
  assert.equal(found[0].kind, 'status-drift');
  assert.match(found[0].message, /no status in the body/);
});

// ── rule 3: a hand-assembled body must be a problem detail ────────────────

test('flags a body assembled out of detail alone', () => {
  // This is the RagSearchController shape, four times over before Batch 873.
  const src = cls(`
    void reject() {
        return ResponseEntity.badRequest().body(
                ErrorResponse.builder().detail("Query must not be blank").build());
    }`);
  const found = checkBodyShape(src);
  assert.equal(found.length, 1);
  assert.equal(found[0].kind, 'incomplete-body');
  for (const field of ['error', 'status']) {
    assert.match(found[0].message, new RegExp(field), `must name the missing ${field}`);
  }
});

test('flags a body that names the code but forgets the sentence', () => {
  const src = cls(`
    void write() {
        ErrorResponse.builder()
                .error("UNAUTHORIZED")
                .status(HttpStatus.UNAUTHORIZED.value())
                .path("/api/v1/x")
                .build();
    }`);
  const found = checkBodyShape(src);
  assert.equal(found.length, 1);
  assert.match(found[0].message, /detail/);
});

test('does not flag a body that derives type and title from its code', () => {
  // The lesson from writing the first survey: reading only the *explicit*
  // builder calls marks all thirteen real sites incomplete, because
  // `error(...)` writes title and type as a side effect and `detail(...)`
  // writes message. A rule that cries wolf on correct code gets exempted, and
  // an exempted rule protects nothing.
  const src = cls(`
    void write() {
        ErrorResponse.builder()
                .error("UNAUTHORIZED")
                .status(HttpStatus.UNAUTHORIZED.value())
                .message("no key")
                .path("/api/v1/x")
                .build();
    }`);
  assert.deepEqual(checkBodyShape(src), []);
});

test('a title() call alone is not a code', () => {
  // This is the shape ErrorResponse.of(String) used to have, and the reason
  // eighteen endpoints shipped "Bad Request" in the error field: title() has a
  // side effect on error, so a body that only sets a title has a phrase where
  // every other site has a machine code.
  const src = cls(`
    void write() {
        ErrorResponse.builder()
                .title("Bad Request")
                .status(400)
                .detail("nope")
                .build();
    }`);
  const found = checkBodyShape(src);
  assert.equal(found.length, 1);
  assert.match(found[0].message, /error/);
});

test('does not flag a chain whose fields are completed after build()', () => {
  const src = cls(`
    void write() {
        ErrorResponse body = ErrorResponse.builder()
                .error("SERVICE_UNAVAILABLE")
                .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                .detail("policy down")
                .build();
        body.setTitle("Service Unavailable");
    }`);
  assert.deepEqual(checkBodyShape(src), []);
});

test('finds every chain in a file, not just the first', () => {
  const src = cls(`
    void a() { ErrorResponse.builder().detail("x").build(); }
    void b() { ErrorResponse.builder().detail("y").build(); }
    void c() { ErrorResponse.builder().detail("z").build(); }`);
  assert.equal(checkBodyShape(src).length, 3);
});

// ── the blind spot, pinned on purpose ─────────────────────────────────────

test('KNOWN BLIND SPOT: a file that only uses the of() factories is invisible', () => {
  // Before Batch 873, `ErrorResponse.of(String)` set title("Bad Request"), and
  // because title() also writes error, eighteen endpoints returned a human
  // phrase in a field every other endpoint fills with a SNAKE_CASE code. The
  // survey that found it was scoped to `ErrorResponse.builder()` and reported
  // this file clean.
  //
  // The factory now delegates to of(ErrorCode.BAD_REQUEST, detail), so the
  // emitted code is right — but the gate still cannot see it. That is a
  // property of source scanning, not something this batch fixed. If someone
  // reintroduces a phrase-in-error factory, nothing here goes red, and whoever
  // reads this knows to look rather than to assume.
  const src = cls(`
    void write() {
        return ResponseEntity.badRequest().body(ErrorResponse.of("providers cannot be empty"));
    }`);
  assert.deepEqual(findBuilderChains(src), [], 'no builder chain — that part is real');
  assert.deepEqual(findEmittedCodes(src), [], 'and no code literal either');
  // What actually protects those eighteen call sites today:
  const factory = readFileSync(
    join(here, '..', '..', '..', 'spring-ai-rag-api/src/main/java/com/springairag/api/dto/ErrorResponse.java'),
    'utf8',
  );
  assert.match(
    factory,
    /of\(ErrorCode\.BAD_REQUEST, detail\)/,
    'the protection is the factory delegating, not this gate',
  );
});

// ── through the process boundary ──────────────────────────────────────────

function runGate(files) {
  const dir = mkdtempSync(join(tmpdir(), 'error-code-gate-'));
  for (const [name, body] of Object.entries({ [ENUM_PATH]: ENUM_SRC, ...files })) {
    const full = join(dir, name);
    mkdirSync(dirname(full), { recursive: true });
    writeFileSync(full, body, 'utf8');
  }
  const result = spawnSync(process.execPath, [GATE], {
    env: { ...process.env, ERROR_CODE_CATALOG_ROOT: dir },
    encoding: 'utf8',
  });
  rmSync(dir, { recursive: true, force: true });
  return result;
}

const out = (r) => r.stdout + r.stderr;

test('the gate exits non-zero and names a code the enum never declared', () => {
  const result = runGate({
    'Handler.java': cls(`
      void handle() {
          return buildResponse(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "x", request);
      }`),
  });
  assert.equal(result.status, 1, `expected a failing exit\n${out(result)}`);
  assert.match(out(result), /MISSING_HEADER/);
  assert.match(out(result), /error-code-catalog/);
});

test('the gate exits non-zero on a status that contradicts the catalog', () => {
  const result = runGate({
    'Handler.java': cls(`
      void handle() {
          return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "TOO_MANY_REQUESTS", "x", request);
      }`),
  });
  assert.equal(result.status, 1, `expected a failing exit\n${out(result)}`);
  assert.match(out(result), /TOO_MANY_REQUESTS/);
  assert.match(out(result), /429/);
});

test('the gate exits non-zero on a detail-only body and lists what is absent', () => {
  const result = runGate({
    'Search.java': cls(`
      void reject() {
          return ResponseEntity.badRequest().body(
                  ErrorResponse.builder().detail("Query must not be blank").build());
      }`),
  });
  assert.equal(result.status, 1, `expected a failing exit\n${out(result)}`);
  assert.match(out(result), /error/);
  assert.match(out(result), /status/);
});

test('the gate exits zero on a tree that gets all three right', () => {
  const result = runGate({
    'Good.java': cls(`
      void handle() {
          return buildResponse(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "no key", request);
      }
      void write() {
          ErrorResponse.builder()
                  .error("TOO_MANY_REQUESTS")
                  .status(HttpStatus.TOO_MANY_REQUESTS.value())
                  .message("slow down")
                  .path("/api/v1/x")
                  .build();
      }`),
  });
  assert.equal(result.status, 0, `expected a passing exit\n${out(result)}`);
  assert.match(result.stdout, /passed/);
});

test('the gate exits zero on a tree that holds only the enum', () => {
  const result = runGate({});
  assert.equal(result.status, 0, `expected a passing exit\n${out(result)}`);
});

let failed = 0;
for (const { title, fn } of cases) {
  try {
    fn();
    console.log(`ok - ${title}`);
  } catch (err) {
    failed += 1;
    console.error(`not ok - ${title}`);
    console.error(`  ${err.message}`);
  }
}
console.log(`\nError code catalog self-test: ${cases.length - failed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
