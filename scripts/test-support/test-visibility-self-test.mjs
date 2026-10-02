#!/usr/bin/env node
// Negative tests for scripts/verify-test-visibility.mjs.
//
// A gate that cannot fail is worse than no gate, and this repository has a
// documented history of producing exactly that defect. These cases therefore
// assert that the checker *rejects* the shape it claims to reject — notably the
// `tests="0" skipped="0"` pair that an `assumeTrue` gate in `@BeforeAll`
// produces, which is indistinguishable from an empty class in the run summary.

import assert from 'node:assert/strict';
import { audit, parseReport } from '../verify-test-visibility.mjs';
import { readdirSync, readFileSync, existsSync } from 'node:fs';
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
