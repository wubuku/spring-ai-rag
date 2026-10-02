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

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));

const suite = (name, rest) =>
  `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite version="3.0.2" name="${name}" ${rest}></testsuite>\n`;

const report = (name, rest) => ({ file: `TEST-${name}.xml`, xml: suite(name, rest) });

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
  // reconciliation permanently red.
  const dir = mkdtempSync(join(tmpdir(), 'visibility-src-'));
  try {
    mkdirSync(join(dir, 'com', 'example'), { recursive: true });
    writeFileSync(
      join(dir, 'com', 'example', 'AbstractBaseTest.java'),
      'package com.example;\nabstract class AbstractBaseTest {}\n',
    );
    writeFileSync(
      join(dir, 'com', 'example', 'RealTest.java'),
      'package com.example;\nclass RealTest {}\n',
    );
    writeFileSync(join(dir, 'com', 'example', 'Helper.java'), 'class Helper {}\n');
    writeFileSync(
      join(dir, 'com', 'example', 'SuiteTests.java'),
      'package com.example;\nclass SuiteTests {}\n',
    );

    const found = collectSourceTestClasses(dir).map(f => f.primary).sort();
    assert.deepEqual(found, ['RealTest', 'SuiteTests']);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
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
