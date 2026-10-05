#!/usr/bin/env node
// Negative tests for scripts/verify-integration-test-switches.mjs.
//
// A gate that cannot fail is worse than no gate, and this repository has a
// documented history of producing exactly that defect — twice inside a single
// batch (Batch 768's design-language document claiming "ten classes" while the
// checker enforced eleven; Batch 790's own first draft, which used
// `['.md', '.sh'].includes(entry.name)` and therefore walked zero files and
// reported every switch undiscoverable). Every case below therefore asserts
// that the checker *rejects* the shape it claims to reject, and one case
// re-derives the `-D` anchoring bug that would otherwise pass silently.

import assert from 'node:assert/strict';
import {
  checkSwitches,
  collectGatedSuites,
  collectRunPaths,
  parseGatedRunner,
  VIOLATION_KINDS,
} from '../verify-integration-test-switches.mjs';
import { mkdirSync, mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

/** Materialises a throwaway repository so the filesystem walkers can be tested. */
function withRepo(build, fn) {
  const dir = mkdtempSync(join(tmpdir(), 'it-switch-'));
  try {
    build(dir);
    return fn(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

const write = (root, relativePath, contents) => {
  const path = join(root, relativePath);
  mkdirSync(join(path, '..'), { recursive: true });
  writeFileSync(path, contents);
};

/** The shape of a gated test class, kept short on purpose. */
const gatedClass = (property, { tests = 1, abstractClass = false } = {}) =>
  `package com.example;\n
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
${abstractClass ? 'public abstract class ' : 'class '}Sample {
${'@EnabledIfSystemProperty(named = "' + property + '", matches = "true")\n'}
${Array.from({ length: tests }, (_, i) => `  @Test void t${i}() {}`).join('\n')}
}
`;

/**
 * A coherent little world: the fixture's gated class really is aggregated.
 *
 * Every rule-1-to-3 fixture below supplies a gated class and a run path but no
 * aggregate runner — which, since Batch 912, is itself a violation, because a
 * per-feature run path answers "can I run this one suite" and nothing else
 * answers "can I run the gated inventory". Those fixtures now register the
 * class, so each case asserts the one rule it is about and the aggregate
 * question does not leak into it.
 */
const aggregated = (className, property) => ({
  runnerSuites: [{ flag: property.replace(/\.it\.enabled$/, ''), className }],
  gatedSuitesByClass: new Map([[className, property]]),
});

// ── 1. undiscoverable-switch ────────────────────────────────────────────────

test('reports a gated suite that no run path switches on', () => {
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/ChatIt.java', gatedClass('chat.it.enabled', { tests: 18 }));
      write(dir, 'scripts/other.sh', '#!/usr/bin/env bash\necho hi\n');
    },
    dir => {
      const gated = collectGatedSuites(join(dir, 'core/src/test/java'));
      const paths = collectRunPaths(dir);
      const violations = checkSwitches({
        gatedSuites: gated,
        runPaths: paths,
        ...aggregated('ChatIt', 'chat.it.enabled'),
      });
      assert.equal(violations.length, 1);
      assert.equal(violations[0].kind, 'undiscoverable-switch');
      assert.match(violations[0].detail, /ChatIt \(18 test\(s\)\)/);
    },
  );
});

test('accepts the switch once a run path turns it on', () => {
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/ChatIt.java', gatedClass('chat.it.enabled', { tests: 18 }));
      write(dir, 'scripts/verify-chat.sh', 'mvn test -Dchat.it.enabled=true -Dtest=ChatIt\n');
    },
    dir => {
      const violations = checkSwitches({
        gatedSuites: collectGatedSuites(join(dir, 'core/src/test/java')),
        runPaths: collectRunPaths(dir),
        ...aggregated('ChatIt', 'chat.it.enabled'),
      });
      assert.deepEqual(violations, []);
    },
  );
});

test('a prose mention is not a run path', () => {
  // The whole point of the turn-on shape: naming the switch in a list is not
  // the same as handing anyone a way to run it.
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/PdfIt.java', gatedClass('pdf-import.it.enabled', { tests: 2 }));
      write(dir, 'docs/guide.md', 'The `pdf-import.it.enabled` switch gates the suite.\n');
    },
    dir => {
      const violations = checkSwitches({
        gatedSuites: collectGatedSuites(join(dir, 'core/src/test/java')),
        runPaths: collectRunPaths(dir),
        ...aggregated('PdfIt', 'pdf-import.it.enabled'),
      });
      assert.deepEqual(violations.map(v => v.kind), ['undiscoverable-switch']);
    },
  );
});

