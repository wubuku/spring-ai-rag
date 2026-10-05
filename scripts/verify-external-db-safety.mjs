#!/usr/bin/env node
/**
 * Destructive-database safety for integration suites that accept a caller-named
 * database.
 *
 * Several PostgreSQL suites here will run against an external database when one
 * is supplied, and they reset the schema with `flyway.clean()` before doing
 * anything. That is fine for a throwaway container and catastrophic for a
 * developer's local database, so the convention in this repository is that the
 * external path demands an explicit acknowledgement first:
 *
 *     DOCUMENT_SYNC_RUNS_IT_CLEAN_CONFIRM=YES
 *
 * Batch 802 found the one suite that broke the convention. It was worse than
 * merely inconsistent:
 *
 *   - `EmbeddingProfilePostgresIntegrationTest` had no `*.it.enabled` switch and
 *     no run path anywhere in the repository, so its seven tests could not run
 *     in any automated path at all;
 *   - the only way to make it run was to hand it `-Drag.it.jdbc-url`, i.e. to
 *     name a database;
 *   - and it then ran `flyway.clean()` against that database with no
 *     acknowledgement, while the ten sibling suites that accept the same kind
 *     of URL all require one.
 *
 * A mistyped or stale property was enough to drop every object in a schema
 * someone cared about. The rule below is narrow on purpose — it only fires when
 * a class can both name an external database and destroy a schema, because a
 * gate that fires on the twenty Testcontainers-only suites would be noise, and a
 * gate that cries wolf gets ignored.
 *
 * Run:
 *   node scripts/verify-external-db-safety.mjs
 */

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { stripJavaComments } from './lib/java-source.mjs';
import { fileURLToPath } from 'node:url';
import { isMainModule } from './lib/is-main-module.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const TEST_ROOT = join(projectRoot, 'spring-ai-rag-core', 'src', 'test', 'java');

/**
 * Ways a suite learns which database to use.
 *
 * The env-var form (`DOCUMENT_SYNC_RUNS_IT_JDBC_URL`) is the current
 * convention; `-Drag.it.jdbc-url` is the older one that only the orphaned suite
 * still used. Both are matched because a gate that only knows the new spelling
 * would have passed the very defect it was written for.
 */
const EXTERNAL_DATASOURCE = [
  /_IT_JDBC_URL/,
  /rag\.it\.jdbc-url/,
];

/**
 * Operations that destroy a schema's contents. `flyway.clean()` is the one
 * that actually drops objects; the others are listed because a suite that
 * truncates somebody else's tables has the same consequence, and the guard
 * costs one line to apply.
 */
const DESTRUCTIVE = [
  /\.clean\(\)/,
  /\.drop\(/i,
  /TRUNCATE\s+(TABLE\s+)?/i,
];

/** The acknowledgement every external path must demand. */
const CONFIRM = /_IT_CLEAN_CONFIRM/;

export const VIOLATION_KINDS = Object.freeze({
  UNGUARDED_DESTRUCTIVE: 'unguarded-destructive-operation',
  GUARD_TOO_LATE: 'guard-after-destructive-operation',
});

/** Strips comments so a name in prose cannot satisfy, or trip, a rule. */


/**
 * @returns {{kind: string, detail: string}[]} empty when the suite is safe.
 */
export function checkSuite(className, source) {
  const code = stripJavaComments(source);
  const violations = [];

  const namesDatabase = EXTERNAL_DATASOURCE.some((re) => re.test(code));
  if (!namesDatabase) return violations;

  const destructiveAt = DESTRUCTIVE
    .map((re) => {
      const match = re.exec(code);
      return match ? { index: match.index, text: match[0].trim() } : null;
    })
    .filter(Boolean)
    .sort((a, b) => a.index - b.index)[0];

  if (!destructiveAt) return violations;

  const confirmAt = CONFIRM.exec(code);

  if (!confirmAt) {
    violations.push({
      kind: VIOLATION_KINDS.UNGUARDED_DESTRUCTIVE,
      detail:
        `${className} takes a database from the caller and then calls ${destructiveAt.text}, ` +
        'but never requires a *_IT_CLEAN_CONFIRM acknowledgement. A mistyped URL would drop ' +
        "somebody else's schema.",
    });
    return violations;
  }

  // Present but too late to help: the guard has to run before anything is
  // destroyed, otherwise a run that fails partway has already done the damage.
  if (confirmAt.index > destructiveAt.index) {
    violations.push({
      kind: VIOLATION_KINDS.GUARD_TOO_LATE,
      detail:
        `${className} checks *_IT_CLEAN_CONFIRM at offset ${confirmAt.index} but calls ` +
        `${destructiveAt.text} at offset ${destructiveAt.index}; the acknowledgement has to come first.`,
    });
  }

  return violations;
}

function javaFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) out.push(...javaFiles(path));
    else if (entry.endsWith('.java')) out.push(path);
  }
  return out;
}

export function collectSuites(root = TEST_ROOT) {
  if (!existsSync(root)) return [];
  return javaFiles(root)
    .map((path) => ({ name: path.slice(path.lastIndexOf(sep) + 1), path }))
    .map(({ name, path }) => ({ name, source: readFileSync(path, 'utf8') }));
}

function main() {
  const suites = collectSuites();
  const violations = suites.flatMap((s) => checkSuite(s.name, s.source));

  if (violations.length > 0) {
    console.error('External-database safety violations:');
    for (const v of violations) console.error(`- [${v.kind}] ${v.detail}`);
    console.error(
      '\nA suite that is handed a database and then calls flyway.clean() must first\n' +
        'require <PREFIX>_IT_CLEAN_CONFIRM=YES, exactly as the other suites that accept\n' +
        'an external JDBC URL already do.',
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `External-database safety passed; ${suites.length} test source(s) checked, and every suite ` +
      'that names a caller-supplied database requires an explicit clean confirmation before ' +
      'destroying a schema.',
  );
}

if (isMainModule(import.meta.url)) {
  main();
}
