#!/usr/bin/env node
// Does a Playwright invocation avoid pulling in the real-acceptance specs by accident?
//
// Batch 926. `playwright.config.ts` has no `testIgnore`; `playwright.preview.config.ts`
// and `playwright.hosted-preview.config.ts` both declare
// `testIgnore: ['**/*-real.spec.ts']`. So the shape of the *command* decides
// whether a run includes the five specs that need a live backend and real
// credentials.
//
// `verify-jsonb-records.sh` and `verify-release.sh` both start their own
// `vite preview` — a static server with no backend behind it — and then ran a
// bare `npx playwright test`. That is the whole e2e directory: 93 passed and
// five `*-real.spec.ts` specs failed on *every* run, each with
// "RAG_ROOT_API_KEY is required" or similar. Neither script had ever completed
// in this work tree, so nothing had ever recorded that the step is red for a
// reason no reader can act on — which is how a step becomes one people skip.
//
// ## What counts
//
// A `playwright test` invocation that names no spec file and passes no
// `--config`. It cannot be scoped by argument, and the default config does not
// exclude the real specs.
//
// ## What deliberately does not
//
//   - Invocations that name a spec, whether a single one
//     (`e2e/alerts-real.spec.ts`) or several. Seventeen of the nineteen in this
//     repository are of that kind, and naming `alerts-real.spec.ts` on purpose
//     is the whole point of `verify-alert-notification-delivery.sh`.
//   - Invocations that pass a config. Whether that config excludes the real
//     specs is a property of the config, which `verify-e2e-run-paths.mjs` and
//     the two configs' own headers already state; this rule is about the
//     command falling through to a default nobody chose.
//
// There is no allowlist. The two scripts that had the defect are the two this
// rule was written from, and both now pass the config explicitly.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

import { stripShellComments, shellFiles } from './lib/json-assertion-check.mjs';

export const VIOLATION_KIND = 'unscoped-playwright-invocation';

/** The specs that need a live backend; a preview server has none. */
export const REAL_SPEC_PATTERN = '-real.spec.ts';

/**
 * A `playwright test` invocation and the text of its own command. Line
 * continuations are joined first, because two of the real invocations wrap
 * their spec list onto the next line and a line-oriented reader would see a bare
 * `npx playwright test \` and call it unscoped.
 */
const INVOCATION = /npx\s+playwright\s+test((?:[^\n]*\\\s*\n)*[^\n]*)/g;

/**
 * An argument that scopes the run. Three forms count, and the third one exists
 * because the first version of this rule reported `verify-next-high-value-feature.sh`
 * — which passes `"$PLAYWRIGHT_SPEC"`, and does scope its run by argument. A rule
 * that cannot resolve a variable has to treat the variable as what it is: the
 * author's visible choice of a spec, not evidence of a missing one. The match is
 * deliberately unanchored for the same reason: `"$PLAYWRIGHT_SPEC"` opens with a
 * quote, so a `^\$` would miss the very case it was added for.
 */
const SCOPING_ARGUMENT = /\.spec\.ts|\$\{?[A-Za-z_]|--grep/;

/**
 * @typedef {object} Violation
 * @property {string} gate   the script's path relative to the repository root
 * @property {string} detail what it runs and why that is a problem
 */

/**
 * The pure half: everything it needs arrives as text, so the self-test can
 * drive it with fixtures instead of a real repository.
 *
 * @param {string} source the script text
 * @returns {Violation[]}
 */
export function checkPlaywrightInvocations(source) {
  const code = stripShellComments(source).replace(/\\\s*\n\s*/g, ' ');
  const out = [];
  for (const match of code.matchAll(INVOCATION)) {
    const args = match[1].trim();
    if (SCOPING_ARGUMENT.test(args)) continue;
    if (args.includes('--config')) continue;
    out.push({
      gate: '',
      detail:
        'it runs a bare `npx playwright test`, which uses playwright.config.ts. That '
        + `config has no testIgnore, so the run also includes the five ${REAL_SPEC_PATTERN} `
        + 'specs — which need a live backend and real credentials, and fail on every '
        + 'run for a reason the reader cannot act on. Name the specs the step is '
        + 'about, or pass --config playwright.hosted-preview.config.ts.',
    });
  }
  return out;
}

/**
 * @param {string} scriptsDir
 * @param {string} [repoRoot]
 * @returns {Violation[]}
 */
export function collectPlaywrightInvocations(scriptsDir, repoRoot) {
  const out = [];
  for (const file of shellFiles(scriptsDir)) {
    for (const found of checkPlaywrightInvocations(readFileSync(file, 'utf8'))) {
      out.push({ ...found, gate: relative(repoRoot ?? scriptsDir, file) });
    }
  }
  return out;
}

function main() {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  const violations = collectPlaywrightInvocations(join(root, 'scripts'), root);
  if (violations.length > 0) {
    console.error(`Unscoped Playwright invocation (${VIOLATION_KIND}):`);
    for (const v of violations) {
      console.error(`  ${v.gate}: ${v.detail}`);
    }
    process.exitCode = 1;
    return;
  }
  console.log(
    'Every Playwright invocation either names its specs or passes a config; none of '
    + `them falls through to one that runs the ${REAL_SPEC_PATTERN} suite.`,
  );
}

if (process.argv[1] && process.argv[1].endsWith('verify-playwright-suite-selection.mjs')) {
  main();
}
