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

// ── Batch 888: a quote in a comment used to swallow the method ────────────
//
// `bodyFrom` counted braces from a method's opening brace and stepped over
// string literals. It did not step over comments, so a `//` note containing a
// quote — the apostrophe in "the caller's assertEquals" — was read as a string
// delimiter, the scan ran off looking for a partner quote, and the method came
// back with no body at all. Both rules skip a method with no body, so the method
// was invisible. Eighteen real @Test methods across fourteen files were in that
// state, and the gate was reporting 8047 test methods when the tree holds 8065.

test('an empty @Test behind a quoted comment is still reported', () => {
  // The decisive case. Before the fix this returned []: the method was not
  // found to be empty, it was not found at all, which is the worse answer
  // because it looks like a clean tree.
  const source = `
class Fixture {
    @Test
    void sample() {
        // a note about the caller's own call
    }
}
`;
  assert.deepEqual(findInertTests(source), ['sample']);
});

test('an empty @Test behind a quoted block comment is still reported', () => {
  // The block-comment skip earns its place only when the comment holds a quote
  // with no partner. A comment quoting "9" and "59" is balanced, so skipping
  // string literals alone already reads it correctly — an earlier version of
  // this case used exactly that shape and stayed green when the block-comment
  // skip was deleted, which is how a fixture shows it is decoration.
  const source = `
class Fixture {
    @Test
    void sample() {
        /* the caller's own copy */
    }
}
`;
  assert.deepEqual(findInertTests(source), ['sample']);
});

test('a non-empty @Test behind a quoted comment is not reported', () => {
  // The negative for the same fix, and the one that would catch an over-correction
  // that reported every method whose neighbourhood contained an apostrophe.
  const source = `
class Fixture {
    @Test
    void sample() {
        // the caller's own call
        assertEquals(1, 1);
    }
}
`;
  assert.deepEqual(findInertTests(source), []);
});

test('a brace inside a comment does not end the body early', () => {
  // If the closing brace were counted from inside a comment, this body would be
  // read as ending at the note and `assertEquals` would fall outside it.
  const source = `
class Fixture {
    @Test
    void sample() {
        // a stray } brace in prose
        assertEquals(1, 1);
    }
}
`;
  assert.deepEqual(findInertTests(source), []);
  assert.deepEqual(findUnrunnableTests(source), []);
});

test('a closing brace inside a string literal does not end the body', () => {
  // The same question for the string skip, asked so that deleting it turns this
  // red. A URL cannot answer it — it holds no brace, so the body is read the
  // same way with or without the skip, and a fixture built on one proves
  // nothing. A `}` inside the literal decrements the depth to zero and the body
  // stops one line early, which is observable.
  const source = `
class Fixture {
    void sample() {
        String template = "}";
        int after = 1;
    }
}
`;
  const body = collectMethods(source)[0].body;
  assert.ok(body.includes('int after'), `body ended early: ${JSON.stringify(body)}`);
});

test('a // comment containing */ does not start a block comment', () => {
  // A line comment and a block comment are told apart by the character after the
  // slash, so the two branches cannot shadow each other and their order is not a
  // decision worth a mutation. What is worth keeping is that the line-comment
  // branch runs to the newline rather than to the `*/` the note mentions.
  const source = `
class Fixture {
    @Test
    void sample() {
        // the terminator is */
        assertEquals(1, 1);
    }
}
`;
  assert.deepEqual(findInertTests(source), []);
});

test('a // inside a string literal is still code, not a comment', () => {
  // The mirror: the quote is what protects the slashes, and the comment is what
  // protects the quotes. Both have to be honoured, comment first, or the two
  // quoted-comment cases above go red.
  const source = `
class Fixture {
    @Test
    void sample() {
        String url = "https://example.test/a";
        assertEquals(1, 1);
    }
}
`;
  assert.deepEqual(findInertTests(source), []);
});

