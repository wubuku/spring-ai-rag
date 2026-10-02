#!/usr/bin/env node
// Negative tests for scripts/verify-gate-wiring.mjs.
//
// Two things justify this file existing rather than a comment.
//
// First, the gate it tests is itself the answer to "a gate that cannot fail is
// worse than no gate", and the cheapest way to ship another one is to write a
// census whose rules never fire. Every case below therefore drives the rule it
// names and asserts the violation kind, so a rule that stops matching shows up
// here as a failing case instead of as a clean run in the test chain.
//
// Second — and this is the part the sibling gates in this repository got wrong —
// the interesting logic here is in the *collectors*, not the checker. The census
// answers "is this gate executed by something?", and that answer comes from
// reading runner text. Batch 809's first version of this collector used
// /\bsh\s/ to find a command word, which matches the `.sh ` at the end of a
// filename: every entry in a documentation allowlist read as an execution, and
// 24 manual scripts would have been reported as wired to a chain that never runs
// them. The second version then dropped repo-relative paths in loop lists and
// reported the pessimistic-lock gate — which really is executed, through
// `for gate in ...; bash "$gate"` — as an orphan. Both defects were invisible to
// a checker-only test, so the collector is exercised directly against fixture
// trees below, in the shapes this repository actually uses.

import assert from 'node:assert/strict';
import {
  checkWiring,
  collectExecutedScripts,
  collectDocText,
  collectGateScripts,
  isExecutionLine,
  resolveCiReachability,
  RUNNERS,
  VIOLATION_KINDS,
} from '../verify-gate-wiring.mjs';
import { GATES } from '../gate-registry.mjs';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

/** Minimal repository: the two script directories, the CI workflow, the
 *  runners, and whatever files a case supplies. */
