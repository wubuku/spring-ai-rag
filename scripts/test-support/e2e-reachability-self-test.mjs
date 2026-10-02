#!/usr/bin/env node
// Negative tests for scripts/verify-e2e-run-paths.mjs.
//
// A gate that cannot fail is worse than no gate, and this repository has a
// documented habit of producing one — Batch 768 shipped a design document
// claiming "ten classes" while the checker enforced eleven; Batch 790's first
// switch reconciler walked zero files and reported all twenty-two switches
// undiscoverable; Batch 802's new external-database gate imported `resolve`
// from the wrong module and crashed on import. Every case below therefore
// asserts that the checker *rejects* the shape it claims to reject, and the
// positive controls prove it is not simply always-red.

import assert from 'node:assert/strict';
import {
  checkReachability,
  collectRunPaths,
  coveredByPreviewSuite,
  parsePreviewIgnore,
  VIOLATION_KINDS,
} from '../verify-e2e-run-paths.mjs';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

const REAL_IGNORE = ['**/*-real.spec.ts'];

test('accepts a suite where every spec is named by a script', () => {
  const violations = checkReachability({
    specs: ['a.spec.ts', 'b-real.spec.ts'],
    runPaths: new Map([
      ['a.spec.ts', ['scripts/x.sh']],
      ['b-real.spec.ts', ['scripts/y.sh']],
    ]),
    ignorePatterns: REAL_IGNORE,
  });
  assert.deepEqual(violations, []);
});

test('accepts a spec the preview suite runs and no script names', () => {
  const violations = checkReachability({
    specs: ['files.spec.ts'],
    runPaths: new Map(),
    ignorePatterns: REAL_IGNORE,
  });
  assert.deepEqual(violations, []);
});

test('rejects a spec nothing runs — the Batch 804 finding', () => {
  // A -real spec is ignored by the preview suite, so a blanket glob exemption
  // cannot cover it; it needs a script that starts a backend.
  const violations = checkReachability({
    specs: ['files-real.spec.ts'],
    runPaths: new Map(),
    ignorePatterns: REAL_IGNORE,
  });
  assert.equal(violations.length, 1);
  assert.equal(violations[0].kind, VIOLATION_KINDS.UNREACHABLE);
});

test('the glob exemption is not a blanket pass over -real specs', () => {
  const withReal = coveredByPreviewSuite('files-real.spec.ts', REAL_IGNORE);
  const withoutReal = coveredByPreviewSuite('files.spec.ts', REAL_IGNORE);
  assert.equal(withReal, false, 'a -real spec must not count as covered by the mock suite');
  assert.equal(withoutReal, true);
});

test('flags the glob exemption when the config no longer declares testIgnore', () => {
  // The exemption is only honest while the config it names still ignores the
  // backend-dependent specs. Losing testIgnore would make the suite try to run
  // them, so a blanket exemption would be a lie.
  const violations = checkReachability({
    specs: ['files.spec.ts'],
    runPaths: new Map(),
    ignorePatterns: [],
  });
  assert.equal(violations.length, 1);
  assert.equal(violations[0].kind, VIOLATION_KINDS.STALE_EXEMPTION);
});

