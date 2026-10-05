#!/usr/bin/env node
// Batch 901. The registry's existing rule — every automated gate carries a
// self-test — is discharged by importing the module and calling its functions,
// so it cannot see a gate that never runs. These cases close that gap from both
// sides: the shapes, and the behaviour.

import assert from 'node:assert/strict';
import {
  VIOLATION_KINDS,
  isHandRolledGuard,
  stripStringLiterals,
  checkEntryPoints,
  HELPER_COPIES,
} from '../verify-gate-entry-points.mjs';
import { GATES } from '../gate-registry.mjs';
import {
  mkdtempSync,
  mkdirSync,
  writeFileSync,
  readFileSync,
  readdirSync,
  rmSync,
  existsSync,
  lstatSync,
  symlinkSync,
  unlinkSync,
} from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));

const isSymlink = (path) => {
  try {
    return lstatSync(path).isSymbolicLink();
  } catch {
    return false;
  }
};

const listScripts = (dir) => {
  try {
    return readdirSync(join(repoRoot, dir)).filter((name) => name.endsWith('.mjs'));
  } catch {
    return [];
  }
};

// ------------------------------------------------------------------ shapes --

test('the three idioms this repository actually had are all reported', () => {
  // Measured before the conversion: 7 files spelled it one way, 11 another, 4 a
  // third. Two of the three are broken under a symlink; the third survives by
  // accident, because comparing basenames happens not to care. That is not a
  // reason to keep a fourth spelling alive.
  const idioms = [
    'if (import.meta.url === `file://${process.argv[1]}`) main();',
    'if (process.argv[1] && import.meta.url === `file://${resolve(process.argv[1])}`) {',
    "if (process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop())) {",
  ];
  for (const line of idioms) {
    assert.equal(isHandRolledGuard(line), true, `not reported: ${line}`);
  }
});

test('the shared helper is not reported', () => {
  assert.equal(isHandRolledGuard('if (isMainModule(import.meta.url)) main();'), false);
  assert.equal(isHandRolledGuard('if (isMainModule(import.meta.url)) {'), false);
});

test('a string that merely names import.meta.url is not a guard', () => {
  // This gate's own source contains exactly that, and the first version flagged
  // itself. A detector that cannot be pointed at a tree containing itself is not
  // a detector.
  assert.equal(
    isHandRolledGuard("  if (text.includes('import.meta.url') && !text.includes('isMainModule')) {"),
    false,
  );
  assert.equal(
    stripStringLiterals("const x = 'import.meta.url';").includes('import.meta.url'),
    false,
  );
});

test('an ordinary use of import.meta.url is not a guard', () => {
  // The commonest reason a gate mentions it at all: finding the repository
  // root. A rule that flagged this would report most of the directory.
  assert.equal(
    isHandRolledGuard("const root = fileURLToPath(new URL('..', import.meta.url));"),
    false,
  );
});

test('stripping literals keeps the line readable and the same length', () => {
  // Comments come after, so the test above can point at a line and a reader can
  // still see what it said.
  const line = "  const x = 'abc'; // note";
  const stripped = stripStringLiterals(line);
  assert.equal(stripped.length, line.length);
  assert.equal(stripped.includes("'abc'"), false);
});

// ---------------------------------------------------------------- behaviour --

test('a real gate runs when it is named through a symlink', () => {
  // The proof the self-test normally cannot give. Before Batch 901 both gates
  // named here printed nothing and exited 0 when invoked this way.
  const dir = mkdtempSync(join(tmpdir(), 'gate-entry-'));
  const link = join(dir, 'repo');
  try {
    symlinkSync(repoRoot, link, 'dir');
    if (!isSymlink(link)) throw new Error('ABORT: the symlink was not created, so this proves nothing');

    // Two gates, because they were written in two of the three idioms, and a
    // fix that only covered the helper's own callers would pass with one.
    for (const gate of ['scripts/verify-gate-entry-points.mjs', 'scripts/verify-slo-endpoint-coverage.mjs']) {
      const out = execFileSync('node', [join(link, gate)], { encoding: 'utf8', cwd: repoRoot });
      assert.ok(
        out.trim().length > 0,
        `${gate} printed nothing through a symlink, which is the failure this gate exists for`,
      );
    }
  } finally {
    if (isSymlink(link)) unlinkSync(link);
    rmSync(dir, { recursive: true, force: true });
  }
  assert.equal(isSymlink(link), false, 'the probe symlink was left behind');
  assert.equal(existsSync(repoRoot), true, 'the cleanup followed the link and removed its target');
});