function withTree(files, fn) {
  const root = mkdtempSync(join(tmpdir(), 'gate-wiring-'));
  try {
    mkdirSync(join(root, 'scripts'), { recursive: true });
    mkdirSync(join(root, 'spring-ai-rag-webui', 'scripts'), { recursive: true });
    mkdirSync(join(root, '.github', 'workflows'), { recursive: true });
    writeFileSync(join(root, '.github', 'workflows', 'ci.yml'), 'name: CI\n');
    writeFileSync(join(root, 'spring-ai-rag-webui', 'package.json'), '{}\n');
    // By default the fixture repository documents every gate the cases below
    // put on disk, so the census reports only the rule under test. A case that
    // wants an undocumented gate passes 'docs/reference.md': ''.
    mkdirSync(join(root, 'docs'), { recursive: true });
    writeFileSync(
      join(root, 'docs', 'reference.md'),
      files['docs/reference.md'] ??
        'Fixture gates: verify-alpha.mjs, verify-project-tests.sh, verify-gated-it.sh,\n' +
        'verify-inner.mjs, verify-newcomer.mjs, verify-beta.sh, verify-deleted.mjs,\n' +
        'verify-project-docs.sh,\n' +
        'verify-chat-capability.sh.\n',
    );
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

/** Drive the real collectors over a tree, then the pure checker. */
function audit(root, registry) {
  const gateScripts = collectGateScripts(root);
  const read = (path) => {
    try {
      return readFileSync(join(root, path), 'utf8');
    } catch {
      return undefined;
    }
  };
  const runnerPaths = [
    '.github/workflows/ci.yml',
    'spring-ai-rag-webui/package.json',
    ...RUNNERS,
  ];
  const runnerTexts = {};
  for (const path of runnerPaths) {
    const text = read(path);
    if (text !== undefined) runnerTexts[path] = text;
  }
  const executedBy = new Map();
  for (const gate of gateScripts) {
    const basename = gate.split('/').pop();
    executedBy.set(
      gate,
      runnerPaths.filter((path) =>
        runnerTexts[path] ? collectExecutedScripts(runnerTexts[path]).has(basename) : false,
      ),
    );
  }
  return checkWiring({
    gateScripts,
    registry,
    fileExists: (path) => read(path) !== undefined,
    executedBy,
    ciReached: resolveCiReachability(gateScripts, runnerTexts),
    docText: collectDocText(root),
  });
}

const kinds = (violations) => violations.map((violation) => violation.kind).sort();

// ---------------------------------------------------------------- collector --

test('an allowlist line is not an execution even though it ends in .sh', () => {
  // The bug this file exists partly to prevent: `/\bsh\s/` matches the ".sh " at
  // the end of a filename, which would make every documented-but-never-run
  // script look wired to the documentation gate.
  const text = 'for script in \\\n      scripts/verify-chat-capability.sh \\\n      scripts/verify-release.sh; do\n  [[ -x "$script" ]]\ndone\n';
  assert.equal(collectExecutedScripts(text).has('verify-chat-capability.sh'), false);
  assert.equal(collectExecutedScripts(text).has('verify-release.sh'), false);
});

test('a bare path in a list is not an execution', () => {
  assert.equal(isExecutionLine('      scripts/verify-release.sh \\'), false);
});

test('a command word makes the line an execution', () => {
  assert.equal(isExecutionLine('node scripts/verify-alpha.mjs'), true);
  assert.equal(isExecutionLine('  bash "$gate" >/dev/null 2>&1'), true);
});

test('a direct path invocation is an execution', () => {
  // ci.yml:86 runs `./scripts/verify-gated-it.sh` with no command word at all.
  assert.equal(isExecutionLine('      ./scripts/verify-gated-it.sh'), true);
  assert.equal(collectExecutedScripts('      ./scripts/verify-gated-it.sh\n').has('verify-gated-it.sh'), true);
});

test('a commented-out invocation is not an execution', () => {
  assert.equal(isExecutionLine('# node scripts/verify-alpha.mjs'), false);
  assert.equal(isExecutionLine('    # scripts/verify-beta.sh \\'), false);
  assert.equal(collectExecutedScripts('# node scripts/verify-alpha.mjs\n').has('verify-alpha.mjs'), false);
});

test('a loop over repo-relative paths is resolved through the loop variable', () => {
  // The real shape in verify-project-docs.sh: the list line carries no command
  // word, so only the `bash "$gate"` body reveals that the names are executed.
  const text = [
    'for gate in scripts/verify-no-pessimistic-locks.sh scripts/business-client-contract-e2e.sh; do',
    '  if PATH="$stripped_path" bash "$gate" >/dev/null 2>&1; then',
    '    return 1',
    '  fi',
    'done',
  ].join('\n');
  const executed = collectExecutedScripts(text);
  assert.equal(executed.has('verify-no-pessimistic-locks.sh'), true);
  assert.equal(executed.has('business-client-contract-e2e.sh'), true);
});

test('a loop that is never invoked resolves to nothing', () => {
  // The list is mentioned, but nothing runs "$gate" — the same distinction the
  // allowlist case turns on.
  const text = 'for gate in scripts/verify-alpha.mjs; do\n  echo "$gate"\ndone\n';
  assert.equal(collectExecutedScripts(text).has('verify-alpha.mjs'), false);
});

test('CI reachability follows one level of indirection', () => {
  // ci.yml runs verify-gated-it.sh; whatever that runs is reached too.
  const files = {
    '.github/workflows/ci.yml': '      run: ./scripts/verify-gated-it.sh\n',
    'scripts/verify-gated-it.sh': 'node scripts/verify-inner.mjs\n',
    'scripts/verify-inner.mjs': '',
  };
  withTree(files, (root) => {
    const gateScripts = collectGateScripts(root);
    const texts = {
      '.github/workflows/ci.yml': files['.github/workflows/ci.yml'],
      'scripts/verify-gated-it.sh': files['scripts/verify-gated-it.sh'],
    };
    const reached = resolveCiReachability(gateScripts, texts);
    assert.equal(reached.has('scripts/verify-inner.mjs'), true);
    assert.equal(reached.has('scripts/verify-gated-it.sh'), true);
  });
});

// ------------------------------------------------------------------- rules --

test('a clean repository reports nothing', () => {
  const files = {
    'scripts/verify-alpha.mjs': '',
    'scripts/verify-project-tests.sh': 'node scripts/verify-alpha.mjs\n',
    'scripts/test-support/alpha-self-test.mjs': '',
    '.github/workflows/ci.yml': 'name: CI\n      run: bash scripts/verify-project-tests.sh\n',
  };
  withTree(files, (root) => {
    const violations = audit(root, [
      { gate: 'scripts/verify-alpha.mjs', kind: 'gate', selfTest: 'scripts/test-support/alpha-self-test.mjs' },
      { gate: 'scripts/verify-project-tests.sh', kind: 'entrypoint', noSelfTestReason: 'aggregate' },
    ]);
    assert.deepEqual(violations, [], JSON.stringify(violations, null, 2));
  });
});

test('a gate on disk that nobody registered is reported', () => {
  const files = {
    'scripts/verify-newcomer.mjs': '',
    'scripts/verify-project-tests.sh': 'node scripts/verify-newcomer.mjs\n',
  };
  withTree(files, (root) => {
    const violations = audit(root, [
      { gate: 'scripts/verify-project-tests.sh', kind: 'entrypoint', noSelfTestReason: 'aggregate', noCiReason: 'not yet' },
    ]);
    assert.deepEqual(kinds(violations), [VIOLATION_KINDS.UNREGISTERED_GATE]);
    assert.equal(violations[0].gate, 'scripts/verify-newcomer.mjs');
  });
});

test('an entry that outlived its script is reported rather than ignored', () => {
  withTree({}, (root) => {
    const violations = audit(root, [{ gate: 'scripts/verify-deleted.mjs', kind: 'manual' }]);
    assert.deepEqual(kinds(violations), [VIOLATION_KINDS.UNREGISTERED_GATE]);
  });
});

test('registering the same gate twice is reported', () => {
  const files = {
    'scripts/verify-alpha.mjs': '',
    'scripts/verify-project-tests.sh': 'node scripts/verify-alpha.mjs\n',
  };
  withTree(files, (root) => {
    const violations = audit(root, [
      { gate: 'scripts/verify-alpha.mjs', kind: 'gate', noSelfTestReason: 'x', noCiReason: 'y' },
      { gate: 'scripts/verify-alpha.mjs', kind: 'gate', noSelfTestReason: 'x', noCiReason: 'y' },
      { gate: 'scripts/verify-project-tests.sh', kind: 'entrypoint', noSelfTestReason: 'x', noCiReason: 'y' },
    ]);
    assert.deepEqual(kinds(violations), [VIOLATION_KINDS.DUPLICATE_REGISTRATION]);
  });
});

test('an unknown kind is reported instead of silently skipping the checks', () => {
  const files = {
    'scripts/verify-alpha.mjs': '',
    'scripts/verify-project-tests.sh': 'node scripts/verify-alpha.mjs\n',
  };
  withTree(files, (root) => {
    const violations = audit(root, [
      { gate: 'scripts/verify-alpha.mjs', kind: 'someday' },
      { gate: 'scripts/verify-project-tests.sh', kind: 'entrypoint', noSelfTestReason: 'x', noCiReason: 'y' },
    ]);
    assert.deepEqual(kinds(violations), [VIOLATION_KINDS.UNKNOWN_GATE_KIND]);
  });
});

test('a gate with neither a self-test nor a reason is reported', () => {
  const files = {
    'scripts/verify-alpha.mjs': '',
    'scripts/verify-project-tests.sh': 'node scripts/verify-alpha.mjs\n',
  };
  withTree(files, (root) => {
    const violations = audit(root, [
      { gate: 'scripts/verify-alpha.mjs', kind: 'gate', noCiReason: 'y' },
      { gate: 'scripts/verify-project-tests.sh', kind: 'entrypoint', noSelfTestReason: 'x', noCiReason: 'y' },
    ]);
    assert.deepEqual(kinds(violations), [VIOLATION_KINDS.MISSING_SELF_TEST]);
  });
});

test('a self-test path that does not exist is reported', () => {
  const files = {
    'scripts/verify-alpha.mjs': '',
    'scripts/verify-project-tests.sh': 'node scripts/verify-alpha.mjs\n',
  };
  withTree(files, (root) => {
    const violations = audit(root, [
      {
        gate: 'scripts/verify-alpha.mjs',
        kind: 'gate',
        selfTest: 'scripts/test-support/ghost-self-test.mjs',
        noCiReason: 'y',
      },
      { gate: 'scripts/verify-project-tests.sh', kind: 'entrypoint', noSelfTestReason: 'x', noCiReason: 'y' },
    ]);
    assert.deepEqual(kinds(violations), [VIOLATION_KINDS.SELF_TEST_NOT_FOUND]);
  });
});

test('a gate no runner executes is reported as an orphan', () => {
  const files = { 'scripts/verify-alpha.mjs': '' };
  withTree(files, (root) => {
    const violations = audit(root, [
      { gate: 'scripts/verify-alpha.mjs', kind: 'gate', noSelfTestReason: 'x' },
    ]);
    assert.ok(kinds(violations).includes(VIOLATION_KINDS.ORPHAN_GATE));
  });
});

test('a gate CI never reaches must say whether that is a decision', () => {
  const files = {
    'scripts/verify-alpha.mjs': '',
    'scripts/verify-project-tests.sh': 'node scripts/verify-alpha.mjs\n',
  };
  withTree(files, (root) => {
    const violations = audit(root, [
      { gate: 'scripts/verify-alpha.mjs', kind: 'gate', noSelfTestReason: 'x' },
      { gate: 'scripts/verify-project-tests.sh', kind: 'entrypoint', noSelfTestReason: 'x', noCiReason: 'y' },
    ]);
    assert.deepEqual(kinds(violations), [VIOLATION_KINDS.MISSING_CI_REASON]);
  });
});

test('a noCiReason that outlived its blocker is reported as stale', () => {
  // The behaviour the user will hit the moment the Batch 806 CI patch lands: the
  // registry says "not in CI" about something CI now runs, and the reason has to
  // be deleted rather than left to rot.
  const files = {
    'scripts/verify-alpha.mjs': '',
    '.github/workflows/ci.yml': '      run: node scripts/verify-alpha.mjs\n',
  };
  withTree(files, (root) => {
    const violations = audit(root, [
      {
        gate: 'scripts/verify-alpha.mjs',
        kind: 'gate',
        noSelfTestReason: 'x',
        noCiReason: 'waiting on a human',
      },
    ]);
    assert.deepEqual(kinds(violations), [VIOLATION_KINDS.STALE_CI_REASON]);
  });
});

test('a manual script owes nothing beyond being registered', () => {
  // 24 verification scripts need a running server and real credentials. Holding
  // them to a CI-wiring rule would be a category error, and holding them to a
  // self-test rule would be theatre.
  const files = { 'scripts/verify-chat-capability.sh': '' };
  withTree(files, (root) => {
    const violations = audit(root, [{ gate: 'scripts/verify-chat-capability.sh', kind: 'manual' }]);
    assert.deepEqual(violations, []);
  });
});

test('an entrypoint is exempt from the orphan rule but not from the CI decision', () => {
  const files = { 'scripts/verify-project-docs.sh': '' };
  withTree(files, (root) => {
    const withoutReason = audit(root, [
      { gate: 'scripts/verify-project-docs.sh', kind: 'entrypoint', noSelfTestReason: 'x' },
    ]);
    assert.deepEqual(kinds(withoutReason), [VIOLATION_KINDS.MISSING_CI_REASON]);
    assert.equal(kinds(withoutReason).includes(VIOLATION_KINDS.ORPHAN_GATE), false);

    const withReason = audit(root, [
      {
        gate: 'scripts/verify-project-docs.sh',
        kind: 'entrypoint',
        noSelfTestReason: 'x',
        noCiReason: 'not yet wired',
      },
    ]);
    assert.deepEqual(withReason, []);
  });
});

test('a gate no document mentions is reported', () => {
  // Measured, not hypothetical: 13 of 21 gates appeared in no document before
  // the rule existed, seven of them WebUI checks nobody could discover.
  const files = {
    'scripts/verify-alpha.mjs': '',
    'scripts/verify-project-tests.sh': 'node scripts/verify-alpha.mjs\n',
    'docs/reference.md': 'A document that mentions nothing useful.\n',
  };
  withTree(files, (root) => {
    const violations = audit(root, [
      {
        gate: 'scripts/verify-alpha.mjs',
        kind: 'gate',
        noSelfTestReason: 'x',
        noCiReason: 'y',
      },
      {
        gate: 'scripts/verify-project-tests.sh',
        kind: 'entrypoint',
        noSelfTestReason: 'aggregate',
        noCiReason: 'y',
      },
    ]);
    assert.deepEqual(kinds(violations), [
      VIOLATION_KINDS.UNDOCUMENTED_GATE,
      VIOLATION_KINDS.UNDOCUMENTED_GATE,
    ]);
  });
});

test('a manual script is exempt from the documentation rule', () => {
  const files = { 'scripts/verify-chat-capability.sh': '', 'docs/reference.md': '' };
  withTree(files, (root) => {
    const violations = audit(root, [{ gate: 'scripts/verify-chat-capability.sh', kind: 'manual' }]);
    assert.deepEqual(violations, []);
  });
});

test('the real registry satisfies the real repository', () => {
  // Guards the other direction: the census must be runnable against this tree,
  // and must pass, or the exemptions above are just paperwork.
  const root = fileURLToPath(new URL('../..', import.meta.url));
  const violations = audit(root, GATES);
  assert.deepEqual(violations, [], JSON.stringify(violations, null, 2));
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

if (failed > 0) {
  console.error(`\n${failed}/${cases.length} gate-wiring self-test case(s) failed.`);
  process.exitCode = 1;
} else {
  console.log(`\nAll ${cases.length} gate-wiring self-test cases passed.`);
}
