#!/usr/bin/env node
// Negative tests for scripts/verify-test-visibility.mjs.
//
// A gate that cannot fail is worse than no gate, and this repository has a
// documented history of producing exactly that defect. These cases therefore
// assert that the checker *rejects* the shape it claims to reject — notably the
// `tests="0" skipped="0"` pair that an `assumeTrue` gate in `@BeforeAll`
// produces, which is indistinguishable from an empty class in the run summary.

import assert from 'node:assert/strict';
import {
  audit,
  parseReport,
  reconcile,
  collectSourceTestClasses,
  sourceRootFor,
  TEST_FILE_PATTERN,
} from '../verify-test-visibility.mjs';
import {
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
  existsSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));

const suite = (name, rest) =>
  `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite version="3.0.2" name="${name}" ${rest}></testsuite>\n`;

/**
 * A report whose children are derived from the attributes it is given.
 *
 * This used to emit an empty `<testsuite>` carrying nothing but attributes,
 * which was fine while `parseReport` read `tests=` and stopped being true the
 * moment it counted `<testcase>` elements: every case in this file would then
 * have described a report with no test cases in it. Deriving the children means
 * the fixture cannot disagree with itself, and it means a case that says
 * `tests="21" skipped="21"` really contains twenty-one skipped cases rather than
 * asserting that a number in an attribute is believed.
 */
const report = (name, rest) => {
  const number = (key) => {
    const found = new RegExp(`\\b${key}="(\\d+)"`).exec(rest);
    return found ? Number.parseInt(found[1], 10) : 0;
  };
  const total = number('tests');
  const skipped = number('skipped');
  const failures = number('failures');
  const errors = number('errors');
  const children = [];
  for (let i = 0; i < total; i += 1) {
    let inner = '';
    if (i < errors) inner = '<error message="e" />';
    else if (i < errors + failures) inner = '<failure message="f" />';
    else if (i < errors + failures + skipped) inner = '<skipped />';
    children.push(`<testcase name="case${i}" classname="${name}">${inner}</testcase>`);
  }
  return {
    file: `TEST-${name}.xml`,
    xml: `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite version="3.0.2" name="${name}" ${rest}>\n`
      + `${children.join('\n')}\n</testsuite>\n`,
  };
};

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

test('parseReport reads every counter off the testsuite element', () => {
  const parsed = parseReport(
    report('com.example.Foo', 'tests="4" failures="1" errors="0" skipped="2"').xml,
  );
  assert.deepEqual(parsed, {
    name: 'com.example.Foo',
    tests: 4,
    skipped: 2,
    failures: 1,
    errors: 0,
  });
});

test('parseReport rejects a document with no testsuite element', () => {
  assert.equal(parseReport('<?xml version="1.0"?><nope/>'), null);
});

test('a gate-aborted class is flagged, not tolerated', () => {
  // The regression this whole gate exists for.
  const { invisible } = audit([report('com.example.Ghost', 'tests="0" skipped="0"')]);
  assert.deepEqual(invisible.map(r => r.name), ['com.example.Ghost']);
});

test('a class that reported its own skip is accepted', () => {
  const { invisible } = audit([report('com.example.OptIn', 'tests="21" skipped="21"')]);
  assert.deepEqual(invisible, []);
});

test('a class that ran tests is accepted', () => {
  const { invisible } = audit([report('com.example.Real', 'tests="7" skipped="0"')]);
  assert.deepEqual(invisible, []);
});

test('totals are summed across every class', () => {
  const { totals } = audit([
    report('com.example.A', 'tests="10" skipped="3" failures="0" errors="0"'),
    report('com.example.B', 'tests="5" skipped="0" failures="2" errors="1"'),
  ]);
  assert.deepEqual(totals, { classes: 2, tests: 15, skipped: 3, failures: 2, errors: 1 });
});

