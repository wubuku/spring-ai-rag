#!/usr/bin/env node
// Negative tests for scripts/verify-null-request-forwarding.mjs.
//
// The rule this guards is narrow on purpose. Batch 816 surveyed every controller
// and found seventeen overloads that forward a literal `null`; only two of them
// put that null into an HttpServletRequest position. The other fifteen null a
// business parameter — collectionKey, idempotencyKey, embeddingPolicy, an
// expected-revision guard — and are perfectly legitimate conveniences.
//
// A detector that flagged all seventeen would have produced fifteen exemptions,
// which is a debt baseline wearing a gate's clothes. So most of what follows
// pins *non*-findings: the shapes that must be released.

import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import {
  findNullRequestForwarding,
  requestParamIndex,
  splitTopLevelArgs,
  collectNullCarryingRequests,
} from '../verify-null-request-forwarding.mjs';

const here = fileURLToPath(import.meta.url);
const GATE = join(here, '..', '..', 'verify-null-request-forwarding.mjs');
const CORE = join(here, '..', '..', '..', 'spring-ai-rag-core/src/main/java/com/springairag/core/controller/');

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

/** Wrap a method body in a class so the overload collector has something to read. */
const cls = (body) => `class Sample {\n${body}\n}\n`;

// ── argument splitting ────────────────────────────────────────────────────

test('splits top-level commas only', () => {
  assert.deepEqual(splitTopLevelArgs('a, b, c'), ['a', 'b', 'c']);
});

test('does not split inside a generic type argument', () => {
  assert.deepEqual(splitTopLevelArgs('Map<String, List<Long>> m, int limit'),
    ['Map<String, List<Long>> m', 'int limit']);
});

test('does not split inside a nested call', () => {
  assert.deepEqual(splitTopLevelArgs('f(a, b), null'), ['f(a, b)', 'null']);
});

test('ignores a trailing comma with no argument', () => {
  assert.deepEqual(splitTopLevelArgs('a, '), ['a']);
});

// ── the load-bearing rule ─────────────────────────────────────────────────

test('flags a null forwarded into the HttpServletRequest position', () => {
  const src = cls(`
    public ResponseEntity<?> search(SearchRequest request, HttpServletRequest httpRequest) {
        return resolve();
    }
    ResponseEntity<?> search(SearchRequest request) {
        return search(request, null);
    }`);
  const hits = findNullRequestForwarding(src);
  assert.equal(hits.length, 1);
  assert.equal(hits[0].name, 'search');
  assert.equal(hits[0].argIndex, 1);
});

test('flags a null in the first position when the request comes first', () => {
  const src = cls(`
    void stream(HttpServletRequest httpRequest, String body) {
        write();
    }
    void stream(String body) {
        stream(null, body);
    }`);
  const hits = findNullRequestForwarding(src);
  assert.equal(hits.length, 1);
  assert.equal(hits[0].argIndex, 0);
});

test('locates the request parameter by position', () => {
  assert.equal(requestParamIndex(['int a', 'HttpServletRequest r', 'String b']), 1);
  assert.equal(requestParamIndex(['int a', 'String b']), -1);
  assert.equal(requestParamIndex([]), -1);
});

// ── non-findings: the shapes that made a wide rule unusable ───────────────

test('releases a null in a business-parameter position', () => {
  // Batch 816's real census: seven such overloads in RagDocumentController.
  const src = cls(`
    public ResponseEntity<DocumentDeleteResponse> deleteDocument(Long id, Long expectedRevision) {
        return remove();
    }
    public ResponseEntity<DocumentDeleteResponse> deleteDocument(Long id) {
        return deleteDocument(id, null);
    }`);
  assert.deepEqual(findNullRequestForwarding(src), []);
});

test('releases a null that is not a literal', () => {
  // A variable is not by itself evidence of an accidental bypass. Batch 819
  // narrowed this further rather than dropping it: an *undeclared* name has no
  // provenance, so it stays released. A name that IS declared as a possibly-null
  // HttpServletRequest is a different matter — see the cases below.
  const src = cls(`
    void send(HttpServletRequest httpRequest) {
        write();
    }
    void send(String body) {
        send(capturedRequest);
    }`);
  assert.deepEqual(findNullRequestForwarding(src), []);
});

