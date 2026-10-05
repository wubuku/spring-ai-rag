#!/usr/bin/env node
/**
 * Integration-test switch reconciliation.
 *
 * Twenty-two PostgreSQL/Testcontainers integration classes in this repository
 * are gated behind `@EnabledIfSystemProperty(named = "<x>.it.enabled")`, which
 * is what keeps 145+ test methods from demanding a Docker daemon on every
 * `mvn test`. That gating is invisible by design — and Batch 790's survey found
 * that one of the twenty-two, `PdfImportPostgresIntegrationTest`, had no way to
 * be switched on that anybody could find: its property appeared in no script
 * and in no non-draft document, only in an archived progress note. Two tests
 * nobody could run, sitting in a suite that read as complete.
 *
 * The same silence is available to the next class that is added, so this gate
 * reconciles the switches in both directions, the way
 * `scripts/verify-test-visibility.mjs` reconciles the source tree against the
 * Surefire reports:
 *
 *   1. undiscoverable-switch  a gated class whose switch no run path turns on
 *   2. ghost-switch           a switch a script or document turns on that no
 *                             gated class consumes
 *   3. empty-gated-suite      a gated class with no `@Test` in it
 *   4. gated-runner-drift     a `verify-gated-it.sh` suite entry whose class is
 *                             gone, or whose flag no longer matches that class
 *
 * Run:
 *   node scripts/verify-integration-test-switches.mjs
 */

import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isMainModule } from './lib/is-main-module.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const TEST_ROOT = join(projectRoot, 'spring-ai-rag-core', 'src', 'test', 'java');
const GATED_RUNNER = join(projectRoot, 'scripts', 'verify-gated-it.sh');
const LEDGER_DIR = join('docs', 'drafts');

/**
 * A switch name, e.g. `chat.idempotency.it.enabled`.
 *
 * Deliberately *not* `\b`-anchored on the left. Every run path writes it as
 * `-Dchat.it.enabled=true`, and `\b` asserts a word boundary between the `D`
 * and the `c` — two word characters, so no boundary exists and the pattern
 * silently matched the suffix `it.enabled` instead. The gate would then have
 * believed every switch was undiscoverable. Anchor on the character class.
 */
const SWITCH = /[a-z0-9][a-z0-9.-]*\.it\.enabled(?![a-z0-9-])/g;

/**
 * A switch that is actually *turned on*, as opposed to merely mentioned.
 *
 * A property name in a prose list is not a run path. Three shapes are:
 * `-D<switch>` on a command line, `<switch>=true` in a properties file, and
 * `named = "<switch>"` in a test annotation. Calibrated on this repository:
 * all 21 discoverable switches match one of these, and the only prose mentions
 * are the ones already counted, so requiring a turn-on shape costs no recall
 * and refuses a hand-wave.
 */
const TURNED_ON = new RegExp(
  [
    '-D([a-z0-9][a-z0-9.-]*\\.it\\.enabled)(?![a-z0-9.-])',
    '([a-z0-9][a-z0-9.-]*\\.it\\.enabled)(?![a-z0-9.-])=true',
    'named\\s*=\\s*"([a-z0-9][a-z0-9.-]*\\.it\\.enabled)(?![a-z0-9.-])"',
  ].join('|'),
  'g',
);

const GATED_SUITE = /@EnabledIfSystemProperty\(\s*named\s*=\s*"([a-z0-9][a-z0-9.-]*)"\s*,\s*matches\s*=\s*"true"/g;

const RUN_PATH_SUFFIX = Object.freeze(['.md', '.sh', '.yml', '.yaml', '.properties']);

export const VIOLATION_KINDS = Object.freeze([
  'undiscoverable-switch',
  'ghost-switch',
  'empty-gated-suite',
  'gated-runner-drift',
]);

function walkFiles(dir, acc = []) {
  if (!existsSync(dir)) return acc;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) walkFiles(path, acc);
    else if (entry.name.endsWith('.java')) acc.push(path);
  }
  return acc;
}

