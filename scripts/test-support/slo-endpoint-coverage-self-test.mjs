#!/usr/bin/env node
// Negative tests for scripts/verify-slo-endpoint-coverage.mjs.
//
// A gate that cannot fail is worse than no gate, and this repository has a
// documented habit of producing one — Batch 768 shipped a design document
// claiming "ten classes" while the checker enforced eleven; Batch 790's first
// switch reconciler walked zero files; Batch 802's external-database gate
// imported `resolve` from the wrong module and crashed on import; Batch 806's
// own first cut of this gate reported the `/ask` + `/chat` alias pair as a
// duplicate because it counted occurrences instead of owner files.
//
// The last one is why the collector is exercised directly here rather than only
// through the pure checker: the bug lived in the collection step, and a suite
// that only fed `checkCoverage` a hand-built list would never have seen it.

import assert from 'node:assert/strict';
import {
  checkCoverage,
  collectControllers,
  collectDefaultThresholds,
  collectTimedValues,
  stripComments,
  VIOLATION_KINDS,
} from '../verify-slo-endpoint-coverage.mjs';
import { mkdtempSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

const CHAT_CONTROLLER = `
package com.example;
class RagChatController {
    @PostMapping("/ask")
    @Timed(value = "rag.chat.ask", description = "d")
    public String ask() { return null; }

    @PostMapping
    @Timed(value = "rag.chat.ask", description = "d")
    public String chat() { return null; }

    @PostMapping(value = "/stream")
    @Timed(value = "rag.chat.stream", description = "d")
    public String stream() { return null; }
}
`;

test('accepts thresholds that every @Timed can serve', () => {
  const violations = checkCoverage(
    ['rag.chat.ask', 'rag.chat.stream'],
    [{ name: 'RagChatController.java', source: CHAT_CONTROLLER }],
  );
  assert.deepEqual(violations, []);
});

test('rejects a threshold no @Timed publishes', () => {
  // The exact shape Batch 806 found: the alias carried its own timer, so every
  // call through it was measured and then dropped by getCompliance().
  const violations = checkCoverage(
    ['rag.chat.ask', 'rag.chat.non-stream'],
    [{ name: 'RagChatController.java', source: CHAT_CONTROLLER }],
  );
  assert.equal(violations.length, 1);
  assert.equal(violations[0].kind, VIOLATION_KINDS.STALE_THRESHOLD);
  assert.match(violations[0].detail, /rag\.chat\.non-stream/);
});

test('rejects a renamed endpoint that left its threshold behind', () => {
  const violations = checkCoverage(
    ['rag.documents.embed'],
    [{ name: 'RagDocumentController.java', source: '@Timed(value = "rag.documents.embedding")' }],
  );
  assert.equal(violations.length, 1);
  assert.equal(violations[0].kind, VIOLATION_KINDS.STALE_THRESHOLD);
});

test('rejects one timer name published by two controllers', () => {
  const violations = checkCoverage(
    [],
    [
      { name: 'A.java', source: '@Timed(value = "rag.shared")' },
      { name: 'B.java', source: '@Timed(value = "rag.shared")' },
    ],
  );
  assert.equal(violations.length, 1);
  assert.equal(violations[0].kind, VIOLATION_KINDS.DUPLICATE_METRIC);
  assert.match(violations[0].detail, /A\.java/);
  assert.match(violations[0].detail, /B\.java/);
});

test('accepts the same timer published twice by one controller', () => {
  // The alias form: /ask and /chat are the same operation and say so by
  // sharing a name. Flagging this would forbid the very fix it guards.
  const violations = checkCoverage(
    ['rag.chat.ask'],
    [{ name: 'RagChatController.java', source: CHAT_CONTROLLER }],
  );
  assert.deepEqual(violations, []);
});

test('does not read a @Timed out of a comment', () => {
  const violations = checkCoverage(
    ['rag.ghost'],
    [{ name: 'RagChatController.java', source: '// @Timed(value = "rag.ghost")\nclass X {}' }],
  );
  assert.equal(violations.length, 1);
  assert.equal(violations[0].kind, VIOLATION_KINDS.STALE_THRESHOLD);
});

test('reads @Timed without the value= keyword', () => {
  assert.deepEqual(collectTimedValues('@Timed("rag.bare")'), ['rag.bare']);
  assert.deepEqual(collectTimedValues('@Timed(value="rag.named")'), ['rag.named']);
});

test('collects only the default threshold table, not Javadoc prose', () => {
  const source = `
    /**
     * <ul>
     *   <li>rag.documents.embed: 2000ms</li>
     * </ul>
     */
    ApiSloProperties() {
        thresholds.put("rag.search.post", 500L);
        thresholds.put("rag.chat.ask", 1000L); // thresholds.put("rag.ghost", 1L)
    }
  `;
  assert.deepEqual(collectDefaultThresholds(source), ['rag.search.post', 'rag.chat.ask']);
});

test('stripComments removes block and line comments', () => {
  assert.equal(stripComments('a/* x */b'), 'a b');
  assert.ok(!stripComments('a // x\nb').includes('x'));
});

// ── collector, against a real directory on disk ──────────────────────────────

function withTree(files, fn) {
  const root = mkdtempSync(join(tmpdir(), 'slo-cov-'));
  try {
    for (const [relPath, content] of Object.entries(files)) {
      const full = join(root, relPath);
      mkdirSync(join(full, '..'), { recursive: true });
      writeFileSync(full, content, 'utf8');
    }
    return fn(root);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

test('collectControllers walks nested directories and skips non-java files', () => {
  withTree({
    'a/BController.java': '@Timed(value = "rag.a")',
    'b/c/CController.java': '@Timed(value = "rag.b")',
    'notes.md': '@Timed(value = "rag.markdown")',
  }, (root) => {
    const files = collectControllers(root);
    const names = files.map((f) => f.name).sort();
    assert.deepEqual(names, ['a/BController.java', 'b/c/CController.java']);
    const total = files.reduce((n, f) => n + collectTimedValues(f.source).length, 0);
    assert.equal(total, 2);
  });
});

test('collectControllers on a missing directory yields nothing instead of throwing', () => {
  assert.deepEqual(collectControllers(join(tmpdir(), 'slo-cov-does-not-exist-xyz')), []);
});

test('a name repeated in one file is not reported as a cross-controller duplicate', () => {
  // Regression for Batch 806: counting occurrences instead of owner files made
  // the gate reject the real repository on its first run.
  withTree({
    'RagChatController.java': CHAT_CONTROLLER,
    'RagSearchController.java': '@Timed(value = "rag.search.post")',
  }, (root) => {
    const violations = checkCoverage(['rag.chat.ask', 'rag.search.post'], collectControllers(root));
    assert.deepEqual(violations, []);
  });
});

let failed = 0;
for (const { title, fn } of cases) {
  try {
    fn();
    console.log(`ok   ${title}`);
  } catch (error) {
    failed += 1;
    console.error(`FAIL ${title}`);
    console.error(String(error && error.message ? error.message : error));
  }
}

if (failed > 0) {
  console.error(`\n${failed}/${cases.length} SLO endpoint coverage self-test case(s) failed.`);
  process.exitCode = 1;
} else {
  console.log(`\nAll ${cases.length} SLO endpoint coverage self-test cases passed.`);
}
