#!/usr/bin/env node
// Does a verification script pin the schema version by writing it down?
//
// Batch 923. `verify-alert-notification-delivery.sh` asserted Flyway's latest
// version by writing `58` into an expected-facts string. V59 shipped, the
// script kept reading 58, and an acceptance run that had been correct for a
// month started failing on a fact naming nothing the reader could act on.
//
// This is the same failure as a hardcoded count in prose, and the ledger has
// already paid for that twice: the true thing is the mechanism, and the number
// is the thing that rots. Two sibling scripts already compute it from the
// migration directory (`verify-collection-provisioning.sh`,
// `verify-managed-api-principals.sh` both bind `LATEST_FLYWAY_MIGRATION` before
// use), so the convention exists — this one script had simply not adopted it,
// which is why the rule can be zero-tolerance with no allowlist.
//
// ## What counts
//
// A script that **selects** `version` from `flyway_schema_history` and compares
// the result against a literal integer. Two conditions, both required: the
// query and a nearby integer comparison of that query's output.
//
// ## What deliberately does not
//
//   - A version literal in a migration filename, or in a message. Naming a
//     migration in prose is fine; the failure is *asserting against* a number
//     that someone else will bump.
//   - Scripts that do not select the version at all.
//   - Scripts that compute it. Those are the correct shape and are what this
//     rule exists to push toward.
//
// ## The rule is drawn around a whole-file read, never line by line
//
// The shape spans lines — the query, then the comparison several lines below —
// and a line-oriented scanner reports a clean bill of health on exactly the
// instances it was written for. That mistake was made once today already.

import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

export const VIOLATION_KIND = 'hardcoded-flyway-version';

/**
 * @typedef {object} Violation
 * @property {string} gate   the script's path relative to the repository root
 * @property {string} detail what it hardcodes and where
 */

/**
 * The pure half: everything it needs arrives as text, so the self-test can
 * drive it with fixtures instead of a real repository.
 *
 * @param {string} source the script text
 * @returns {Violation|null}
 */
export function checkHardcodedFlywayVersion(source) {
  // Strip comments first — Batch 915's rule: a text scanner must not count
  // what a comment says it did.
  const code = source
    .split('\n')
    .map(line => {
      const trimmed = line.trim();
      if (trimmed.startsWith('#')) return '';
      let inSingle = false;
      let inDouble = false;
      for (let i = 0; i < line.length; i += 1) {
        const ch = line[i];
        if (ch === "'" && !inDouble) inSingle = !inSingle;
        else if (ch === '"' && !inSingle) inDouble = !inDouble;
        else if (ch === '#' && !inSingle && !inDouble && (i === 0 || /\s/.test(line[i - 1]))) {
          return line.slice(0, i);
        }
      }
      return line;
    })
    .join('\n');

  if (!code.includes('flyway_schema_history')) return null;
  if (!/version\s+FROM\s+flyway_schema_history/i.test(code)) return null;
  // The query exists; does it get compared against a bare integer?
  const comparison = code.match(
    /"?\$\{?facts\}?"?\s*(?:==|!=)\s*"?(\d+)\|/,
  );
  if (!comparison) return null;
  const line = code.slice(0, code.indexOf(comparison[0])).split('\n').length;
  return {
    gate: '',
    detail:
      `it asserts the schema version against the literal ${comparison[1]} (line ${line}). `
      + "Compute it from spring-ai-rag-core/src/main/resources/db/migration instead, as "
      + 'verify-collection-provisioning.sh and verify-managed-api-principals.sh already do.',
  };
}

/**
 * @param {string} scriptsDir
 * @returns {Violation[]}
 */
export function collectHardcodedFlywayVersions(scriptsDir) {
  const out = [];
  for (const name of readdirSync(scriptsDir).filter(n => n.endsWith('.sh')).sort()) {
    const found = checkHardcodedFlywayVersion(
      readFileSync(join(scriptsDir, name), 'utf8'));
    if (found) out.push({ ...found, gate: `scripts/${name}` });
  }
  return out;
}

function main() {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  const violations = collectHardcodedFlywayVersions(join(root, 'scripts'));
  if (violations.length > 0) {
    console.error(`Hardcoded Flyway version (${VIOLATION_KIND}):`);
    for (const v of violations) {
      console.error(`  ${v.gate}: ${v.detail}`);
    }
    process.exitCode = 1;
    return;
  }
  console.log(
    'No script asserts the schema version against a literal; every one computes it '
    + 'from the migration directory.',
  );
}

if (process.argv[1] && process.argv[1].endsWith('verify-flyway-version-pinning.mjs')) {
  main();
}