test('a declaration whose body never closes is not reported as a method', () => {
  // The contract behind the `return null` at the end of the scan. Nothing in the
  // real tree reaches it, which is the point: a declaration the parser cannot
  // close is dropped rather than reported with a partial body, so a truncated
  // or malformed file costs coverage silently instead of producing a finding
  // that names a method whose contents were never read. Asserting the method is
  // absent, rather than absent from the findings, is what makes the difference
  // between dropping it and truncating it observable.
  const source = `
class Fixture {
    @Test
    void sample() {
        assertEquals(1, 1);
`;
  assert.deepEqual(collectMethods(source), []);
  assert.deepEqual(findInertTests(source), []);
  assert.deepEqual(findUnrunnableTests(source), []);
});

// ── Batch 890: a control-flow statement is not a method declaration ────────
//
// `DECLARATION_HEAD` is anchored at a line and matches "modifiers, annotations,
// a type, a name and an open paren", none of which a statement is required to
// have. `if (x) {` therefore matched with an empty type, and the block after it
// was read as a method body. Measured on the real tree, 579 of 12150 reported
// "declarations" were statements or anonymous classes.

test('an if block is not a method declaration', () => {
  const source = `
class Fixture {
    @Test
    void sample() {
        if (flag) {
            assertEquals(1, 1);
        }
    }
}
`;
  assert.deepEqual(collectMethods(source).map((m) => m.name), ['sample']);
});

test('a for loop and a while loop are not method declarations', () => {
  const source = `
class Fixture {
    @Test
    void sample() {
        for (int i = 0; i < 3; i++) {
            use(i);
        }
        while (flag) {
            use(1);
        }
    }
}
`;
  assert.deepEqual(collectMethods(source).map((m) => m.name), ['sample']);
});

test('an anonymous class body is not a method declaration', () => {
  // The one statement shape that carries a `{` and so survives every other
  // check. The name the pattern captures is the class being instantiated, so
  // the keyword test cannot see it; what betrays it is the word sitting where a
  // return type goes. Before the fix this source reported `Advisor` — the
  // anonymous class body read as a method named after the class. The override
  // inside it is a real declaration and is still found, which is why the
  // expectation is two names and not one.
  const source = `
class Fixture {
    @Test
    void sample() {
        return new Advisor() {
            @Override
            public String name() {
                return "x";
            }
        };
    }
}
`;
  assert.deepEqual(collectMethods(source).map((m) => m.name), ['sample', 'name']);
});

test('a method whose name merely starts with a keyword is still a method', () => {
  // The over-rejection check, and the one that matters most: a keyword filter
  // that matched prefixes would drop `format`, `variable`, `newborn` and
  // `record` — real methods, dropped silently, with nothing reporting the
  // absence. A filter that works leaves them alone.
  const source = `
class Fixture {
    void format() { }
    void variable() { }
    void newborn() { }
    void record() { }
    void forEach() { }
    void ifPresent() { }
    void switchOn() { }
}
`;
  assert.deepEqual(
    collectMethods(source).map((m) => m.name),
    ['format', 'variable', 'newborn', 'record', 'forEach', 'ifPresent', 'switchOn'],
  );
});

test('a constructor and a private @Test are still found', () => {
  // The two shapes whose head is unusual — a constructor has no return type at
  // all, and a test the gate has to report has modifiers. Both must survive.
  const source = `
class Fixture {
    private Fixture() { }

    @Test
    private void sample() { }
}
`;
  assert.deepEqual(collectMethods(source).map((m) => m.name), ['Fixture', 'sample']);
  assert.deepEqual(findUnrunnableTests(source), ['sample']);
});

test('the real tree count includes the methods the apostrophe used to hide', () => {
  // Pinned against the tree rather than against a number, so it cannot rot. The
  // eighteen recovered methods are not listed: the point is that the census
  // recognises every @Test in the tree, and the census is what counts them.
  const root = fileURLToPath(new URL('../..', import.meta.url));
  const { findings, files, tests } = collectFindings(root);
  assert.deepEqual(findings, [], 'the real tree has no inert or unrunnable @Test');
  assert.ok(files > 900, `expected the real tree to be scanned, only saw ${files} file(s)`);
  assert.ok(tests > 8000, `expected the scan to recognise @Test methods, saw ${tests}`);
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