test('an archived progress note does not count as a run path', () => {
  // This is the real Batch 790 finding: the switch was documented in exactly
  // one place, under docs/drafts/archive, and nowhere else.
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/PdfIt.java', gatedClass('pdf-import.it.enabled', { tests: 2 }));
      write(dir, 'docs/drafts/archive/progress.md', '-Dpdf-import.it.enabled=true passed 2/2\n');
      write(dir, 'docs/drafts/LEDGER.md', '-Dpdf-import.it.enabled=true\n');
    },
    dir => {
      const violations = checkSwitches({
        gatedSuites: collectGatedSuites(join(dir, 'core/src/test/java')),
        runPaths: collectRunPaths(dir),
        ...aggregated('PdfIt', 'pdf-import.it.enabled'),
      });
      assert.deepEqual(violations.map(v => v.kind), ['undiscoverable-switch']);
    },
  );
});

test('a -D switch is matched whole, not by its suffix', () => {
  // Regression for the bug that shipped in this file's first draft: a `\b`
  // before the name asserts a word boundary between the `D` of `-D` and the
  // first letter of the switch. Both are word characters, so no boundary
  // exists, and every `-D`-written switch silently failed to match.
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/ChatIt.java', gatedClass('chat.it.enabled', { tests: 3 }));
      write(dir, 'scripts/run.sh', 'mvn test -Dchat.it.enabled=true\n');
    },
    dir => {
      const paths = collectRunPaths(dir);
      assert.deepEqual([...paths.keys()], ['chat.it.enabled']);
    },
  );
});

test('a differently-named property does not satisfy a gated switch', () => {
  // `chat.it.enabled.extra` is a different property, so it must not be
  // trimmed down into `chat.it.enabled`. Trimming it would report a suite as
  // discoverable on the strength of an unrelated switch.
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/ChatIt.java', gatedClass('chat.it.enabled', { tests: 3 }));
      write(dir, 'scripts/run.sh', 'mvn test -Dchat.it.enabled.extra=true\n');
    },
    dir => {
      const paths = collectRunPaths(dir);
      assert.deepEqual([...paths.keys()], []);
      const violations = checkSwitches({
        gatedSuites: collectGatedSuites(join(dir, 'core/src/test/java')),
        runPaths: paths,
        ...aggregated('ChatIt', 'chat.it.enabled'),
      });
      assert.deepEqual(violations.map(v => v.kind), ['undiscoverable-switch']);
    },
  );
});

test('an abstract base is not asked to be discoverable', () => {
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/BaseIt.java', gatedClass('chat.it.enabled', { abstractClass: true }));
    },
    dir => {
      const gated = collectGatedSuites(join(dir, 'core/src/test/java'));
      assert.equal(gated.size, 0);
    },
  );
});

// ── 2. ghost-switch ─────────────────────────────────────────────────────────

test('reports a run path that names a switch no test class consumes', () => {
  withRepo(
    dir => {
      write(dir, 'scripts/verify-old.sh', 'mvn test -Dretired-suite.it.enabled=true\n');
    },
    dir => {
      const violations = checkSwitches({
        gatedSuites: collectGatedSuites(join(dir, 'core/src/test/java')),
        runPaths: collectRunPaths(dir),
      });
      assert.deepEqual(violations.map(v => v.kind), ['ghost-switch']);
      assert.match(violations[0].detail, /scripts\/verify-old\.sh/);
    },
  );
});

test('a properties-file switch counts as turned on', () => {
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/ChatIt.java', gatedClass('chat.it.enabled', { tests: 1 }));
      write(dir, 'ci/it.properties', 'chat.it.enabled=true\n');
    },
    dir => {
      const violations = checkSwitches({
        gatedSuites: collectGatedSuites(join(dir, 'core/src/test/java')),
        runPaths: collectRunPaths(dir),
        ...aggregated('ChatIt', 'chat.it.enabled'),
      });
      assert.deepEqual(violations, []);
    },
  );
});

// ── 3. empty-gated-suite ────────────────────────────────────────────────────

test('reports a gated class that declares no test', () => {
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/EmptyIt.java', gatedClass('chat.it.enabled', { tests: 0 }));
      write(dir, 'scripts/run.sh', 'mvn test -Dchat.it.enabled=true\n');
    },
    dir => {
      const violations = checkSwitches({
        gatedSuites: collectGatedSuites(join(dir, 'core/src/test/java')),
        runPaths: collectRunPaths(dir),
        ...aggregated('EmptyIt', 'chat.it.enabled'),
      });
      assert.deepEqual(violations.map(v => v.kind), ['empty-gated-suite']);
    },
  );
});

// ── 4. gated-runner-drift ───────────────────────────────────────────────────

const runner = entries => `#!/usr/bin/env bash\nALL_SUITES=(\n${entries
  .map(e => `  "${e.flag}:${e.className}"`)
  .join('\n')}\n)\n`;