// ── Batch 819: the shape a literal-only rule cannot see ───────────────────

test('flags a request local built by a ternary that can yield null', () => {
  // This is the exact overload Batch 819 deleted from RagCollectionController.
  // The literal-only rule reported zero findings on that file, because the `null`
  // sits three lines below the call and behind a variable name.
  const src = cls(`
    public ResponseEntity<?> create(CollectionRequest request, HttpServletRequest httpRequest) {
        return persist();
    }
    public ResponseEntity<?> create(CollectionRequest request) {
        HttpServletRequest currentRequest =
                RequestContextHolder.getRequestAttributes()
                        instanceof ServletRequestAttributes attributes
                        ? attributes.getRequest()
                        : null;
        return create(request, currentRequest);
    }`);
  const hits = findNullRequestForwarding(src);
  assert.equal(hits.length, 1, 'the ternary-carried null must be reported');
  assert.equal(hits[0].name, 'create');
  assert.equal(hits[0].argIndex, 1);
  assert.match(hits[0].carrier, /currentRequest/,
    'the report must name the variable, or the reader cannot check it');
});

test('flags a request local assigned plain null', () => {
  const src = cls(`
    void send(HttpServletRequest httpRequest) {
        write();
    }
    void send(String body) {
        HttpServletRequest request = null;
        send(request);
    }`);
  const hits = findNullRequestForwarding(src);
  assert.equal(hits.length, 1);
  assert.match(hits[0].carrier, /request/);
});

test('releases a request local whose ternary yields a real request', () => {
  // The false branch is not null, so nothing can be lost here. A rule that
  // flagged this would be a rule crying wolf.
  const src = cls(`
    void send(HttpServletRequest httpRequest) {
        write();
    }
    void send(String body) {
        HttpServletRequest request = other == null ? fallback : other;
        send(request);
    }`);
  assert.deepEqual(findNullRequestForwarding(src), []);
});

test('releases a possibly-null local that is not a request', () => {
  const src = cls(`
    void delete(Long id, String collectionKey) {
        remove();
    }
    void delete(Long id) {
        String key = keyOf(id) == null ? null : keyOf(id);
        delete(id, key);
    }`);
  assert.deepEqual(findNullRequestForwarding(src), []);
});

test('collects only the locals that can actually be null', () => {
  const src = cls(`
    void a() {
        HttpServletRequest yes1 = null;
        HttpServletRequest yes2 = cond ? x.getRequest() : null;
        HttpServletRequest no1 = x.getRequest();
        HttpServletRequest no2 = a == null ? b : c;
        String notARequest = null;
    }`);
  const names = collectNullCarryingRequests(src);
  assert.deepEqual([...names].sort(), ['yes1', 'yes2']);
});

test('releases a call whose arity matches no overload carrying a request', () => {
  const src = cls(`
    void target(Long a, String b, String c, String d) {
        write();
    }
    void target(Long a) {
        target(a, null, null, null);
    }`);
  assert.deepEqual(findNullRequestForwarding(src), []);
});

test('releases a call to a method that does not exist in this class', () => {
  const src = cls(`
    void send(HttpServletRequest httpRequest) {
        write();
    }
    void other() {
        helper.send(null);
    }`);
  assert.deepEqual(findNullRequestForwarding(src), []);
});

test('releases a null in the response position of a three-argument overload', () => {
  // Batch 816 deleted a third overload that nulled only HttpServletResponse.
  // It is not an authorization bypass, so the rule must not claim it is.
  const src = cls(`
    SseEmitter stream(ChatRequest c, HttpServletRequest req, HttpServletResponse res) {
        return emit();
    }
    SseEmitter stream(ChatRequest c, HttpServletRequest req) {
        return stream(c, req, null);
    }`);
  assert.deepEqual(findNullRequestForwarding(src), []);
});

test('ignores commented-out code', () => {
  const src = cls(`
    void search(SearchRequest s, HttpServletRequest httpRequest) {
        write();
    }
    // return search(s, null);
    void note() {
        write();
    }`);
  assert.deepEqual(findNullRequestForwarding(src), []);
});

test('ignores a null mentioned inside a javadoc example', () => {
  const src = cls(`
    /**
     * Example: search(request, null);
     */
    void search(SearchRequest s, HttpServletRequest httpRequest) {
        write();
    }`);
  assert.deepEqual(findNullRequestForwarding(src), []);
});