test('one ghost class among many healthy ones is still caught', () => {
  const { invisible } = audit([
    report('com.example.A', 'tests="10" skipped="0"'),
    report('com.example.Ghost', 'tests="0" skipped="0"'),
    report('com.example.OptIn', 'tests="21" skipped="21"'),
  ]);
  assert.deepEqual(invisible.map(r => r.name), ['com.example.Ghost']);
});

test('malformed reports are dropped rather than crashing the gate', () => {
  const { invisible, totals } = audit([
    { file: 'TEST-broken.xml', xml: 'not xml at all' },
    report('com.example.Real', 'tests="2" skipped="0"'),
  ]);
  assert.deepEqual(invisible, []);
  assert.equal(totals.classes, 1);
});

test('reconcile flags a source class that never ran', () => {
  // The failure this gate was created for, in the direction it could not see:
  // the class exists in the source tree and simply produced no report.
  const { unreported, ghosts } = reconcile(
    [
      {
        file: '/src/RanTest.java',
        packageName: 'com.example',
        primary: 'Ran',
        classNames: ['Ran'],
      },
      {
        file: '/src/NeverRanTest.java',
        packageName: 'com.example',
        primary: 'NeverRan',
        classNames: ['NeverRan'],
      },
    ],
    [{ name: 'com.example.Ran', file: 'TEST-com.example.Ran.xml' }],
  );
  assert.deepEqual(unreported, ['com.example.NeverRan']);
  assert.deepEqual(ghosts, []);
});

test('reconcile flags a report whose source is gone', () => {
  // A deleted test class leaves its report behind, inflating every total the
  // gate prints. This is the shape Batch 784 tripped over with its throwaway
  // diagnostic classes.
  const { unreported, ghosts } = reconcile(
    [
      {
        file: '/src/RanTest.java',
        packageName: 'com.example',
        primary: 'Ran',
        classNames: ['Ran'],
      },
    ],
    [
      { name: 'com.example.Ran', file: 'TEST-com.example.Ran.xml' },
      { name: 'com.example.Deleted', file: 'TEST-com.example.Deleted.xml' },
    ],
  );
  assert.deepEqual(ghosts, ['com.example.Deleted']);
  assert.deepEqual(unreported, []);
});

test('reconcile accepts a fully matched tree', () => {
  const { unreported, ghosts } = reconcile(
    [
      {
        file: '/src/ATest.java',
        packageName: 'com.example',
        primary: 'A',
        classNames: ['A'],
      },
      {
        file: '/src/BTest.java',
        packageName: 'com.example',
        primary: 'B',
        classNames: ['B'],
      },
    ],
    [
      { name: 'com.example.A', file: 'TEST-com.example.A.xml' },
      { name: 'com.example.B', file: 'TEST-com.example.B.xml' },
    ],
  );
  assert.deepEqual(unreported, []);
  assert.deepEqual(ghosts, []);
});

test('a nested class without its own report is not called unreported', () => {
  // Surefire does not report every `@Nested` class in a separate file: in this
  // repository `RetrievalEvaluationServiceImplTest` folds six of them into the
  // parent's report. Only a file's primary class is required to have one, or the
  // gate would be permanently red.
  const { unreported, ghosts } = reconcile(
    [
      {
        file: '/src/OuterTest.java',
        packageName: 'com.example',
        primary: 'Outer',
        classNames: ['Folded', 'Outer'],
      },
    ],
    [{ name: 'com.example.Outer', file: 'TEST-com.example.Outer.xml' }],
  );
  assert.deepEqual(unreported, []);
  assert.deepEqual(ghosts, []);
});

test('a nested class that does get its own report is not a ghost', () => {
  // The opposite grouping: `HybridRetrieverServiceTest`'s nested classes each
  // earn their own report, named without the outer-class prefix.
  const { unreported, ghosts } = reconcile(
    [
      {
        file: '/src/OuterTest.java',
        packageName: 'com.example',
        primary: 'Outer',
        classNames: ['Outer', 'OwnReport'],
      },
    ],
    [
      { name: 'com.example.Outer', file: 'TEST-com.example.Outer.xml' },
      { name: 'com.example.OwnReport', file: 'TEST-com.example.OwnReport.xml' },
    ],
  );
  assert.deepEqual(unreported, []);
  assert.deepEqual(ghosts, []);
});

