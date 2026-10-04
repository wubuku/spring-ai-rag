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
  collectFindings,
  collectMethods,
  executableStatements,
  findInertTests,
  findUnrunnableTests,
} from '../verify-test-expectations.mjs';
import { readFileSync, readdirSync, statSync } from 'node:fs';
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

// ── Batch 887: `@Test` methods JUnit 5 never runs ────────────────────────
//
// The rule added here answers a different question from the one above, so it is
// pinned separately. An empty body is a test that runs and proves nothing; a
// `private` or `static` `@Test` is a test that does not run at all and does not
// even appear in the report. The second is strictly worse — a green build and a
// shrinking test count — and it is also the one a person cannot see by reading
// the report, because there is no report row to read.

const unrunnable = (modifiers) => `
class Fixture {
    @Test
    ${modifiers}void sample() { assertEquals(1, 1); }
}
`;

test('a private @Test is reported — JUnit 5 will not discover it', () => {
  assert.deepEqual(findUnrunnableTests(unrunnable('private ')), ['sample']);
});

test('a static @Test is reported for the same reason', () => {
  assert.deepEqual(findUnrunnableTests(unrunnable('static ')), ['sample']);
});

test('a private static @Test is reported once, not twice', () => {
  assert.deepEqual(findUnrunnableTests(unrunnable('private static ')), ['sample']);
});

test('a private helper is not a test, however many tests sit above it', () => {
  // The negative that matters, and the one that decides the whole implementation.
  // The empty-body rule decides "is this a test" from the text between one
  // declaration and the next, and that window is bounded by the previous
  // declaration's *parameter list*, not its body. So a private helper sitting
  // after a test inherits that test's `@Test` and looks like a skipped test.
  // Reading from the whole file prefix instead of the contiguous block above
  // reported 743 such helpers on the real tree. Reading the lines directly above
  // the declaration is what makes this empty.
  const source = `
class Sample {
    @Test
    void sample() { assertEquals(1, 1); }

    private String helper() { return "x"; }
}
`;
  assert.deepEqual(findUnrunnableTests(source), []);
});

test('a private helper above the first test is not a test either', () => {
  // The same mistake reached from the other end: for the first declaration in a
  // file the gap starts at byte 0, so a helper under the class header inherits
  // whatever `@Test` text the file's preamble contains.
  const source = `
import org.junit.jupiter.api.Test;

class Sample {
    private String helper() { return "x"; }

    @Test
    void sample() { assertEquals(1, 1); }
}
`;
  assert.deepEqual(findUnrunnableTests(source), []);
});

test('a @Test named in the file header comment is not an annotation', () => {
  // The negative control on where the upward walk stops. A header comment that
  // talks about `@Test` is exactly the text a whole-prefix scan would pick up,
  // and the first private member of the class is exactly what it would then
  // report as a skipped test.
  const source = `
/*
 * Every @Test method in this class is package-private by design.
 */
class Sample {
    private String helper() { return "x"; }
}
`;
  assert.deepEqual(findUnrunnableTests(source), []);
});

test('a comment between the annotation and the declaration does not break the walk', () => {
  // The walk must skip comments, not just blank lines, or an annotation
  // separated from its method by a one-line note is silently missed.
  const source = `
class Sample {
    @Test
    // a note somebody left between the annotation and the method
    private void sample() { }
}
`;
  assert.deepEqual(findUnrunnableTests(source), ['sample']);
});

test('an empty @BeforeEach is not an unrunnable test', () => {
  // It is not a test at all, and the rule under test is about tests JUnit skips.
  // The empty-body rule has its own opinion about it and stays out of this one.
  assert.deepEqual(findUnrunnableTests(wrap('', '@BeforeEach')), []);
});

test('an ordinary package-private @Test is not reported', () => {
  assert.deepEqual(findUnrunnableTests(wrap('assertEquals(1, 1);')), []);
});

test('a wrapped @ParameterizedTest above a private method is still found', () => {
  // The upward scan walks lines, so an annotation whose arguments span several
  // lines puts a `})` between the annotation and the declaration. The
  // paren-balance carry is what keeps this a hit instead of a silent miss.
  const source = `
class Sample {
    @ParameterizedTest
    @CsvSource({
        "1, 1",
        "2, 2"
    })
    private void sample() { }
}
`;
  assert.deepEqual(findUnrunnableTests(source), ['sample']);
});

test('the real test tree has no @Test that JUnit would skip — and the scan is not vacuous', () => {
  // Two claims in one test, because the first alone passes for the wrong reason.
  // An earlier version of this case asserted only "no findings", and it passed
  // while the rule matched *zero* test methods in the entire repository: the
  // annotation was being read off a capture group that could never fire, since
  // its separator class `[\\t ]+` does not match the newline between `@Test` and
  // `void`. A census that reports nothing because it recognised nothing is
  // indistinguishable from a clean tree, so the population is asserted too.
  const root = fileURLToPath(new URL('../..', import.meta.url));
  const { findings, files, tests } = collectFindings(root);
  assert.deepEqual(
    findings.filter((f) => f.kind === 'unrunnable'),
    [],
  );
  assert.ok(files > 900, `expected the real tree to be scanned, only saw ${files} file(s)`);
  assert.ok(tests > 5000, `expected the scan to recognise @Test methods, saw ${tests}`);
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
