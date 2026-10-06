#!/usr/bin/env node
// Negative and boundary tests for scripts/verify-interrupt-handling.mjs.
//
// Most of what follows pins *non*-findings. The rule is narrow on purpose: it
// fires only on a `catch` clause that spells `InterruptedException`, and only
// when the block neither rethrows nor restores the flag.
//
// Two of these cases pin the two census directions this batch measured and
// rejected. Both looked like a gate someone should build; both turned out to be
// undecidable by text, and the reason is recorded in the gate header. Pinning
// them here is what stops the next person from re-measuring them from scratch.
//
// One case pins an intentional miss. It is not a pass.

import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, rmSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { findSwallowedInterrupts, scanSource } from '../verify-interrupt-handling.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const GATE = join(here, '..', 'verify-interrupt-handling.mjs');
const REPO = join(here, '..', '..');
const SCAN_ROOTS = [
  'spring-ai-rag-api',
  'spring-ai-rag-core',
  'spring-ai-rag-documents',
  'spring-ai-rag-starter',
].flatMap((m) => [join(REPO, m, 'src/main/java'), join(REPO, m, 'src/test/java')]);

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

/** Wrap statements in a class so the reader sees ordinary Java. */
const cls = (body) => `class Sample {\n${body}\n}\n`;
/** Wrap a catch clause in a method so the reader sees ordinary Java. */
const catchOf = (clause, body) =>
  cls(`    void go() {\n        try {\n            go();\n        } catch ${clause} {\n${body}\n        }\n    }`);

// ── the two legitimate reactions, both must be released ───────────────────

test('releases a catch that restores the interrupt flag', () => {
  assert.deepEqual(
    findSwallowedInterrupts(catchOf('(InterruptedException e)',
      '            Thread.currentThread().interrupt();\n            return null;')),
    [],
  );
});

test('releases a catch that rethrows', () => {
  assert.deepEqual(
    findSwallowedInterrupts(catchOf('(InterruptedException e)',
      '            throw new IllegalStateException("wrapped", e);')),
    [],
  );
});

test('releases whitespace inside the compliant call', () => {
  assert.deepEqual(
    findSwallowedInterrupts(catchOf('(InterruptedException e)',
      '            Thread\n                .currentThread()\n                .interrupt();')),
    [],
  );
});

test('a method that declares throws InterruptedException needs no catch at all', () => {
  assert.deepEqual(
    findSwallowedInterrupts(cls('    void go() throws InterruptedException {\n        go();\n    }')),
    [],
  );
});

// ── rejections ────────────────────────────────────────────────────────────

test('flags a catch that swallows the interrupt', () => {
  const found = findSwallowedInterrupts(catchOf('(InterruptedException e)',
    '            return null;'));
  assert.equal(found.length, 1);
  assert.equal(found[0].line, 5);
});

test('flags an empty catch', () => {
  assert.equal(findSwallowedInterrupts(catchOf('(InterruptedException ignored)', '')).length, 1);
});

test('flags the fully-qualified type', () => {
  assert.equal(
    findSwallowedInterrupts(catchOf('(java.lang.InterruptedException e)', '            return null;')).length,
    1,
  );
});

test('flags a final-modified parameter', () => {
  assert.equal(
    findSwallowedInterrupts(catchOf('(final InterruptedException e)', '            return null;')).length,
    1,
  );
});

// This is the shape the first probe missed. It reported "main: 0 violations"
// while MarkerPdfConverter.isAvailable() was breaking exactly this rule, so the
// multi-catch arm is pinned separately rather than folded into the cases above.
test('flags a multi-catch that swallows the interrupt', () => {
  const found = findSwallowedInterrupts(catchOf('(IOException | InterruptedException e)',
    '            log.debug("Marker CLI not available: {}", e.getMessage());\n            return false;'));
  assert.equal(found.length, 1);
  assert.equal(found[0].types, 'IOException | InterruptedException');
});

test('releases a multi-catch that restores the flag', () => {
  assert.deepEqual(
    findSwallowedInterrupts(catchOf('(IOException | InterruptedException e)',
      '            if (e instanceof InterruptedException) {\n'
      + '                Thread.currentThread().interrupt();\n'
      + '            }\n'
      + '            return false;')),
    [],
  );
});

test('rejects a multi-catch that mentions neither', () => {
  assert.deepEqual(
    findSwallowedInterrupts(catchOf('(IllegalStateException | DataAccessException e)',
      '            return null;')),
    [],
  );
});

// ── the two census directions this batch measured and rejected ────────────

// Rejected direction 1: "empty catch in a test". Five empty catch blocks exist
// in the test tree; at least four are legitimate — three are checked-exception
// catches the *compiler* requires for Mockito's thenThrow, and one deliberately
// drops a single failed request in a concurrency benchmark that asserts on
// successCount. A text rule cannot tell those apart from a swallow.
test('does not flag an empty catch for an unrelated type', () => {
  assert.deepEqual(findSwallowedInterrupts(catchOf('(IOException ignored)', '')), []);
});

// ── the scanner must not be fooled ────────────────────────────────────────

test('a compliant call written in a comment does not rescue the block', () => {
  const found = findSwallowedInterrupts(catchOf('(InterruptedException e)',
    '            // Thread.currentThread().interrupt();\n            return null;'));
  assert.equal(found.length, 1, 'comments are stripped, so they cannot supply the reaction');
});

test('a compliant call inside a block comment does not rescue the block', () => {
  const found = findSwallowedInterrupts(catchOf('(InterruptedException e)',
    '            /* Thread.currentThread().interrupt(); */\n            return null;'));
  assert.equal(found.length, 1);
});

