#!/usr/bin/env node
/**
 * End-to-end suite reachability.
 *
 * `scripts/verify-integration-test-switches.mjs` answers "can a gated
 * integration suite be switched on?" for the backend. This answers the same
 * question for the Playwright suite, where the equivalent switch is "some
 * verification script names this spec".
 *
 * Batch 804 ran the reachability survey for the first time. Of twenty specs in
 * `spring-ai-rag-webui/e2e`, fourteen are named by a `scripts/verify-*.sh`. Six
 * are not:
 *
 *   - `dashboard.spec.ts`, `evaluation-tabs.spec.ts`, `files.spec.ts` appear
 *     in no script and in no document, archived or otherwise;
 *   - `alignment.spec.ts`, `files-real.spec.ts` and
 *     `workspace-continuity.spec.ts` appear only in `docs/drafts/archive/`
 *     progress notes.
 *
 * Running the first three immediately paid for the survey: 22 tests, 21 passed,
 * and one that could never have passed — a `getByLabel(/Suites|套件/)` that
 * matches both the tabpanel and the section inside it and therefore violates
 * strict mode. It had been committed, and it was dead.
 *
 * The distinction this gate draws between a run path and a mention follows the
 * calibration `verify-integration-test-switches.mjs` already established: an
 * archived progress note is a record of what somebody once did, not something
 * anybody can repeat. Only a script that actually invokes Playwright counts.
 *
 * Run:
 *   node scripts/verify-e2e-run-paths.mjs
 */

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, basename, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const E2E_DIR = join(projectRoot, 'spring-ai-rag-webui', 'e2e');
const SCRIPT_DIR = join(projectRoot, 'scripts');

/**
 * Registered exemptions: specs that a script runs *by glob* rather than by
 * name, and so cannot be matched textually.
 *
 * Each entry must name the script and say how the spec is reached. A spec only
 * belongs here if running that script really does run it — the point of the
 * gate is to be true, not to be quiet, and an exemption that is wrong is worse
 * than a violation because it looks checked.
 */
const COVERED_BY_GLOB = new Map([
  [
    'mock-suite',
    'scripts/verify-webui-e2e-mock.sh runs the whole playwright.preview.config.ts suite, '
      + 'which ignores **/*-real.spec.ts and therefore includes every non-real spec',
  ],
]);

export const VIOLATION_KINDS = Object.freeze({
  UNREACHABLE: 'unreachable-e2e-spec',
  STALE_EXEMPTION: 'stale-glob-exemption',
});

/**
 * The start of a Playwright invocation, e.g. `npx playwright test` or
 * `playwright test --project=chromium`.
 */
const INVOCATION = /playwright\s+test/g;

/** One spec named on a command line. */
const SPEC_TOKEN = /e2e\/([A-Za-z0-9._-]+\.spec\.ts)/g;

function shellScripts(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) out.push(...shellScripts(path));
    else if (entry.endsWith('.sh')) out.push(path);
  }
  return out;
}

export function collectSpecs(e2eDir = E2E_DIR) {
  return readdirSync(e2eDir)
    .filter((name) => name.endsWith('.spec.ts'))
    .sort();
}

/**
 * The text of one shell command, starting at {@code from}.
 *
 * <p>A backslash-continued command ends at the first line that does not end
 * with one. Scanning instead to the next `playwright test` would swallow
 * anything printed between two invocations — an `echo "run e2e/decoy.spec.ts"`
 * would then read as a run path, which is precisely the mention-versus-command
 * distinction this gate exists to draw.
 */
function commandTextAt(source, from) {
  const lines = source.slice(from).split('\n');
  const command = [];
  for (const line of lines) {
    command.push(line);
    if (!line.trimEnd().endsWith('\\')) break;
  }
  return command.join('\n');
}

/**
 * Maps each spec to the scripts that actually invoke it.
 *
 * <p>Every spec token inside a single invocation is attributed to it. Matching
 * spec tokens with one global regex instead would attribute only the first:
 * after the first match `lastIndex` sits past the command, and the remaining
 * names on the same command line — which is how most invocations here are
 * written — would be invisible. That bug made this gate report
 * `files-real.spec.ts` as unreachable while a script plainly named it on the
 * following line.
 */
export function collectRunPaths(scriptDir = SCRIPT_DIR) {
  const bySpec = new Map();
  for (const path of shellScripts(scriptDir)) {
    const source = readFileSync(path, 'utf8');
    INVOCATION.lastIndex = 0;
    let match;
    while ((match = INVOCATION.exec(source)) !== null) {
      const command = commandTextAt(source, match.index);
      SPEC_TOKEN.lastIndex = 0;
      let spec;
      while ((spec = SPEC_TOKEN.exec(command)) !== null) {
        if (!bySpec.has(spec[1])) bySpec.set(spec[1], []);
        const owners = bySpec.get(spec[1]);
        if (!owners.includes(path)) owners.push(path);
      }
    }
  }
  return bySpec;
}