test('parses the verify-gated-it.sh suite registry', () => {
  const parsed = parseGatedRunner(
    runner([
      { flag: 'document-sync-runs', className: 'DocumentSyncRunsPostgresIntegrationTest' },
      { flag: 'chat.idempotency', className: 'ChatTurnOperationPostgresIntegrationTest' },
    ]),
  );
  assert.deepEqual(parsed, [
    { flag: 'document-sync-runs', className: 'DocumentSyncRunsPostgresIntegrationTest' },
    { flag: 'chat.idempotency', className: 'ChatTurnOperationPostgresIntegrationTest' },
  ]);
});

test('reports a runner entry whose class no longer exists', () => {
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/ChatIt.java', gatedClass('chat.it.enabled', { tests: 3 }));
      write(dir, 'scripts/run.sh', 'mvn test -Dchat.it.enabled=true\n');
    },
    dir => {
      const gated = collectGatedSuites(join(dir, 'core/src/test/java'));
      const violations = checkSwitches({
        gatedSuites: gated,
        runPaths: collectRunPaths(dir),
        runnerSuites: [{ flag: 'retired', className: 'DeletedPostgresIntegrationTest' }],
        gatedSuitesByClass: new Map([['ChatIt', 'chat.it.enabled']]),
      });
      // Two kinds, and both are true. A runner entry pointing at a deleted class
      // is how the class that *does* exist silently loses its aggregate slot, so
      // `unaggregated-gated-suite` is the same drift seen from the other end
      // rather than a second, independent complaint.
      assert.deepEqual(violations.map(v => v.kind), [
        'gated-runner-drift',
        'unaggregated-gated-suite',
      ]);
      assert.match(violations[0].detail, /DeletedPostgresIntegrationTest/);
    },
  );
});

test('reports a runner flag that no longer matches the class it runs', () => {
  // The silent one: `mvn test -Dwrong.it.enabled=true -Dtest=ChatIt` runs
  // nothing, Surefire reports zero tests, and the suite reads as passing.
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/ChatIt.java', gatedClass('chat.it.enabled', { tests: 3 }));
      write(dir, 'scripts/run.sh', 'mvn test -Dchat.it.enabled=true\n');
    },
    dir => {
      const violations = checkSwitches({
        gatedSuites: collectGatedSuites(join(dir, 'core/src/test/java')),
        runPaths: collectRunPaths(dir),
        runnerSuites: [{ flag: 'wrong', className: 'ChatIt' }],
        gatedSuitesByClass: new Map([['ChatIt', 'chat.it.enabled']]),
      });
      assert.deepEqual(violations.map(v => v.kind), ['gated-runner-drift']);
      assert.match(violations[0].detail, /-Dwrong\.it\.enabled but ChatIt is gated on chat\.it\.enabled/);
    },
  );
});

test('reports the same class registered twice', () => {
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/ChatIt.java', gatedClass('chat.it.enabled', { tests: 3 }));
      write(dir, 'scripts/run.sh', 'mvn test -Dchat.it.enabled=true\n');
    },
    dir => {
      const violations = checkSwitches({
        gatedSuites: collectGatedSuites(join(dir, 'core/src/test/java')),
        runPaths: collectRunPaths(dir),
        runnerSuites: [
          { flag: 'chat', className: 'ChatIt' },
          { flag: 'chat', className: 'ChatIt' },
        ],
        gatedSuitesByClass: new Map([['ChatIt', 'chat.it.enabled']]),
      });
      assert.deepEqual(violations.map(v => v.kind), ['gated-runner-drift']);
    },
  );
});

// ── 5. unaggregated-gated-suite ─────────────────────────────────────────────

test('reports a gated suite the aggregate runner does not run', () => {
  // The Batch 912 finding. Both switches here have a legitimate run path, so
  // every pre-existing rule was satisfied; what was missing was a single
  // command that runs the gated inventory as a whole.
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/ChatIt.java', gatedClass('chat.it.enabled', { tests: 18 }));
      write(dir, 'core/src/test/java/p/PdfIt.java', gatedClass('pdf-import.it.enabled', { tests: 2 }));
      write(dir, 'scripts/verify-chat.sh', 'mvn test -Dchat.it.enabled=true -Dtest=ChatIt\n');
      write(dir, 'scripts/verify-pdf.sh', 'mvn test -Dpdf-import.it.enabled=true -Dtest=PdfIt\n');
    },
    dir => {
      const violations = checkSwitches({
        gatedSuites: collectGatedSuites(join(dir, 'core/src/test/java')),
        runPaths: collectRunPaths(dir),
        runnerSuites: [],
        gatedSuitesByClass: new Map(),
      });
      assert.deepEqual(violations.map(v => v.kind), [
        'unaggregated-gated-suite',
        'unaggregated-gated-suite',
      ]);
      assert.match(violations[0].detail, /ChatIt is gated on chat\.it\.enabled and holds 18 test\(s\)/);
      assert.match(violations[0].detail, /verify-gated-it\.sh does not run it/);
    },
  );
});

