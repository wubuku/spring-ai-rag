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

// ── Batch 883: the three shapes the old implementation was blind to ───────
//
// Each case below isolates exactly one of the three loosenings, so a red run
// says which one is missing rather than "the scanner broke". All three were
// confirmed against the real tree before they were written down, and each is
// a shape that exists in this repository — not a shape invented to be easy.
//
// A note on how these were measured, because the first attempt got it wrong in
// a way that would have looked like a much bigger finding: comparing the old
// and new comment strippers *line by line* reported 64516 differing lines and
// 3051 lost declarations. All of it was an artefact. The old stripper replaced
// comments with the empty string, so a multi-line block comment collapsed and
// every subsequent line number shifted — the Batch 877 trap, repeated. The
// real number is 1: one file (WebUiConfig.java), and the direction is a pure
// miss, never an invented finding. Comparisons in this batch are therefore by
// scan result, not by line text.

test('sees a declaration whose signature ends in a throws clause', () => {
  // The old pattern demanded the body's brace immediately after the closing
  // paren, so every `throws` declaration was invisible. Five exist in the real
  // tree, and all five are filter/interceptor entry points — the outermost
  // authorization boundary, which is the last place that should tolerate a
  // missing request context.
  const src = cls(`
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(request, chain);
    }
    void doFilterInternal(FilterChain chain) {
        doFilterInternal(null, chain);
    }`);
  const hits = findNullRequestForwarding(src);
  assert.equal(hits.length, 1,
    'a throws clause must not hide the overload that a null is forwarded into');
  assert.equal(hits[0].name, 'doFilterInternal');
});

test('sees a declaration indented deeper than four spaces', () => {
  // Zero occurrences in the real tree today — all 101 request-carrying
  // declarations sit at indent 4. It is still a blind spot worth closing,
  // because the trigger is structural: move a method into an inner class and
  // it becomes permanently invisible, with no change in behaviour to explain
  // why the gate went quiet.
  const src = `class Outer {\n    static class Inner {\n`
    + '        void send(HttpServletRequest httpRequest) { write(); }\n'
    + '        void send() { send(null); }\n'
    + '    }\n}\n';
  const hits = findNullRequestForwarding(src);
  assert.equal(hits.length, 1, 'an inner-class declaration must be collected');
  assert.equal(hits[0].name, 'send');
});

test('a glob in a line comment must not swallow the code that follows', () => {
  // The real defect, reproduced from the second occurrence of the shape in
  // WebUiConfig.java: the old stripper ran its block-comment rule first, found
  // the `/*` inside the glob `assets/**`, and carried on to the next real
  // javadoc terminator. Everything between became "comment" and was deleted.
  //
  // The ordering is the whole point and it was wrong in the first draft of this
  // fixture. Two corrections, both found by running the control and reading
  // which tests went red:
  //
  //   1. The declaration has to sit BETWEEN the glob comment and the javadoc's
  //      terminator, which is where the real file puts it. Put it after the
  //      javadoc and it survives the old stripper, so the test passes on the
  //      version it is supposed to catch.
  //   2. There must be exactly ONE forwarding pair in the file. The first draft
  //      added a second, outside the deleted span, and that second pair was
  //      still detected — so the test stayed green on the broken stripper while
  //      looking like it covered the defect.
  const src = `class WebUiLike {
    // Catch-all for /webui/* paths, but not /webui/assets/* which the handler serves
    public String webuiCatchAll(HttpServletRequest request) { return index(request); }
    public String webuiCatchAll() { return webuiCatchAll(null); }
    /**
     * Serves /webui and /webui/ as the React SPA entry point.
     */
    String index(HttpServletRequest request) { return "index"; }
}
`;
  const hits = findNullRequestForwarding(src);
  assert.equal(hits.length, 1,
    'a glob inside a line comment must not delete the declarations after it');
  assert.equal(hits[0].name, 'webuiCatchAll');
  assert.equal(hits[0].argIndex, 0);
});