/**
 * `docs/drafts` is the batch ledger and its archive: a historical record of
 * what someone once typed, not an instruction anyone follows. A switch that
 * only appears there is exactly the drift being hunted — `pdf-import.it.enabled`
 * was referenced from one archived progress note and from nowhere else.
 *
 * The exclusion is resolved *relative to the walk root*, not against the
 * repository root this file happens to live in. Pinning it to the module-level
 * `projectRoot` made the function untestable against a fixture tree, and the
 * first draft of this self-test caught exactly that.
 */
function isLedgerPath(walkRoot, path) {
  const rel = relative(walkRoot, path);
  return rel === LEDGER_DIR || rel.startsWith(LEDGER_DIR + sep);
}

/** Every `.md`/`.sh`/`.yml`/`.properties` file that could carry a run path. */
function walkRunPaths(dir, walkRoot = dir, acc = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith('.') || entry.name === 'node_modules' || entry.name === 'target') continue;
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (isLedgerPath(walkRoot, path)) continue;
      walkRunPaths(path, walkRoot, acc);
    } else if (RUN_PATH_SUFFIX.some(suffix => entry.name.endsWith(suffix)) && statSync(path).isFile()) {
      acc.push(path);
    }
  }
  return acc;
}

/**
 * @returns {Map<string, {property: string, file: string, className: string, testCount: number}[]>}
 *   gated switches -> the classes that consume them
 */
export function collectGatedSuites(testRoot) {
  const byProperty = new Map();
  for (const file of walkFiles(testRoot)) {
    const source = readFileSync(file, 'utf8');
    // An abstract base carries no tests and is never instantiated; Surefire
    // correctly ignores it, so it must not be asked to be discoverable.
    if (/\babstract\s+class\b/.test(source)) continue;
    GATED_SUITE.lastIndex = 0;
    let match;
    while ((match = GATED_SUITE.exec(source)) !== null) {
      const property = match[1];
      if (!property.endsWith('.it.enabled')) continue;
      const testCount = (source.match(/(?<![A-Za-z0-9_])@Test(?![A-Za-z0-9_])/g) || []).length;
      if (!byProperty.has(property)) byProperty.set(property, []);
      byProperty.get(property).push({
        property,
        file,
        className: file.slice(file.lastIndexOf('/') + 1).replace(/\.java$/, ''),
        testCount,
      });
    }
  }
  return byProperty;
}

/**
 * @returns {Map<string, string[]>} switch -> run paths that turn it on
 */
export function collectRunPaths(repoRoot) {
  const runPaths = new Map();
  for (const file of walkRunPaths(repoRoot)) {
    const text = readFileSync(file, 'utf8');
    TURNED_ON.lastIndex = 0;
    let match;
    const seenHere = new Set();
    while ((match = TURNED_ON.exec(text)) !== null) {
      const property = match[1] ?? match[2] ?? match[3];
      if (!property?.endsWith('.it.enabled') || seenHere.has(property)) continue;
      seenHere.add(property);
      if (!runPaths.has(property)) runPaths.set(property, []);
      runPaths.get(property).push(relative(repoRoot, file));
    }
  }
  return runPaths;
}

/**
 * Parses the `ALL_SUITES` registry of `scripts/verify-gated-it.sh`, which is
 * written as `"<switch-prefix>:<TestClassName>"` one per line.
 *
 * @returns {{flag: string, className: string}[]}
 */
export function parseGatedRunner(scriptText) {
  const suites = [];
  const block = scriptText.match(/ALL_SUITES=\(\n([\s\S]*?)\n\)/);
  if (!block) return suites;
  for (const line of block[1].split('\n')) {
    const entry = line.match(/^\s*"([^":]+):([^"]+)"\s*$/);
    if (entry) suites.push({ flag: entry[1], className: entry[2] });
  }
  return suites;
}

/**
 * @param {object} input
 * @param {Map} [input.gatedSuites]  property -> consuming classes
 * @param {Map} [input.runPaths]     property -> run paths that turn it on
 * @param {string[]} [input.runnerSuites] entries of verify-gated-it.sh
 * @param {Map<string, string>} [input.gatedSuitesByClass] class name -> property
 * @param {Set<string>} [input.knownUnd iscoverable] registered exemptions
 * @returns {{kind: string, detail: string}[]}
 */
