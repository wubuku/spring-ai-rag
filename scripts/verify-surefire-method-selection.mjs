#!/usr/bin/env node
// Does a script that selects surefire tests by method name check that the names exist?
//
// Batch 925. Surefire reads `-Dtest=Class#a+b` as "run whichever of `a` and `b`
// exist", and twenty scripts in this repository pass
// `-Dsurefire.failIfNoSpecifiedTests=false` — legitimately, because they select
// whole classes, where a missing class fails loudly. Method names are the
// exception: a stale one produces **silence**. The run is shorter, the report is
// written, and nothing says which name went missing.
//
// That is not hypothetical. `verify-next-high-value-feature.sh` named
// `migrationsCreateDurableControlPlanesFromEmptyDatabase` in both of its
// branches; the method is now
// `latestMigrationsCreateDurableControlPlanesFromEmptyDatabase`. The first run
// since 2026-08-21 executed six of the seven methods it named, and the only
// thing in the repository that noticed was a hardcoded count carried beside the
// list, which reported
//
//     must run 7 tests without failure/error/skip; got tests=6, failures=0, ...
//
// A perfect run, described as a broken one, naming neither the missing method
// nor the rename that caused it.
//
// ## What counts
//
// A script that builds a surefire selector containing `#` — whether written out
// or assembled from a variable, which is how the one instance does it. It must
// source `scripts/lib/surefire-method-selection.sh`, which is where the check
// lives.
//
// ## What deliberately does not
//
//   - Class-only selectors. `failIfNoSpecifiedTests=false` is the right call
//     there: a class that does not exist leaves no report, and the script's
//     `[[ -f "$report" ]]` check is what catches it. Twenty scripts rely on
//     that and none of them needs this.
//   - Playwright and any other runner's selectors. The failure being prevented
//     is specific to surefire's "run whichever exist" reading of `a+b`.
//
// The rule is a wiring check rather than a text check on the assertion, because
// "does this script verify the names" is not decidable by looking at it — the
// check lives in a shared library for the same reason Batch 894 moved the
// report reader there, and the wiring is what is decidable.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

import { stripShellComments, shellFiles } from './lib/json-assertion-check.mjs';

export const VIOLATION_KIND = 'unchecked-surefire-method-selection';

/** The library that holds the check a method-selecting script must use. */
export const METHOD_SELECTION_SOURCE = 'scripts/lib/surefire-method-selection.sh';

/**
 * A surefire method selector: a quoted string that *begins* with an identifier
 * immediately followed by `#`.
 *
 * The anchoring is the whole rule, and getting it wrong is how the first version
 * of this gate reported nothing on the very script it was written for. That
 * script builds the selector as `local selector="Class#${METHODS}"` and then
 * passes `"-Dtest=${selector}"`, so a rule looking for `#` next to `-Dtest=`
 * can never match it — the `#` is in the variable's value, not in the argument.
 *
 * Requiring the identifier to follow a quote or `-Dtest=` is what separates a
 * method selector from a shell parameter expansion, and the six on the real tree
 * are all the latter: `${version#1.}`, `${TOKEN#PURGE_REAL_}`, `"$#"`,
 * `${LIFECYCLE_JDBC_URL#jdbc:}`, `${suite##*:}`, `${public_path#/webui/}`. There
 * `#` strips a prefix; it is an operator, not a separator, and a parameter
 * expansion always begins with `$`.
 */
const METHOD_SELECTOR = /(?:["']|-Dtest=["']?)[A-Za-z_][\w$]*#/;

/**
 * @typedef {object} Violation
 * @property {string} gate   the script's path relative to the repository root
 * @property {string} detail what it selects and why it is unchecked
 */

/**
 * The pure half: everything it needs arrives as text, so the self-test can
 * drive it with fixtures instead of a real repository.
 *
 * @param {string} source the script text
 * @returns {Violation|null}
 */
export function checkMethodSelection(source) {
  const code = stripShellComments(source);
  const match = code.match(METHOD_SELECTOR);
  if (!match) return null;
  if (code.includes('scripts/lib/surefire-method-selection.sh')) return null;
  return {
    gate: '',
    detail:
      'it builds a surefire method selector without checking that those methods '
      + 'exist. Surefire runs whichever names it finds and '
      + '-Dsurefire.failIfNoSpecifiedTests=false makes the rest silent, so a renamed '
      + 'test stops being verified with nothing saying so. Source '
      + `${METHOD_SELECTION_SOURCE} and call assert_surefire_methods_exist before Maven.`,
  };
}

/**
 * @param {string} scriptsDir
 * @param {string} [repoRoot]
 * @returns {Violation[]}
 */
export function collectMethodSelections(scriptsDir, repoRoot) {
  const out = [];
  for (const file of shellFiles(scriptsDir)) {
    const found = checkMethodSelection(readFileSync(file, 'utf8'));
    if (found) {
      out.push({ ...found, gate: relative(repoRoot ?? scriptsDir, file) });
    }
  }
  return out;
}

function main() {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  const violations = collectMethodSelections(join(root, 'scripts'), root);
  if (violations.length > 0) {
    console.error(`Unchecked surefire method selection (${VIOLATION_KIND}):`);
    for (const v of violations) {
      console.error(`  ${v.gate}: ${v.detail}`);
    }
    process.exitCode = 1;
    return;
  }
  console.log(
    'Every script that selects surefire tests by method name verifies the names '
    + `first, via ${METHOD_SELECTION_SOURCE}.`,
  );
}

if (process.argv[1] && process.argv[1].endsWith('verify-surefire-method-selection.mjs')) {
  main();
}