test('collectSourceTestClasses finds nested and sibling classes, not prose', () => {
  // Batch 885. A source that declares no test method cannot produce a report, so
  // it is no longer collected at all — which means these fixtures had to grow a
  // `@Test`. That is worth recording rather than just fixing: a fixture that
  // stops exercising the rule because the rule gained a precondition is a
  // fixture that has quietly stopped testing anything.
  const dir = mkdtempSync(join(tmpdir(), 'visibility-src-'));
  try {
    mkdirSync(join(dir, 'com', 'example'), { recursive: true });
    writeFileSync(
      join(dir, 'com', 'example', 'OuterTest.java'),
      [
        'package com.example;',
        '// a class keyword in a line comment: class Commented',
        'class OuterTest {',
        '    private record Fixture(String s) {}',
        '    static class PackagePrivateSibling {}',
        '    void helper() { class DeclaredInsideAMethod {} }',
        '    @Test',
        '    void ok() { }',
        '    @Nested',
        '    class FoldedIntoParent {}',
        '}',
        'class TopLevelSibling {}',
      ].join('\n'),
    );
    writeFileSync(
      join(dir, 'com', 'example', 'QuotedTest.java'),
      [
        'package com.example;',
        'class QuotedTest {',
        '    @Test',
        '    void ok() { }',
        '    String s = "class InAString";',
        "    char c = ';';",
        '    /* class InABlockComment */',
        '}',
      ].join('\n'),
    );

    const byFile = new Map(
      collectSourceTestClasses(dir).map(f => [f.packageName + '.' + f.primary, f]),
    );

    assert.deepEqual(byFile.get('com.example.OuterTest').classNames.sort(), [
      'FoldedIntoParent',
      'OuterTest',
      'TopLevelSibling',
    ]);
    // `PackagePrivateSibling` is nested one level, `DeclaredInsideAMethod` and
    // `Fixture` are not reportable, and nothing from comments or strings leaks in.
    assert.deepEqual(byFile.get('com.example.QuotedTest').classNames, ['QuotedTest']);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('collectSourceTestClasses skips abstract bases and non-test sources', () => {
  // `AbstractIntegrationTest` is compiled but never instantiated, so surefire
  // writes no report for it; counting it as unreported would make the
  // reconciliation permanently red. The two real classes below need a `@Test`
  // for the same reason as of Batch 885 — a source with no test method produces
  // no report either, and requiring one would be asking for the impossible.
  const dir = mkdtempSync(join(tmpdir(), 'visibility-src-'));
  try {
    mkdirSync(join(dir, 'com', 'example'), { recursive: true });
    writeFileSync(
      join(dir, 'com', 'example', 'AbstractBaseTest.java'),
      'package com.example;\nabstract class AbstractBaseTest { @Test void ok() { } }\n',
    );
    writeFileSync(
      join(dir, 'com', 'example', 'RealTest.java'),
      'package com.example;\nclass RealTest { @Test void ok() { } }\n',
    );
    writeFileSync(join(dir, 'com', 'example', 'Helper.java'), 'class Helper {}\n');
    writeFileSync(
      join(dir, 'com', 'example', 'SuiteTests.java'),
      'package com.example;\nclass SuiteTests { @Test void ok() { } }\n',
    );

    const found = collectSourceTestClasses(dir).map(f => f.primary).sort();
    assert.deepEqual(found, ['RealTest', 'SuiteTests']);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ── Batch 885: the include set, and what a source must look like to be in it ──

test('TEST_FILE_PATTERN covers all four of surefire\'s default includes', () => {
  // The doc comment has always listed four patterns. The regex implemented
  // three: `Test*` was missing, so a test class named `TestFoo.java` would be
  // run by surefire and invisible here — and if it stopped running, this gate
  // could not say so.
  for (const name of ['TestFoo.java', 'FooTest.java', 'FooTests.java', 'FooTestCase.java']) {
    assert.equal(TEST_FILE_PATTERN.test(name), true, `${name} is a surefire test source`);
  }
  // `TestSupport.java` and `Tester.java` are deliberately in the *positive* set
  // even though both read like helpers: surefire's `Test*` include really does
  // match anything beginning with "Test". That breadth is exactly why the
  // "declares no test method" exclusion below is load-bearing rather than
  // defensive — without it, every helper named `Test*` would be reported as a
  // test class that produced no report.
  for (const name of ['TestSupport.java', 'Tester.java']) {
    assert.equal(TEST_FILE_PATTERN.test(name), true,
      'surefire includes Test*; being broad here is faithful, not sloppy');
  }
  for (const name of ['Helper.java', 'Foo.java', 'TestFoo.kt', 'FooSpec.java']) {
    assert.equal(TEST_FILE_PATTERN.test(name), false, `${name} is not a surefire test source`);
  }
});

test('a Test* source with a test method is collected and held to a report', () => {
  const dir = mkdtempSync(join(tmpdir(), 'visibility-src-'));
  try {
    mkdirSync(join(dir, 'com', 'example'), { recursive: true });
    writeFileSync(
      join(dir, 'com', 'example', 'TestPrefix.java'),
      'package com.example;\nclass TestPrefix { @Test void ok() { } }\n',
    );
    const found = collectSourceTestClasses(dir);
    assert.equal(found.length, 1);
    assert.equal(found[0].primary, 'TestPrefix');
    // Collected means held: with no report, it is unreported.
    const { unreported } = reconcile(found, []);
    assert.deepEqual(unreported, ['com.example.TestPrefix']);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('a Test* source with no test method is not collected at all', () => {
  // surefire reports nothing for a class with no test method — measured across
  // all four modules, 0 of 1006 reports carry `tests="0"` — so requiring a report
  // would be asking for something impossible. The real file in this shape today
  // is `logging/TestMaskDebug.java`, a `main()` scratchpad.
  const dir = mkdtempSync(join(tmpdir(), 'visibility-src-'));
  try {
    mkdirSync(join(dir, 'com', 'example'), { recursive: true });
    writeFileSync(
      join(dir, 'com', 'example', 'TestScratch.java'),
      'package com.example;\npublic class TestScratch { public static void main(String[] a) { } }\n',
    );
    assert.deepEqual(collectSourceTestClasses(dir), []);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('a test class that nests an abstract helper is still collected', () => {
  // The abstract exclusion used to read `abstract class` **anywhere** in the
  // file and drop the whole file. Surefire never instantiates an abstract class,
  // so excluding one is right — but a real test with an abstract base nested
  // inside it was dropped too, and a test class that then vanished could not be
  // reported, because it was never in the inventory to begin with.
  const dir = mkdtempSync(join(tmpdir(), 'visibility-src-'));
  try {
    mkdirSync(join(dir, 'com', 'example'), { recursive: true });
    writeFileSync(
      join(dir, 'com', 'example', 'WithAbstractBaseTest.java'),
      [
        'package com.example;',
        'class WithAbstractBaseTest {',
        '    abstract static class Base { abstract void run(); }',
        '    @Test',
        '    void ok() { }',
        '}',
      ].join('\n'),
    );
    writeFileSync(
      join(dir, 'com', 'example', 'AbstractTopLevelTest.java'),
      'package com.example;\nabstract class AbstractTopLevelTest { @Test void ok() { } }\n',
    );
    const found = collectSourceTestClasses(dir).map(f => f.primary);
    assert.deepEqual(found, ['WithAbstractBaseTest'],
      'only the genuinely abstract top-level class is excluded');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('a nested class reported under its own simple name is not a ghost', () => {
  // This is why the three classes the gate header calls "deleted from source"
  // are not ghosts. They are not deleted: `AsyncTimeoutFallbackTests` and
  // `FulltextStrategyConfigTests` are `@Nested` classes inside
  // `HybridRetrieverServiceTest`, and `NoOpFulltextSearchProviderTest` is a
  // package-private sibling in `FulltextSearchProviderFactoryTest`. Surefire
  // names a nested report `<package>.<NestedSimpleName>` with no outer prefix,
  // so those report files are legitimate and the declared superset has to
  // accept them. Checking the source was worth the detour — the alternative
  // reading was that ghost detection itself was broken.
  const dir = mkdtempSync(join(tmpdir(), 'visibility-src-'));
  try {
    mkdirSync(join(dir, 'com', 'example'), { recursive: true });
    writeFileSync(
      join(dir, 'com', 'example', 'HybridRetrieverServiceTest.java'),
      [
        'package com.example;',
        'class HybridRetrieverServiceTest {',
        '    @Test void ok() { }',
        '    @Nested',
        '    class AsyncTimeoutFallbackTests { @Test void ok() { } }',
        '}',
      ].join('\n'),
    );
    const found = collectSourceTestClasses(dir);
    const { ghosts, unreported } = reconcile(found, [
      { name: 'com.example.AsyncTimeoutFallbackTests', file: 'TEST-x.xml' },
    ]);
    assert.deepEqual(ghosts, [], 'a nested report is not a ghost');
    // The host class is a separate question and is genuinely unreported here:
    // ghost detection and non-execution detection are independent, and
    // collapsing them would hide one behind the other.
    assert.deepEqual(unreported, ['com.example.HybridRetrieverServiceTest']);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ── the sentence a green run prints ───────────────────────────────────────

test('a passing run names the module it covered and says the rest are outside it', () => {
  // The gate derives its source root from the reports directory it is handed,
  // so one run reconciles one module — and the aggregate entry point hands it
  // core. The old success line read "Source tree reconciled both ways against
  // N declared test class(es)", which reads as a repository-wide conclusion.
  const res = spawnSync(process.execPath, [join(projectRoot, 'scripts/verify-test-visibility.mjs')], {
    encoding: 'utf8',
  });
  assert.equal(res.status, 0, res.stdout + res.stderr);
  assert.match(res.stdout, /passed for spring-ai-rag-core/,
    'the module has to be named, or "passed" reads as repo-wide');
  assert.match(res.stdout, /Other modules are outside this run/);
});

test('sourceRootFor maps a surefire directory back to its module source tree', () => {
  assert.equal(
    sourceRootFor('/repo/spring-ai-rag-core/target/surefire-reports'),
    '/repo/spring-ai-rag-core/src/test/java',
  );
});

test('the real surefire output contains no vanished class', () => {
  // Skipped when the suite has not run here: asserting a count on absent
  // reports would assert nothing at all.
  const dir = join(projectRoot, 'spring-ai-rag-core/target/surefire-reports');
  if (!existsSync(dir)) {
    return;
  }
  const reports = readdirSync(dir)
    .filter(name => name.startsWith('TEST-') && name.endsWith('.xml'))
    .map(name => ({ file: name, xml: readFileSync(join(dir, name), 'utf8') }));

  assert.ok(reports.length > 500, `expected a full suite, saw ${reports.length} reports`);

  const { invisible, totals } = audit(reports);
  assert.deepEqual(
    invisible.map(r => r.name),
    [],
    'test classes that neither ran nor reported a skip',
  );
  // The opt-in integration classes must now be counted, not merely tolerated.
  assert.ok(
    totals.skipped >= 100,
    `expected the opt-in integration classes to report a skip, saw ${totals.skipped}`,
  );

  // The live tree must also reconcile both ways. A `mvn test` that was not
  // preceded by `clean` leaves deleted classes behind as executable ghosts, and
  // this is the only check that notices.
  const sourceClasses = collectSourceTestClasses(sourceRootFor(dir));
  const { unreported, ghosts } = reconcile(
    sourceClasses,
    reports.map(({ file, xml }) => ({ ...parseReport(xml), file })).filter(Boolean),
  );
  assert.deepEqual(
    ghosts,
    [],
    'surefire reports whose source no longer exists (run `mvn clean test`)',
  );
  assert.deepEqual(
    unreported,
    [],
    'test classes in the source tree that produced no report',
  );
});

test('the tests count comes from the elements, not from the attribute', () => {
  // The regression this gate had inside itself. Surefire writes `tests=` before
  // the cases an `@Nested` class contributes, so on the real tree the attribute
  // said 17 where 24 cases ran — and this gate is what prints the number to
  // whoever is deciding whether coverage moved. Measured over all four modules:
  // 3 of 1006 reports disagreed, 8320 declared against 8329 real.
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite version="3.0.2" name="com.example.Nested" tests="2" failures="0" errors="0" skipped="0">
<testcase name="outer" classname="com.example.Nested" />
<testcase name="inner" classname="com.example.Nested$Inner" />
<testcase name="inner2" classname="com.example.Nested$Inner" />
<testcase name="inner3" classname="com.example.Nested$Inner" />
</testsuite>
`;
  assert.equal(parseReport(xml).tests, 4);
});

test('the attribute is never consulted, even when it claims tests the report does not hold', () => {
  // The direction the first case cannot see, and the only one where a fallback
  // shows: a report whose attribute claims nine cases and whose body contains
  // none. Surefire under-reports `tests` for an `@Nested` class, so the
  // realistic disagreement runs the other way once a report is truncated or
  // rewritten — and either way the answer has to come from the children.
  // A parser that falls back to the attribute when it finds none is the
  // pre-893 defect wearing a safety net, and that net turns "ran nothing" into
  // "ran nine tests fine", which is precisely the class of accident this gate
  // was written to name.
  const overstated = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite version="3.0.2" name="com.example.Overstated" tests="9" failures="0" errors="0" skipped="0">
</testsuite>
`;
  assert.equal(parseReport(overstated).tests, 0);
  // Nothing ran and nothing said it was skipping, so this is a class that
  // vanished — the same verdict the honest empty report below gets.
  assert.deepEqual(
    audit([{ file: 'TEST-com.example.Overstated.xml', xml: overstated }]).invisible.map(r => r.name),
    ['com.example.Overstated'],
  );

  // The honest zero, which must keep the verdict it has always had.
  const empty = '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite version="3.0.2" name="com.example.Ghost" tests="0" skipped="0"></testsuite>\n';
  assert.deepEqual(parseReport(empty), {
    name: 'com.example.Ghost', tests: 0, skipped: 0, failures: 0, errors: 0,
  });
  assert.deepEqual(
    audit([{ file: 'TEST-com.example.Ghost.xml', xml: empty }]).invisible.map(r => r.name),
    ['com.example.Ghost'],
  );
});

test('a tag inside a logged message cannot inflate the count', () => {
  // The count is a string split, so the one thing that could fake it is a test
  // whose captured output contains the literal text. Surefire escapes `<`
  // inside system-out, which is what makes this safe; the case pins that rather
  // than assuming it, because a surefire upgrade that stopped escaping would
  // turn every logged stack trace into phantom test cases.
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite version="3.0.2" name="com.example.Logged" tests="1" failures="0" errors="0" skipped="0">
<testcase name="logs" classname="com.example.Logged">
<system-out>&lt;testcase name="fake" /&gt;</system-out>
</testcase>
</testsuite>
`;
  assert.equal(parseReport(xml).tests, 1);
});

let failures = 0;
for (const { title, fn } of cases) {
  try {
    fn();
    console.log(`ok   ${title}`);
  } catch (error) {
    failures += 1;
    console.error(`FAIL ${title}`);
    console.error(`     ${error.message}`);
  }
}

if (failures > 0) {
  console.error(`\n${failures}/${cases.length} test-visibility self-test case(s) failed.`);
  process.exit(1);
}
console.log(`\nAll ${cases.length} test-visibility self-test cases passed.`);
