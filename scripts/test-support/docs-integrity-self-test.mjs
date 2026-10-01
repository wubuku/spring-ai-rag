#!/usr/bin/env node
// Negative tests for scripts/lib/docs-integrity-check.mjs.
//
// A gate that cannot fail is worse than no gate, and this repository has a
// documented history of producing exactly that defect. These cases therefore
// assert that each rule *rejects* the input it claims to reject — not merely
// that the checker runs. Case 1 in particular is the regression test for the
// hard-coded pair list that let 27 bilingual pairs go unexamined.

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import {
  checkBilingualPairs,
  checkTrackedTextCleanliness,
  discoverPairs,
  headingSignature
} from '../lib/docs-integrity-check.mjs';

let passed = 0;

function test(name, body) {
  try {
    body();
    passed += 1;
    console.log(`PASS: ${name}`);
  } catch (error) {
    console.error(`FAIL: ${name}`);
    console.error(`  ${error.message}`);
    process.exitCode = 1;
  }
}

function fixture(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'docs-integrity-'));
  const written = [];

  for (const [relative, content] of Object.entries(files)) {
    const absolute = path.join(root, relative);
    fs.mkdirSync(path.dirname(absolute), { recursive: true });
    fs.writeFileSync(absolute, content);
    written.push(relative);
  }

  return {
    root,
    files: written,
    cleanup: () => fs.rmSync(root, { recursive: true, force: true })
  };
}

const IN_SYNC_HEADINGS = '# Title\n\n## One\n\n### Detail\n\n## Two\n';
const CHINESE_HEADINGS = '# 标题\n\n## 一\n\n### 细节\n\n## 二\n';
const EXTRA_SECTION = '\n## Three\n';

function expectFailure(result, fragment) {
  assert.equal(result.ok, false, 'expected the check to fail, but it passed');
  const joined = result.errors.join('\n');
  assert.ok(
    joined.includes(fragment),
    `expected an error containing ${JSON.stringify(fragment)}, got:\n${joined}`
  );
}

const cleanFixture = fixture({
  'guide.md': IN_SYNC_HEADINGS,
  'guide-zh-CN.md': CHINESE_HEADINGS
});
const driftedFixture = fixture({
  'guide.md': IN_SYNC_HEADINGS + EXTRA_SECTION,
  'guide-zh-CN.md': CHINESE_HEADINGS
});
const extraPairFixture = fixture({
  'guide.md': IN_SYNC_HEADINGS,
  'guide-zh-CN.md': CHINESE_HEADINGS,
  'docs/extra.md': IN_SYNC_HEADINGS,
  'docs/extra-zh-CN.md': IN_SYNC_HEADINGS + EXTRA_SECTION
});