test('accepts a gated suite the aggregate runner runs', () => {
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/ChatIt.java', gatedClass('chat.it.enabled', { tests: 18 }));
      write(dir, 'scripts/verify-chat.sh', 'mvn test -Dchat.it.enabled=true -Dtest=ChatIt\n');
    },
    dir => {
      const violations = checkSwitches({
        gatedSuites: collectGatedSuites(join(dir, 'core/src/test/java')),
        runPaths: collectRunPaths(dir),
        ...aggregated('ChatIt', 'chat.it.enabled'),
      });
      assert.deepEqual(violations, []);
    },
  );
});

test('an unaggregated-suite exemption annotates the failure, it does not silence it', () => {
  // The contract `KNOWN_UNDISCOVERABLE` already uses, and the one that keeps the
  // registry from becoming a suppression list. A registry that turned the gate
  // green would be a baseline wearing a registry's clothes — and the reason
  // would be filed next to the red instead of beside the green.
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/PdfIt.java', gatedClass('pdf-import.it.enabled', { tests: 2 }));
      write(dir, 'scripts/verify-pdf.sh', 'mvn test -Dpdf-import.it.enabled=true -Dtest=PdfIt\n');
    },
    dir => {
      const violations = checkSwitches({
        gatedSuites: collectGatedSuites(join(dir, 'core/src/test/java')),
        runPaths: collectRunPaths(dir),
        runnerSuites: [],
        gatedSuitesByClass: new Map(),
        knownUnaggregated: new Map([['PdfIt', 'needs a pdf toolchain on PATH']]),
      });
      assert.equal(violations.length, 1);
      assert.equal(violations[0].kind, 'unaggregated-gated-suite');
      assert.match(violations[0].detail, /\[exempt: needs a pdf toolchain on PATH\]$/);
    },
  );
});

test('an abstract base is not asked to be aggregated either', () => {
  // `collectGatedSuites` drops abstract classes, and this rule reads that
  // parser's output rather than a caller-supplied class map — so a map that
  // still names an abstract base cannot make the gate report a suite that
  // nothing can run. The concrete class is in the runner here, so the abstract
  // one is the only thing left that could be wrong.
  withRepo(
    dir => {
      write(dir, 'core/src/test/java/p/BaseIt.java', gatedClass('chat.it.enabled', { abstractClass: true }));
      write(dir, 'core/src/test/java/p/ChatIt.java', gatedClass('chat.it.enabled', { tests: 1 }));
      write(dir, 'scripts/verify-chat.sh', 'mvn test -Dchat.it.enabled=true -Dtest=ChatIt\n');
    },
    dir => {
      const violations = checkSwitches({
        gatedSuites: collectGatedSuites(join(dir, 'core/src/test/java')),
        runPaths: collectRunPaths(dir),
        runnerSuites: [{ flag: 'chat', className: 'ChatIt' }],
        gatedSuitesByClass: new Map([
          ['BaseIt', 'chat.it.enabled'],
          ['ChatIt', 'chat.it.enabled'],
        ]),
      });
      assert.deepEqual(violations, []);
    },
  );
});

// ── 6. the gate against the real repository ─────────────────────────────────

test('the real repository reconciles clean', () => {
  // Guards against a self-test that only ever exercises synthetic fixtures: if
  // the walkers drift away from the real tree layout, this is what notices.
  const gated = collectGatedSuites(join(projectRoot, 'spring-ai-rag-core', 'src', 'test', 'java'));
  const paths = collectRunPaths(projectRoot);
  assert.ok(gated.size >= 20, `expected the real gated inventory, found ${gated.size}`);
  assert.ok(paths.size >= 20, `expected the real run paths, found ${paths.size}`);
  const byClass = new Map();
  for (const [property, classes] of gated) for (const c of classes) byClass.set(c.className, property);
  const violations = checkSwitches({
    gatedSuites: gated,
    runPaths: paths,
    runnerSuites: parseGatedRunner(
      readFileSync(join(projectRoot, 'scripts', 'verify-gated-it.sh'), 'utf8'),
    ),
    gatedSuitesByClass: byClass,
  });
  assert.deepEqual(violations, []);
});

test('every kind this gate can emit is declared', () => {
  for (const kind of VIOLATION_KINDS) assert.match(kind, /^[a-z-]+$/);
  assert.equal(VIOLATION_KINDS.length, 5);
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
  console.error(`\n${failures}/${cases.length} integration-switch self-test case(s) failed.`);
  process.exit(1);
}
console.log(`\nAll ${cases.length} integration-switch self-test cases passed.`);