export function checkSwitches({
  gatedSuites,
  runPaths,
  runnerSuites = [],
  gatedSuitesByClass = new Map(),
  knownUndiscoverable = new Map(),
}) {
  const violations = [];

  for (const [property, classes] of [...gatedSuites].sort()) {
    if (!runPaths.has(property)) {
      const exemption = knownUndiscoverable.get(property);
      violations.push({
        kind: 'undiscoverable-switch',
        detail: classes
          .map(c => `${c.className} (${c.testCount} test(s))`)
          .join(', ') + (exemption ? ` [exempt: ${exemption}]` : ''),
      });
    }
    for (const entry of classes) {
      if (entry.testCount === 0) {
        violations.push({
          kind: 'empty-gated-suite',
          detail: `${entry.className} is gated on ${property} but declares no @Test`,
        });
      }
    }
  }

  for (const [property, paths] of [...runPaths].sort()) {
    if (!gatedSuites.has(property)) {
      violations.push({
        kind: 'ghost-switch',
        detail: `${property} is turned on by ${[...new Set(paths)].slice(0, 3).join(', ')} but no test class consumes it`,
      });
    }
  }

  const seenClasses = new Set();
  for (const { flag, className } of runnerSuites) {
    const expected = `${flag}.it.enabled`;
    if (seenClasses.has(className)) {
      violations.push({
        kind: 'gated-runner-drift',
        detail: `verify-gated-it.sh registers ${className} more than once`,
      });
      continue;
    }
    seenClasses.add(className);
    if (!gatedSuitesByClass.has(className)) {
      violations.push({
        kind: 'gated-runner-drift',
        detail: `verify-gated-it.sh runs ${className}, which is missing or no longer gated by an *.it.enabled switch`,
      });
      continue;
    }
    const actual = gatedSuitesByClass.get(className);
    if (actual !== expected) {
      violations.push({
        kind: 'gated-runner-drift',
        detail: `verify-gated-it.sh passes -D${expected} but ${className} is gated on ${actual}`,
      });
    }
  }

  return violations;
}

function main() {
  const gatedSuites = collectGatedSuites(TEST_ROOT);
  const runPaths = collectRunPaths(projectRoot);
  const runnerSuites = existsSync(GATED_RUNNER)
    ? parseGatedRunner(readFileSync(GATED_RUNNER, 'utf8'))
    : [];
  const gatedSuitesByClass = new Map();
  for (const [, classes] of gatedSuites) {
    for (const entry of classes) gatedSuitesByClass.set(entry.className, entry.property);
  }

  const violations = checkSwitches({
    gatedSuites,
    runPaths,
    runnerSuites,
    gatedSuitesByClass,
    knownUndiscoverable: KNOWN_UNDISCOVERABLE,
  });

  if (violations.length > 0) {
    console.error('Integration-test switch violations:');
    for (const v of violations) console.error(`- [${v.kind}] ${v.detail}`);
    console.error(
      '\nA gated integration suite that no run path can switch on is a suite that\n' +
        'never runs. Add the switch to a script or to docs/testing-guide*.md, or\n' +
        'register it in KNOWN_UNDISCOVERABLE with a reason.',
    );
    process.exitCode = 1;
    return;
  }

  const gatedTestCount = [...gatedSuites.values()].reduce((sum, cs) => sum + cs.reduce((s, c) => s + c.testCount, 0), 0);
  console.log(
    `Integration-test switch reconciliation passed; ${gatedSuites.size} gated switch(es) ` +
      `covering ${gatedTestCount} test(s) all have a run path, and no run path names a switch ` +
      `without a test class (${runnerSuites.length} suite(s) in verify-gated-it.sh).`,
  );
}

/**
 * Registered exemptions. Deliberately empty: Batch 790 fixed the one real
 * drift (`pdf-import.it.enabled`, two unreachable tests) rather than excusing
 * it. Each entry would need a reason, must still be broken, and would be capped
 * by a ceiling that may only shrink — the shape `KNOWN_DRIFT` already uses in
 * `scripts/lib/docs-integrity-check.mjs`.
 */
const KNOWN_UNDISCOVERABLE = new Map();

if (isMainModule(import.meta.url)) {
  main();
}