test('reports a file once per offending call, not once per overload', () => {
  const src = cls(`
    void go(HttpServletRequest httpRequest) {
        write();
    }
    void a() {
        go(null);
    }
    void b() {
        go(null);
    }`);
  assert.equal(findNullRequestForwarding(src).length, 2);
});

// ── the detector against the real tree ────────────────────────────────────

test('finds nothing in the controllers as they stand after Batch 819', () => {
  for (const f of ['RagChatController.java', 'RagSearchController.java',
    'RagDocumentController.java', 'PdfImportController.java', 'RagCollectionController.java']) {
    const hits = findNullRequestForwarding(readFileSync(join(CORE, f), 'utf8'));
    assert.deepEqual(hits.map((h) => h.name), [], `${f} still forwards a null request`);
  }
});

// ── the gate end to end ───────────────────────────────────────────────────
//
// Everything above tests the detector. None of it tests the *gate* — and a
// mutation that emptied the reporting loop left all sixteen cases green, which
// is the same shape as the four "gates that cannot fail" this repository has
// already had to delete. These two cases run the real script as a child process
// and assert its exit code, which is the only thing CI observes.

function runGate(files) {
  const dir = mkdtempSync(join(tmpdir(), 'null-request-gate-'));
  for (const [name, body] of Object.entries(files)) {
    writeFileSync(join(dir, name), body, 'utf8');
  }
  const result = spawnSync(process.execPath, [GATE], {
    env: { ...process.env, NULL_REQUEST_FORWARDING_ROOT: dir },
    encoding: 'utf8',
  });
  rmSync(dir, { recursive: true, force: true });
  return result;
}

test('the gate exits non-zero on a tree that forwards a null request', () => {
  const result = runGate({
    'Bad.java': `class Bad {\n`
      + '    void search(String q, HttpServletRequest httpRequest) { write(); }\n'
      + '    void search(String q) { search(q, null); }\n'
      + '    void write() {}\n}\n',
  });
  assert.equal(result.status, 1, `expected a failing exit, got ${result.status}\n${result.stdout}${result.stderr}`);
  assert.match(result.stdout + result.stderr, /null-request-forwarding/,
    'the violation must actually be reported, not merely counted');
  assert.match(result.stdout + result.stderr, /fail open/,
    'the report must say why a null request is dangerous here');
});

test('the gate exits zero on a tree whose nulls are business parameters', () => {
  const result = runGate({
    'Good.java': `class Good {\n`
      + '    void delete(Long id, Long expectedRevision) { remove(); }\n'
      + '    void delete(Long id) { delete(id, null); }\n'
      + '    void remove() {}\n}\n',
  });
  assert.equal(result.status, 0, `expected a passing exit, got ${result.status}\n${result.stdout}${result.stderr}`);
  assert.match(result.stdout, /passed/, 'a passing run must say so');
});

test('the gate exits zero on an empty tree', () => {
  const result = runGate({});
  assert.equal(result.status, 0, `expected a passing exit, got ${result.status}`);
});

test('the gate exits non-zero on the ternary-carried null, not just the literal', () => {
  // The detector cases above all call findNullRequestForwarding directly. That
  // is precisely how a gate can be extended while its wiring quietly rots, so
  // the Batch 819 shape is also asserted through the process boundary.
  const result = runGate({
    'Ternary.java': `class Ternary {\n`
      + '    Object create(Object body, HttpServletRequest httpRequest) { return persist(); }\n'
      + '    Object create(Object body) {\n'
      + '        HttpServletRequest currentRequest =\n'
      + '                holder.getRequestAttributes()\n'
      + '                        instanceof ServletRequestAttributes attributes\n'
      + '                        ? attributes.getRequest()\n'
      + '                        : null;\n'
      + '        return create(body, currentRequest);\n'
      + '    }\n'
      + '    Object persist() { return null; }\n}\n',
  });
  assert.equal(result.status, 1,
    `expected a failing exit, got ${result.status}\n${result.stdout}${result.stderr}`);
  assert.match(result.stdout + result.stderr, /currentRequest/,
    'the report must name the variable the reader has to check by hand');
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
console.log(`\nNull-request forwarding self-test: ${cases.length - failed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