try {
  // ---- discovery -----------------------------------------------------------

  test('discovers a pair and ignores a document with no translation', () => {
    const pairs = discoverPairs(['a.md', 'a-zh-CN.md', 'b.md', 'c.md', 'd-zh-CN.md']);
    assert.deepEqual(pairs, [['a.md', 'a-zh-CN.md']]);
  });

  test('heading signature ignores comments inside a fenced block', () => {
    const withFence = '# Title\n\n```bash\n## not a heading\n# also not\n```\n\n## Real\n';
    assert.deepEqual(headingSignature(withFence), [1, 2]);
  });

  test('a matching unregistered pair passes', () => {
    // Positive control. Without this, every later assertion would also pass
    // against a checker that simply always failed.
    const result = checkBilingualPairs({
      root: cleanFixture.root,
      files: cleanFixture.files,
      knownDrift: [],
      driftCeiling: 0
    });
    assert.equal(result.ok, true, result.errors.join('\n'));
    assert.equal(result.stats.enforced, 1);
  });

  // ---- the regression this checker exists for --------------------------------

  test('a drifting pair the registry never mentions is rejected', () => {
    // The old hard-coded list skipped this document entirely and passed.
    const result = checkBilingualPairs({
      root: driftedFixture.root,
      files: driftedFixture.files,
      knownDrift: [],
      driftCeiling: 0
    });
    expectFailure(result, 'Heading structure mismatch: guide.md <> guide-zh-CN.md');
  });

  test('a newly added drifting pair is rejected without touching the registry', () => {
    const result = checkBilingualPairs({
      root: extraPairFixture.root,
      files: extraPairFixture.files,
      knownDrift: [],
      driftCeiling: 0
    });
    expectFailure(result, 'docs/extra.md <> docs/extra-zh-CN.md');
  });

  // ---- exemptions must be earned and cannot go stale -----------------------

  test('an exemption that is no longer broken is rejected', () => {
    const result = checkBilingualPairs({
      root: cleanFixture.root,
      files: cleanFixture.files,
      knownDrift: [{ pair: ['guide.md', 'guide-zh-CN.md'], reason: 'out of date' }],
      driftCeiling: 1
    });
    expectFailure(result, 'remove it from KNOWN_DRIFT');
  });

  test('an exemption without a reason is rejected', () => {
    const result = checkBilingualPairs({
      root: driftedFixture.root,
      files: driftedFixture.files,
      knownDrift: [{ pair: ['guide.md', 'guide-zh-CN.md'], reason: '   ' }],
      driftCeiling: 1
    });
    expectFailure(result, 'without a reason');
  });

  test('an exemption for a pair that no longer exists is rejected', () => {
    const result = checkBilingualPairs({
      root: cleanFixture.root,
      files: cleanFixture.files,
      knownDrift: [{ pair: ['vanished.md', 'vanished-zh-CN.md'], reason: 'gone' }],
      driftCeiling: 1
    });
    expectFailure(result, 'no such bilingual pair exists on disk');
  });

  test('an exemption pointing at the wrong translation is rejected', () => {
    const result = checkBilingualPairs({
      root: driftedFixture.root,
      files: driftedFixture.files,
      knownDrift: [
        { pair: ['guide.md', 'other-zh-CN.md'], reason: 'wrong target' }
      ],
      driftCeiling: 1
    });
    expectFailure(result, 'the discovered pair is guide-zh-CN.md');
  });

  test('registering more drift than the ceiling allows is rejected', () => {
    const result = checkBilingualPairs({
      root: driftedFixture.root,
      files: driftedFixture.files,
      knownDrift: [{ pair: ['guide.md', 'guide-zh-CN.md'], reason: 'out of date' }],
      driftCeiling: 0
    });
    expectFailure(result, 'DRIFT_CEILING is 0');
  });

  test('a ceiling left higher than the registered count is rejected', () => {
    // The loophole an upper bound would leave open: fix a document, delete its
    // entry, forget to lower the ceiling. The gate must not stay green while the
    // pinned number drifts away from the truth.
    const result = checkBilingualPairs({
      root: cleanFixture.root,
      files: cleanFixture.files,
      knownDrift: [],
      driftCeiling: 4
    });
    expectFailure(result, 'KNOWN_DRIFT holds 0 entries but DRIFT_CEILING is 4');
  });

  test('a duplicate exemption is rejected', () => {
    const result = checkBilingualPairs({
      root: driftedFixture.root,
      files: driftedFixture.files,
      knownDrift: [
        { pair: ['guide.md', 'guide-zh-CN.md'], reason: 'first' },
        { pair: ['guide.md', 'guide-zh-CN.md'], reason: 'second' }
      ],
      driftCeiling: 2
    });
    expectFailure(result, 'more than once');
  });

  test('a correctly registered drifting pair passes and counts against the ceiling', () => {
    const result = checkBilingualPairs({
      root: driftedFixture.root,
      files: driftedFixture.files,
      knownDrift: [{ pair: ['guide.md', 'guide-zh-CN.md'], reason: 'missing last section' }],
      driftCeiling: 1
    });
    assert.equal(result.ok, true, result.errors.join('\n'));
    assert.equal(result.stats.registeredDrift, 1);
    assert.equal(result.stats.enforced, 0);
  });

  // ---- NUL bytes ------------------------------------------------------------

  test('a NUL byte in a tracked markdown file is rejected', () => {
    const nulFixture = fixture({ 'notes.md': '# Title\n\nexample `safe\u0000/etc`\n' });
    try {
      const result = checkTrackedTextCleanliness(nulFixture);
      expectFailure(result, 'NUL byte in tracked text file notes.md');
      assert.match(result.errors[0], /first line=3/);
    } finally {
      nulFixture.cleanup();
    }
  });

  test('a NUL byte in a tracked shell script is rejected', () => {
    const nulFixture = fixture({ 'tool.sh': '#!/usr/bin/env bash\necho "a\u0000b"\n' });
    try {
      const result = checkTrackedTextCleanliness(nulFixture);
      expectFailure(result, 'NUL byte in tracked text file tool.sh');
    } finally {
      nulFixture.cleanup();
    }
  });

  test('a NUL byte inside a binary asset is not a finding', () => {
    const binaryFixture = fixture({ 'logo.png': Buffer.from([0x89, 0x50, 0x00, 0x01]) });
    try {
      const result = checkTrackedTextCleanliness(binaryFixture);
      assert.equal(result.ok, true, result.errors.join('\n'));
      assert.equal(result.stats.skipped, 1);
    } finally {
      binaryFixture.cleanup();
    }
  });

  test('clean text files produce no findings', () => {
    const result = checkTrackedTextCleanliness(cleanFixture);
    assert.equal(result.ok, true, result.errors.join('\n'));
    assert.equal(result.stats.findings, 0);
  });

  test('a missing file listed by git does not crash the scan', () => {
    const result = checkTrackedTextCleanliness({
      root: cleanFixture.root,
      files: ['guide.md', 'deleted-between-listing-and-read.md']
    });
    assert.equal(result.ok, true, result.errors.join('\n'));
  });
} finally {
  cleanFixture.cleanup();
  driftedFixture.cleanup();
  extraPairFixture.cleanup();
}

if (process.exitCode) {
  console.error(`\nDocs integrity self-test FAILED: ${passed} case(s) passed before the failure.`);
} else {
  console.log(`\nDocs integrity self-test passed: ${passed} cases.`);
}
