#!/usr/bin/env node
// Self-test for scripts/lib/surefire-report.sh.
//
// Five acceptance gates read the same four counters off the same kind of report,
// each with its own copy of the same `sed` pipeline, and none of them had a
// self-test — so the reading was never checked, only the thing being read. This
// runs the real shell function against fixtures written to disk, because the
// thing being tested *is* a shell function: a JavaScript re-implementation of
// the same rule would pass while the shell one rotted.
//
// The decisive case is a report shaped like the three real ones Batch 893 found
// disagreeing: the attribute says fewer tests than the file actually contains,
// which is what surefire does for a class with `@Nested` inner classes.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const HELPER = join(repoRoot, 'scripts/lib/surefire-report.sh');

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

const dir = mkdtempSync(join(tmpdir(), 'surefire-report-self-test-'));

/** Write a report and return the four counters the shell helper prints. */
function counts(body, attrs = 'tests="0" failures="0" errors="0" skipped="0"') {
  const file = join(dir, `TEST-fixture-${Math.random().toString(36).slice(2)}.xml`);
  writeFileSync(
    file,
    `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite version="3.0.2" name="com.example.Fixture" ${attrs}>\n${body}\n</testsuite>\n`,
  );
  const out = execFileSync('bash', ['-c', `source "${HELPER}"; surefire_counts "${file}"`], {
    encoding: 'utf8',
  });
  // Only the newline is stripped, and the split is on a comma. A `trim()` plus
  // a whitespace split — the first version of this file — deleted the trailing
  // empty fields, which are exactly what a missing attribute produces, and made
  // the first two cases pass for the wrong reason.
  return out.replace(/\n+$/, '').split(',');
}

const testcase = (i, inner = '') => `<testcase name="case${i}" classname="com.example.Fixture">${inner}</testcase>`;
const plain = (n) => Array.from({ length: n }, (_, i) => testcase(i)).join('\n');

test('the helper is sourceable and prints four fields', () => {
  // The positive control. Without it, a helper that printed nothing would make
  // every other case in this file pass for the same wrong reason.
  assert.equal(counts(plain(3)).length, 4);
});

test('three plain cases read as three', () => {
  assert.equal(counts(plain(3), 'tests="3" failures="0" errors="0" skipped="0"')[0], '3');
});

test('a nested class counts, even when the attribute does not', () => {
  // The regression. `tests="17"` against 24 real cases is what surefire writes
  // for RagCollectionServiceTest, whose 24 cases all come from @Nested classes.
  const casesXml = Array.from({ length: 24 }, (_, i) => testcase(i)).join('\n');
  assert.equal(counts(casesXml, 'tests="17" failures="0" errors="0" skipped="0"')[0], '24');
});

test('the other three counters still come from the attribute', () => {
  // They are read, not counted, because none of the 1006 real reports disagrees
  // on them. This case pins that decision so it cannot drift quietly.
  const body = [testcase(0, '<skipped />'), testcase(1, '<skipped />'), testcase(2, '<failure message="f" />')].join('\n');
  assert.deepEqual(
    counts(body, 'tests="9" failures="1" errors="0" skipped="2"'),
    ['3', '1', '0', '2'],
  );
});

test('an attribute the report does not carry reads as empty, not as zero', () => {
  // A caller comparing against "0" then reports a missing counter as missing
  // rather than silently passing, which is what the gates' error messages say.
  const file = join(dir, 'TEST-no-attrs.xml');
  writeFileSync(file, `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="com.example.Bare">\n${plain(2)}\n</testsuite>\n`);
  const out = execFileSync('bash', ['-c', `source "${HELPER}"; surefire_counts "${file}"`], { encoding: 'utf8' });
  assert.deepEqual(out.replace(/\n+$/, '').split(','), ['2', '', '', '']);
});

test('an empty report is four fields and a zero, not a crash', () => {
  assert.deepEqual(counts(''), ['0', '0', '0', '0']);
});

test('a missing report is a zero, not an error', () => {
  // The gates call this after their own existence check, and a `grep` that
  // matched nothing would take the whole pipeline down under `set -euo pipefail`.
  const out = execFileSync('bash', ['-c', `source "${HELPER}"; surefire_counts "/nonexistent/report.xml"`], { encoding: 'utf8' });
  assert.deepEqual(out.replace(/\n+$/, '').split(','), ['0', '', '', '']);
});

test('a tag inside logged output cannot inflate the count', () => {
  // Surefire escapes `<` inside system-out; if an upgrade stopped doing that,
  // every test that logged a stack trace would turn into phantom cases.
  const body = `${testcase(0)}\n<system-out>&lt;testcase name="fake" /&gt;</system-out>`;
  assert.equal(counts(body)[0], '1');
});

test('all five acceptance gates read their counters through the helper', () => {
  // The wiring half. A helper that exists and is correct is worth nothing if
  // the gates keep their own copies of the pipeline beside it — and an earlier
  // version of this case only checked that each file *sourced* the helper and
  // did not use one particular `sed`, which a gate can satisfy while ignoring
  // the helper entirely. So the assertion is that each one calls it, and for
  // the Python heredoc that it counts the elements in Python.
  const shellGates = [
    'verify-collection-purge.sh',
    'verify-api-key-expiry-alerts.sh',
    'verify-next-high-value-feature.sh',
    'verify-collection-provisioning.sh',
  ];
  for (const g of shellGates) {
    const src = readFileSync(join(repoRoot, 'scripts', g), 'utf8');
    assert.ok(src.includes('lib/surefire-report.sh'), `${g} does not source the helper`);
    assert.ok(src.includes('surefire_counts "$report"'), `${g} sources the helper but does not read through it`);
    assert.ok(!/tests="\\d/.test(src), `${g} still matches a tests attribute as a literal`);
  }
  const py = readFileSync(join(repoRoot, 'scripts', 'verify-document-lifecycle.sh'), 'utf8');
  assert.ok(py.includes('root.iter("testcase")'), 'verify-document-lifecycle.sh does not count the elements');
  assert.ok(
    !/attrib\.get\("tests"/.test(py),
    'verify-document-lifecycle.sh still reads the tests attribute',
  );
});

let failed = 0;
for (const { title, fn } of cases) {
  try {
    fn();
    console.log(`ok   ${title}`);
  } catch (error) {
    failed += 1;
    console.error(`FAIL ${title}`);
    console.error(`     ${error.message}`);
  }
}
rmSync(dir, { recursive: true, force: true });

if (failed > 0) {
  console.error(`\n${failed}/${cases.length} surefire-report self-test case(s) failed.`);
  process.exitCode = 1;
} else {
  console.log(`\nAll ${cases.length} surefire-report self-test cases passed.`);
}