// -------------------------------------------------------------- integration --

function withTree(files, fn) {
  const root = mkdtempSync(join(tmpdir(), 'gate-entry-tree-'));
  try {
    for (const [path, content] of Object.entries(files)) {
      const full = join(root, path);
      mkdirSync(dirname(full), { recursive: true });
      writeFileSync(full, content);
    }
    return fn(root);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

const MIRRORED = {
  'scripts/lib/is-main-module.mjs': 'export const isMainModule = () => true;\n',
  'spring-ai-rag-webui/scripts/lib/is-main-module.mjs': 'export const isMainModule = () => true;\n',
};

test('a gate that rolls its own entry test is reported', () => {
  const files = {
    ...MIRRORED,
    'scripts/verify-thing.mjs': "import { isMainModule } from './lib/is-main-module.mjs';\n"
      + 'if (isMainModule(import.meta.url)) {}\n',
    'scripts/verify-other.mjs': 'if (import.meta.url === `file://${process.argv[1]}`) {}\n',
  };
  withTree(files, (root) => {
    const violations = checkEntryPoints(root, ['scripts/verify-thing.mjs', 'scripts/verify-other.mjs']);
    assert.deepEqual(
      violations.map((v) => [v.gate, v.kind]),
      [['scripts/verify-other.mjs', VIOLATION_KINDS.HAND_ROLLED_ENTRY_GUARD]],
    );
  });
});

test('a drifted copy of the helper is reported', () => {
  // The webui package cannot import the repository's copy, so the mirror is a
  // second file. A mirror nobody compares is a second implementation waiting to
  // happen — which is how four entry-guard idioms appeared in the first place.
  const files = {
    ...MIRRORED,
    'spring-ai-rag-webui/scripts/lib/is-main-module.mjs': 'export const isMainModule = () => false;\n',
  };
  withTree(files, (root) => {
    const violations = checkEntryPoints(root, []);
    assert.deepEqual(violations.map((v) => v.kind), [VIOLATION_KINDS.DRIFTED_HELPER_COPY]);
  });
});

test('identical copies are not reported', () => {
  withTree(MIRRORED, (root) => {
    assert.deepEqual(checkEntryPoints(root, []), []);
  });
});

test('this repository decides none of its own entry tests', () => {
  // The real tree, and the floor that keeps this from passing on an empty walk.
  const gates = [
    ...listScripts('scripts').map((name) => `scripts/${name}`),
    ...listScripts('spring-ai-rag-webui/scripts').map((name) => `spring-ai-rag-webui/scripts/${name}`),
  ];
  assert.ok(gates.length >= 25, `only ${gates.length} gate script(s) walked; the walk stopped early`);
  const violations = checkEntryPoints(repoRoot, gates);
  assert.deepEqual(violations.map((v) => `${v.gate}:${v.kind}`), [], JSON.stringify(violations, null, 2));
});

test('both helper copies are the file this batch wrote', () => {
  // The census compares the two copies to each other, which says nothing about
  // whether either is the helper. This says what it must contain.
  for (const path of HELPER_COPIES) {
    const text = readFileSync(join(repoRoot, path), 'utf8');
    assert.match(text, /realpathSync/, `${path} is not the symlink-robust helper`);
    assert.match(text, /export function isMainModule/, `${path} does not export isMainModule`);
  }
});

test('the registered gate count did not shrink while this was added', () => {
  // A census that reads fewer files than it used to can go green by looking at
  // less. This is a plain floor and it fails loudly if the walk changes.
  const automated = GATES.filter((entry) => entry.kind === 'gate').length;
  assert.ok(automated >= 24, `only ${automated} automated gate(s) registered`);
});

let failed = 0;
for (const { title, fn } of cases) {
  try {
    await fn();
    console.log(`ok   ${title}`);
  } catch (error) {
    failed += 1;
    console.error(`FAIL ${title}`);
    console.error(String(error && error.message ? error.message : error));
  }
}
console.log(`\n${cases.length - failed}/${cases.length} gate-entry-points self-test case(s) passed.`);
process.exit(failed === 0 ? 0 : 1);