test('reads testIgnore from the config rather than restating it', () => {
  const dir = mkdtempSync(join(tmpdir(), 'e2e-reach-'));
  try {
    const path = join(dir, 'playwright.preview.config.ts');
    writeFileSync(path, `export default {
  testIgnore: ['**/*-real.spec.ts', '**/skipped-*.spec.ts'],
  use: {},
};
`);
    const patterns = parsePreviewIgnore(path);
    assert.deepEqual(patterns, ['**/*-real.spec.ts', '**/skipped-*.spec.ts']);
    // The second pattern must be honoured without any change to the gate.
    assert.equal(coveredByPreviewSuite('skipped-legacy.spec.ts', patterns), false);
    assert.equal(coveredByPreviewSuite('files.spec.ts', patterns), true);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('an empty or missing config yields no patterns, not a silent pass', () => {
  const dir = mkdtempSync(join(tmpdir(), 'e2e-reach-'));
  try {
    assert.deepEqual(parsePreviewIgnore(join(dir, 'absent.ts')), []);
    const path = join(dir, 'plain.ts');
    writeFileSync(path, 'export default { use: {} };\n');
    assert.deepEqual(parsePreviewIgnore(path), []);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('a single * in an ignore pattern does not cross a path separator', () => {
  // `coveredByPreviewSuite` answers "would the preview suite run this?".
  assert.equal(coveredByPreviewSuite('files-real.spec.ts', ['*/*-real.spec.ts']), false,
    'one directory level: the pattern matches, so the suite ignores it');
  // Two levels deep the single-* pattern can no longer reach, so the ignore
  // misses and the suite would run it. If `*` silently crossed separators the
  // exemption would swallow nested -real specs nobody has a backend for.
  assert.equal(coveredByPreviewSuite('nested/files-real.spec.ts', ['*/*-real.spec.ts']), true,
    'two directory levels: the single-* pattern does not reach, so nothing ignores it');
  // The `**` form does span directories, which is why the config uses it.
  assert.equal(coveredByPreviewSuite('nested/files-real.spec.ts', ['**/*-real.spec.ts']), false);
});

/**
 * The collector cases below exist because the earlier self-test only exercised
 * the pure decision function. That left the collector free to be wrong in the
 * way it actually was: a single global regex over spec tokens attributes only
 * the first name on a multi-spec command line, so `files-real.spec.ts` was
 * reported unreachable while a script named it on the very next line. A
 * self-test that never runs the collector cannot catch that.
 */
function withScripts(sources, fn) {
  const dir = mkdtempSync(join(tmpdir(), 'e2e-collect-'));
  try {
    for (const [name, body] of Object.entries(sources)) {
      writeFileSync(join(dir, name), body);
    }
    return fn(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('collects every spec on a multi-spec command line', () => {
  withScripts({
    'gate.sh': `#!/usr/bin/env bash
npx playwright test \\
  e2e/first-real.spec.ts \\
  e2e/second-real.spec.ts \\
  --project=chromium
`,
  }, (dir) => {
    const runPaths = collectRunPaths(dir);
    assert.ok(runPaths.has('first-real.spec.ts'), 'first spec on the line');
    assert.ok(runPaths.has('second-real.spec.ts'),
      'second spec on the same command line must not be lost to lastIndex');
  });
});

test('collects from several invocations in one script without crossing them', () => {
  withScripts({
    'gate.sh': `#!/usr/bin/env bash
npx playwright test e2e/alpha.spec.ts --project=chromium
echo "not a command: e2e/decoy.spec.ts"
npx playwright test e2e/beta.spec.ts --project=chromium
`,
  }, (dir) => {
    const runPaths = collectRunPaths(dir);
    assert.ok(runPaths.has('alpha.spec.ts'));
    assert.ok(runPaths.has('beta.spec.ts'));
    assert.ok(!runPaths.has('decoy.spec.ts'),
      'a spec mentioned between invocations is a mention, not a run path');
  });
});

test('a script that only echoes a spec name does not count as running it', () => {
  withScripts({
    'gate.sh': `#!/usr/bin/env bash
echo "remember to run e2e/ghost.spec.ts someday"
`,
  }, (dir) => {
    assert.ok(!collectRunPaths(dir).has('ghost.spec.ts'));
  });
});

test('runs one script in a second and are not confused by the first', () => {
  // Guards the shared `lastIndex` on the module-level regexes: a first script
  // that ends mid-match must not shift where the second one starts looking.
  withScripts({
    'a.sh': 'npx playwright test e2e/one.spec.ts\n',
    'b.sh': 'npx playwright test e2e/two.spec.ts\n',
  }, (dir) => {
    const runPaths = collectRunPaths(dir);
    assert.ok(runPaths.has('one.spec.ts'), 'first script');
    assert.ok(runPaths.has('two.spec.ts'), 'second script');
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
  console.error(`\n${failed}/${cases.length} e2e reachability self-test case(s) failed.`);
  process.exitCode = 1;
} else {
  console.log(`\nAll ${cases.length} e2e reachability self-test cases passed.`);
}