/**
 * Reads the preview suite's own `testIgnore` so the exemption follows the
 * config instead of restating it.
 *
 * <p>Hard-coding `!name.endsWith('-real.spec.ts')` here would be a second place
 * to forget: change the config's ignore pattern and this gate would keep
 * claiming coverage the suite no longer provides. The pattern is matched with
 * the same `*` semantics Playwright uses, so adding a second pattern to the
 * config needs no change here.
 */
export function parsePreviewIgnore(configPath) {
  try {
    const source = readFileSync(configPath, 'utf8');
    const patterns = [];
    const block = /testIgnore\s*:\s*\[([^\]]*)\]/.exec(source);
    if (block) {
      for (const raw of block[1].split(',')) {
        const value = raw.trim().replace(/^['"`]|['"`]$/g, '');
        if (value) patterns.push(value);
      }
    }
    return patterns;
  } catch {
    return [];
  }
}

/**
 * Translates a Playwright `testIgnore` glob into an anchored RegExp.
 *
 * <p>A double star spans directory separators and a single star does not, which
 * is why the preview config's ignore pattern is written with a leading double
 * star: a single star would miss the specs one directory deeper and the
 * exemption would quietly stop covering them.
 *
 * <p>The placeholder used while reordering the two globs is a NUL, written as
 * an escape rather than as a literal byte. Batch 768 found a NUL written
 * literally into a tracked file in this repository, which made git treat the
 * file as binary and silently switch off its diff, blame and text search —
 * with nothing reporting an error. A sentinel is not worth that.
 */
function globToRegExp(pattern) {
  const SENTINEL = '\u0000';
  const escaped = pattern
    .replace(/[.+^${}()|[\]\\]/g, '\\$&')
    .replace(/\*\*/g, SENTINEL)
    .replace(/\*/g, '[^/]*')
    .replace(new RegExp(SENTINEL, 'g'), '.*');
  return new RegExp(`^${escaped}$`);
}

/** True when the preview suite would run this spec, i.e. nothing ignores it. */
export function coveredByPreviewSuite(name, ignorePatterns = []) {
  const relative = `e2e/${name}`;
  return !ignorePatterns
    .map(globToRegExp)
    .some((re) => re.test(relative));
}

export function checkReachability({ specs, runPaths, ignorePatterns = [], coveredByGlob = COVERED_BY_GLOB }) {
  const violations = [];

  for (const name of specs) {
    if (runPaths.has(name)) continue;
    if (coveredByGlob.has('mock-suite') && coveredByPreviewSuite(name, ignorePatterns)) {
      continue;
    }
    violations.push({
      kind: VIOLATION_KINDS.UNREACHABLE,
      detail:
        `e2e/${name} is not named by any Playwright invocation in scripts/, and the`
        + ' preview suite ignores it. A spec nobody can run is a spec whose'
        + ' assertions have never been observed.',
    });
  }

  // The exemption is only honest while the config it names still exists. If the
  // preview config is renamed or loses its testIgnore, the glob registration
  // would keep reporting coverage that nothing provides.
  if (coveredByGlob.has('mock-suite') && ignorePatterns.length === 0) {
    violations.push({
      kind: VIOLATION_KINDS.STALE_EXEMPTION,
      detail:
        'the mock-suite glob exemption is registered but '
        + 'spring-ai-rag-webui/playwright.preview.config.ts declares no testIgnore,'
        + ' so the suite would try to run the -real specs and cannot be the basis'
        + ' for a blanket exemption',
    });
  }

  return violations;
}

function main() {
  const specs = collectSpecs();
  const runPaths = collectRunPaths();
  const configPath = join(
    projectRoot, 'spring-ai-rag-webui', 'playwright.preview.config.ts',
  );
  const ignorePatterns = parsePreviewIgnore(configPath);

  // Real specs need a live backend, which no preview-style gate can provide.
  // They are held to the same bar as everything else: a script must name them.
  const violations = checkReachability({ specs, runPaths, ignorePatterns });

  if (violations.length > 0) {
    console.error('E2E reachability violations:');
    for (const v of violations) console.error(`- [${v.kind}] ${v.detail}`);
    console.error(
      '\nA spec that no verification script invokes is a spec whose assertions have\n' +
        'never been observed. Add a script that runs it, or register it in\n' +
        'COVERED_BY_GLOB with the reason it is covered.',
    );
    process.exitCode = 1;
    return;
  }

  const named = specs.filter((s) => runPaths.has(s)).length;
  console.log(
    `E2E reachability passed; ${specs.length} spec(s) all have a run path `
      + `(${named} named by a script, ${specs.length - named} covered by the mock`
      + ` preview suite, which ignores ${ignorePatterns.join(', ')}).`,
  );
}

if (process.argv[1] && import.meta.url === `file://${resolve(process.argv[1])}`) {
  main();
}
