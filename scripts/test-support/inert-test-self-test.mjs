#!/usr/bin/env node
// Negative tests for scripts/verify-test-expectations.mjs.
//
// The census this guards shipped three wrong detectors before it shipped a right
// one — 191 candidates, then 40, then 30, and reading the top of each list showed
// the detector rather than the tests was at fault. Every case below pins one of
// those three mistakes, because each was invisible to a checker-only test:
//
//   1. treating `assertEquals(a, b);` as a method declaration (it is a call);
//   2. treating a `//` inside a string literal as the start of a comment, which
//      would empty out a body that does contain code;
//   3. flagging tests whose whole contract is "this must not throw".
//
// The third is the one the rule deliberately does not encode, so it is pinned as
// a non-finding: an allowlist of twenty-odd "does not throw" tests would have
// been a debt baseline wearing a gate's clothes.

import assert from 'node:assert/strict';
import {
  collectMethods,
  executableStatements,
  findInertTests,
} from '../verify-test-expectations.mjs';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

const wrap = (body, annotation = '@Test') => `
class Fixture {
    ${annotation}
    void sample() {${body}}
}
`;

test('an empty body is inert', () => {
  assert.deepEqual(findInertTests(wrap('')), ['sample']);
});

test('a comment-only body is inert — the shape Batch 809 found three of', () => {
  const source = wrap(`
        // Skip: requires complex varargs mocking. Covered elsewhere.
    `);
  assert.deepEqual(findInertTests(source), ['sample']);
});

test('a body with any statement is not inert', () => {
  assert.deepEqual(findInertTests(wrap(' int x = 1;')), []);
});

test('a test whose contract is "must not throw" is deliberately not inert', () => {
  // JUnit fails a test when an unexpected exception escapes, so the expectation
  // is real even though no assertion API appears. Flagging it would be wrong.
  assert.deepEqual(findInertTests(wrap(' properties.validate();')), []);
});

test('verifyNoInteractions is a statement, not an empty body', () => {
  assert.deepEqual(findInertTests(wrap(' verifyNoInteractions(dep);')), []);
});

test('MockMvc expectations are statements', () => {
  const body = `
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<!doctype html")));
    `;
  assert.deepEqual(findInertTests(wrap(body)), []);
});

test('a hand-rolled AssertionError is a statement', () => {
  assert.deepEqual(findInertTests(wrap(' if (a != b) { throw new AssertionError("x"); }')), []);
});

test('a // inside a string literal is code, not a comment', () => {
  // The mistake that would have emptied a body that does contain code.
  assert.deepEqual(findInertTests(wrap(' String url = "https://example.test/a";')), []);
  assert.notEqual(executableStatements(' String url = "https://example.test/a";'), '');
});

test('a non-test method with an empty body is ignored', () => {
  const source = `
class Fixture {
    private void helper() {
    }
}
`;
  assert.deepEqual(findInertTests(source), []);
});

test('a method annotated only with @DisplayName is not a test', () => {
  const source = wrap('', '@DisplayName("something")');
  assert.deepEqual(findInertTests(source), []);
});

test('a call line is not parsed as a method declaration', () => {
  // Detector mistake 1: matching "looks like a call" reported assertEquals(...),
  // mock(...) and when(...) as methods, which then looked like test methods.
  const source = `
class Fixture {
    void sample() {
        assertEquals(expected, actual);
        when(jdbc.queryForObject(anyString())).thenReturn(1);
        verifyNoInteractions(dep);
    }
}
`;
  const names = collectMethods(source).map((m) => m.name);
  assert.deepEqual(names, ['sample']);
});

test('@ParameterizedTest and @TestFactory count as tests', () => {
  for (const annotation of ['@ParameterizedTest', '@TestFactory', '@RepeatedTest(3)']) {
    assert.deepEqual(findInertTests(wrap('', annotation)), ['sample'], annotation);
  }
});

test('the real test tree has no inert @Test method', () => {
  const root = fileURLToPath(new URL('../..', import.meta.url));
  const dir = join(root, 'spring-ai-rag-core/src/test/java/com/springairag/core/retrieval/fulltext');
  const target = join(dir, 'PgTrgmFulltextProviderTest.java');
  // The class that carried the three, pinned by name so a re-introduction is
  // reported against the file it happened in.
  assert.deepEqual(findInertTests(readFileSync(target, 'utf8')), []);
  assert.ok(readdirSync(dir).includes('PgTrgmFulltextProviderTest.java'));
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
  console.error(`\n${failed}/${cases.length} inert-test self-test case(s) failed.`);
  process.exitCode = 1;
} else {
  console.log(`\nAll ${cases.length} inert-test self-test cases passed.`);
}