test('a compliant call inside a string literal does not rescue the block', () => {
  const found = findSwallowedInterrupts(catchOf('(InterruptedException e)',
    '            log.error("we call Thread.currentThread().interrupt() but do not");'));
  assert.equal(found.length, 1, 'literals are masked, so they cannot supply the reaction');
});

test('an unbalanced brace inside a literal does not derail block extraction', () => {
  assert.deepEqual(
    findSwallowedInterrupts(catchOf('(InterruptedException e)',
      '            log.error("closing brace: }");\n            Thread.currentThread().interrupt();')),
    [],
    'the reaction sits after the literal, so a naive brace count would miss it',
  );
});

test('a swallow after an unbalanced brace in a literal is still found', () => {
  assert.equal(
    findSwallowedInterrupts(catchOf('(InterruptedException e)',
      '            log.error("closing brace: }");\n            return null;')).length,
    1,
  );
});

// ── documented limits: misses, deliberately pinned ────────────────────────

// Limit 1. `catch (Throwable t)` swallowing an interrupt is textually identical
// to swallowing any other exception. This is an intentional miss, not a pass.
test('documents that catch (Throwable) swallowing an interrupt is invisible', () => {
  assert.deepEqual(
    findSwallowedInterrupts(catchOf('(Throwable t)', '            return null;')),
    [],
    'known limit: only a spelled-out InterruptedException is in scope',
  );
});

// Limit 2. The reaction is matched as text, not as control flow: a `throw`
// that some inner catch intercepts before it escapes still reads as compliant.
// This repository has no such shape; the case exists so the limit stays honest.
//
// The first fixture written for this case was wrong about the direction of the
// miss and said so in its own failure: `catch (InterruptedException e) { try {
// throw e; } catch (InterruptedException inner) {} }` leaves the *outer* block
// compliant but gets the *inner* empty catch flagged on its own. The gate is not
// silent there — it reports the swallow that actually happens. What it cannot do
// is decide whether a `throw` escapes, so the fixture below puts the throw
// somewhere it provably does not.
test('documents that a throw intercepted by an inner catch reads as compliant', () => {
  assert.deepEqual(
    findSwallowedInterrupts(catchOf('(InterruptedException e)',
      '            try {\n                throw new IllegalStateException("unrelated");\n'
      + '            } catch (RuntimeException ignored) {\n                // dropped\n            }')),
    [],
    'known limit: the reaction is matched as text, so control flow is not analysed',
  );
});

// ── the accepting set, printed so a wrong number cannot hide ──────────────

test('the real four-module tree is clean, and its clause count is measured', () => {
  let considered = 0;
  const findings = [];
  let files = 0;

  const walk = (dir) => {
    for (const entry of readdirSync(dir)) {
      const full = join(dir, entry);
      if (statSync(full).isDirectory()) walk(full);
      else if (entry.endsWith('.java')) {
        files += 1;
        const scan = scanSource(readFileSync(full, 'utf8'));
        considered += scan.considered;
        for (const f of scan.findings) findings.push(`${full}:${f.line}`);
      }
    }
  };
  SCAN_ROOTS.forEach(walk);

  console.log(`    accepting set: ${files} file(s), ${considered} InterruptedException clause(s)`);
  assert.deepEqual(findings, [], 'the corpus must be clean');
  // Measured on this branch: 20 in src/main + 10 in src/test = 30. Asserted as a
  // floor rather than an equality so that adding one more correct clause does
  // not turn this case red; a *drop* to a much lower number would mean the
  // scanner stopped seeing the corpus at all.
  assert.ok(considered >= 30,
    `expected at least the 30 clauses measured on this branch, saw ${considered}`);
});

// ── the reporting path, both directions ───────────────────────────────────

function runGate(files) {
  const dir = mkdtempSync(join(tmpdir(), 'interrupt-gate-'));
  for (const [name, body] of Object.entries(files)) {
    writeFileSync(join(dir, name), body, 'utf8');
  }
  const result = spawnSync(process.execPath, [GATE], {
    env: { ...process.env, INTERRUPT_HANDLING_ROOT: dir },
    encoding: 'utf8',
  });
  rmSync(dir, { recursive: true, force: true });
  return result;
}

test('the gate exits zero on a clean tree and counts what it looked at', () => {
  const result = runGate({
    'Good.java': catchOf('(InterruptedException e)',
      '            Thread.currentThread().interrupt();\n            return null;'),
    'Unrelated.java': catchOf('(IOException e)', '            return null;'),
  });
  assert.equal(result.status, 0, `expected a passing exit, got ${result.status}\n${result.stdout}${result.stderr}`);
  assert.match(result.stdout, /1 InterruptedException catch clause\(s\)/);
});

// The reverse probe: take a real production file, append one violating clause,
// and require the gate to go red. A mutation that does not fire proves the
// detector is not reading that file.
test('the gate goes red on a real production file plus one violating clause', () => {
  const source = readFileSync(
    join(REPO, 'spring-ai-rag-core/src/main/java/com/springairag/core/service/pdf/MarkerPdfConverter.java'),
    'utf8');
  const result = runGate({
    'MarkerPdfConverter.java':
      `${source}\nextra class Probe {\n    void go() {\n        try {\n            go();\n`
      + '        } catch (InterruptedException ignored) {\n            // dropped\n        }\n    }\n}\n',
  });
  assert.equal(result.status, 1,
    `expected a failing exit, got ${result.status}\n${result.stdout}${result.stderr}`);
  const out = result.stdout + result.stderr;
  assert.match(out, /\[interrupt-handling\]/,
    'the violation must be reported, not merely counted');
  assert.match(out, /never sees the cancellation again/,
    'the report must say why swallowing an interrupt is dangerous');
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
console.log(`\nInterrupt handling self-test: ${cases.length - failed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);