test('a url inside a string literal is not a line comment', () => {
  // The quieter half of the same defect. `https://` in a string was read as the
  // start of a comment, so everything to the right of it on that line was
  // deleted — including the forwarding call, when the call shared the line with
  // the default. The overload is declared on its own line, which is the ordinary
  // way this is written, so only the *call* has to be on the payload's line for
  // the bug to cost a finding.
  const src = `class WithUrl {
    void post(String url, Object id, HttpServletRequest httpRequest) { http(url, id, httpRequest); }
    void post(String url, Object id) { post("https://api.example.com/v1/embed", id, null); }
    void http(String url, Object id, HttpServletRequest httpRequest) { write(url, id); }
    void write(String url, Object id) {}
}
`;
  const hits = findNullRequestForwarding(src);
  assert.equal(hits.length, 1, 'a url literal must not blank the rest of its line');
  assert.equal(hits[0].name, 'post');
  assert.equal(hits[0].argIndex, 2);
});

test('an escaped quote inside a string does not desynchronise the scanner', () => {
  // Not a control against the two-regex stripper, which has no scanner state to
  // desynchronise — that is the honest description of this test. It is a guard
  // against the most likely way a future rewrite of `stripComments` breaks: a
  // character scanner that forgets that a backslash escapes the next character.
  // Get that wrong and `\"` ends the string early, the `//` that follows is read
  // as a comment, and the forwarding call on that line is deleted.
  //
  // The call is on the payload's line on purpose: with it on the next line the
  // test passes whether or not the escape is handled, which is the same
  // non-discriminating-fixture mistake the glob case above had.
  const src = `class Escaped {
    void send() { post("quote \\" // "); send(null); }
    void send(HttpServletRequest httpRequest) { post("x"); }
}
`;
  const hits = findNullRequestForwarding(src);
  assert.equal(hits.length, 1, 'an escaped quote must not end the string early');
  assert.equal(hits[0].name, 'send');
});

test('still refuses the shapes that are not declarations', () => {
  // Widening the pattern from "exactly four spaces" to "any indentation" and
  // tolerating a throws clause buys coverage. It must not also buy findings
  // for control flow, which is what would turn this rule into the kind of
  // noise that gets allowlisted.
  const shapes = [
    '    if (ready(x)) { y(); }',
    '    for (HttpServletRequest r : all) { y(); }',
    '    catch (IOException e) { y(); }',
    '    } else { y(); }',
    '    client.send(HttpServletRequest.class) { y(); }',
  ];
  for (const body of shapes) {
    assert.deepEqual(
      findNullRequestForwarding(`class C {\n${body}\n}\n`), [],
      `must not treat this as a declaration: ${body.trim()}`,
    );
  }
});

test('reports the line the call is really on, not the line a collapsed comment left', () => {
  // The old stripper replaced comments with the empty string, so a multi-line
  // block comment collapsed and every reported line number after it was wrong.
  // The report is the only thing a reader has to go on; a line number pointing
  // at the wrong place is a report that cannot be acted on.
  const src = `class Lines {
    /**
     * A block comment
     * spanning several lines
     * before the interesting part.
     */
    void send(HttpServletRequest httpRequest) { write(); }
    void send() {
        send(null);
    }
}
`;
  const hits = findNullRequestForwarding(src);
  assert.equal(hits.length, 1);
  // `send(null)` is on line 9 of the source above. Under the old stripper the
  // five-line block comment collapsed to nothing and this reported line 4.
  assert.equal(hits[0].line, 9, `expected the call on line 9, got ${hits[0].line}`);
});

test('the gate catches it end to end, with all three loosenings needed at once', () => {
  // The cases above call the detector directly. This one runs the real script,
  // because the way a gate rots is by having its detector extended while its
  // wiring quietly stops reaching the extension. Every blind spot has to be
  // closed on all three axes at the same time: a glob in a line comment (so
  // the declarations survive), a throws clause (so the overload is collected),
  // and an inner class (so the indentation is not pinned to four).
  const result = runGate({
    'Three.java': `class Three {
    void serve() {
        // Serve /webui/assets/** from classpath:/static/webui/assets/
        registry.addResourceHandler("/webui/assets/**");
    }
    static class Inner {
        /**
         * Serves /webui and /webui/ as the React SPA entry point.
         */
        Object load(Object id, HttpServletRequest httpRequest) throws IOException {
            return fetch(id);
        }
        Object load(Object id) { return load(id, null); }
    }
}
`,
  });
  assert.equal(result.status, 1,
    `expected a failing exit, got ${result.status}\n${result.stdout}${result.stderr}`);
  assert.match(result.stdout + result.stderr, /null-request-forwarding/,
    'the violation must be reported, not merely counted');
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
