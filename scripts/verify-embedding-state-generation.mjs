#!/usr/bin/env node
// Does every production writer of rag_document_embedding_state name request_generation?
//
// Batch 928. `DerivationIntegrityRepository` will not call a vector fresh unless
// `vector_generation > 0`, and that generation is the fence tying a state row to
// the job that produced it — every job-side update is guarded by
// `state.request_generation = job.request_generation`.
//
// Three production writers wrote the state row without naming the column:
//
//   EmbeddingPersistenceService.replace                   (INSERT, status COMPLETED)
//   EmbeddingPersistenceService.recordFailureIfNoCompleted (INSERT, status FAILED)
//   LegacyEmbeddingMigrationService                        (INSERT, status COMPLETED)
//
// On the update branch that is invisible: an existing row keeps the generation
// its job allocated. On the insert branch the row takes the column default, and
// until V60 that default was 0. So a document whose vectors had just been
// committed — status COMPLETED, content hash matching, chunker matching, chunk
// count declared and vector present — was classified `CORRUPT`, and the public
// lifecycle reported it as `embeddingStatus=FAILED` with a null error.
//
// Measured on a live PostgreSQL instance: 69 of 82 rows sat at generation 0.
// `run-retrieval-regression.sh` aborted on its third fixture with
// `status=FAILED error=None`, and a fixture document with a valid 1024-dimension
// vector had failed for a reason that existed nowhere in the database.
//
// ## What counts
//
//   - An `INSERT INTO rag_document_embedding_state` in `src/main/java` whose
//     column list does not name `request_generation`. The Java source is read
//     with comments stripped, and string literals are left intact, because the
//     SQL lives inside them and is assembled across lines by `+`.
//   - An assignment of a bare numeric literal to `request_generation`. The
//     correct values are a bind parameter or `... + 1`; a literal is either the
//     sentinel this gate exists to prevent or a number that will drift.
//   - No migration declaring `CHECK (request_generation > 0)`. The writers being
//     correct and the schema being unable to say so are two different failures,
//     and only the second one is permanent.
//
// ## What deliberately does not
//
//   - The test tree. Two fixtures there omit the column on purpose: one seeds the
//     shape V42-era code wrote, and one asserts that the new default is safe.
//     Since V60 the default is 1, so a test fixture that forgets the column
//     cannot produce a row the integrity repository calls corrupt — the failure
//     this gate prevents is only reachable from production code.
//   - `rag_document_local_index_state.local_index_generation`, which looks like
//     the same column and is not. `KeywordIndexPersistenceService` inserts 0 there
//     on its failure path, on purpose, and tightening it would break embedding.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

import { stripJavaComments } from './lib/java-source.mjs';

export const VIOLATION_KIND = 'unchecked-embedding-state-generation';

export const STATE_TABLE = 'rag_document_embedding_state';
export const GENERATION_COLUMN = 'request_generation';
export const MIGRATION_DIR = 'spring-ai-rag-core/src/main/resources/db/migration';
export const REQUIRED_CONSTRAINT =
  'CHECK (request_generation > 0)';

/**
 * `INSERT INTO <table> ( ... )`.
 *
 * The gap between the table name and the opening parenthesis is the part worth
 * being careful about. On disk the statement is not one string: it is a `+`
 * chain, so the text between `rag_document_embedding_state` and `(` is
 *
 *     rag_document_embedding_state "⏎        + "(
 *
 * — a closing quote, a newline, indentation, a plus and an opening quote. A
 * pattern that allows only whitespace here matches nothing in this repository,
 * which is the same mistake this rule was written after: a detector that has
 * never been pointed at the real text reports a clean tree either way, and the
 * clean tree and the broken detector look identical. Hence `[\s"'+]*` rather
 * than `\s*`.
 *
 * The column list itself cannot contain a closing parenthesis, so `[^)]*` is
 * the whole job.
 */
const INSERT_INTO_STATE =
  new RegExp(`INSERT\\s+INTO\\s+${STATE_TABLE}[\\s"'+]*\\(([^)]*)\\)`, 'gi');

/**
 * `request_generation = <number>`, in a SET list or an ON CONFLICT clause.
 *
 * No trailing guard. The first version of this pattern ended in
 * `(?!\s*[),;])`, on the theory that a literal followed by a comma was part of
 * some other expression. That theory excluded the only shape that occurs here —
 * `SET request_generation = 0, updated_at = NOW()` — so the rule did not fire
 * on the statement it was written for. The self-test's positive control is what
 * caught it, which is the argument for keeping those fixtures at all.
 */
const LITERAL_ASSIGNMENT =
  new RegExp(`${GENERATION_COLUMN}\\s*=\\s*(\\d+)\\b`, 'gi');

/**
 * @typedef {object} Violation
 * @property {string} gate   the file's path relative to the repository root
 * @property {string} detail what the statement does that leaves generation 0 reachable
 */

/**
 * The pure half: everything it needs arrives as text, so the self-test can drive
 * it with fixtures instead of a real repository.
 *
 * @param {string} source Java source, comments and all
 * @returns {{missingColumn: boolean, literalAssignments: string[]}}
 */
export function inspectJavaSource(source) {
  const code = stripJavaComments(source);
  let missingColumn = false;
  for (const match of code.matchAll(INSERT_INTO_STATE)) {
    const columns = match[1].split(',').map((c) => c.trim());
    if (!columns.includes(GENERATION_COLUMN)) {
      missingColumn = true;
      break;
    }
  }
  const literalAssignments = [...code.matchAll(LITERAL_ASSIGNMENT)]
    .map((m) => m[1]);
  return { missingColumn, literalAssignments };
}

/**
 * @param {string} source Java source
 * @param {string} gate the reporting path
 * @returns {Violation[]}
 */
export function checkJavaSource(source, gate) {
  const { missingColumn, literalAssignments } = inspectJavaSource(source);
  const out = [];
  if (missingColumn) {
    out.push({
      gate,
      detail:
        `it inserts into ${STATE_TABLE} without naming ${GENERATION_COLUMN}, so a new `
        + 'row takes the column default. DerivationIntegrityRepository requires a '
        + 'positive generation before it will call a vector fresh, and until V60 '
        + 'that default was 0 — which made a correctly embedded document read as '
        + 'embeddingStatus=FAILED with a null error. Name the column and write 1; '
        + 'do not increment it on the conflict branch, because the generation is '
        + 'the job fence.',
    });
  }
  for (const literal of literalAssignments) {
    out.push({
      gate,
      detail:
        `it assigns ${GENERATION_COLUMN} = ${literal}. The generation is either a `
        + 'bind parameter or the previous value plus one; a literal is the '
        + 'sentinel this gate exists to keep out of the table.',
    });
  }
  return out;
}

/**
 * SQL comments, so a migration that only *mentions* the constraint in prose is
 * not read as declaring it. `--` is the comment syntax these migrations use, and
 * `/* ... *\/` is stripped as well because a block comment must not be able to
 * hide a live statement from this scan.
 */
export function stripSqlComments(sql) {
  return sql
    .replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, ' '))
    .replace(/--[^\n]*/g, (line) => line.replace(/[^\n]/g, ' '));
}

/**
 * @param {string} sql migration file contents
 * @returns {boolean} whether it declares the positive-generation constraint
 */
export function declaresGenerationConstraint(sql) {
  return new RegExp(
    `CHECK\\s*\\(\\s*${GENERATION_COLUMN}\\s*>\\s*0\\s*\\)`,
    'i',
  ).test(stripSqlComments(sql));
}

function javaSources(root) {
  const out = [];
  const walk = (dir) => {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name !== 'target' && entry.name !== 'node_modules') walk(full);
      } else if (entry.name.endsWith('.java')) {
        out.push(full);
      }
    }
  };
  for (const module of ['spring-ai-rag-api', 'spring-ai-rag-core', 'spring-ai-rag-documents', 'spring-ai-rag-starter']) {
    walk(join(root, module, 'src', 'main', 'java'));
  }
  return out;
}

/**
 * @param {string} repoRoot
 * @returns {Violation[]}
 */
export function collectEmbeddingStateGenerations(repoRoot) {
  const out = [];
  for (const file of javaSources(repoRoot)) {
    const text = readFileSync(file, 'utf8');
    if (!text.includes(STATE_TABLE)) continue;
    out.push(...checkJavaSource(text, relative(repoRoot, file)));
  }

  const migrationDir = join(repoRoot, MIGRATION_DIR);
  let declares = false;
  let sawMigration = false;
  for (const name of readdirSync(migrationDir).sort()) {
    if (!name.endsWith('.sql')) continue;
    sawMigration = true;
    if (declaresGenerationConstraint(readFileSync(join(migrationDir, name), 'utf8'))) {
      declares = true;
    }
  }
  if (sawMigration && !declares) {
    out.push({
      gate: MIGRATION_DIR,
      detail:
        `no migration declares CHECK (${GENERATION_COLUMN} > 0). Fixing the writers `
        + 'is not enough on its own: without the constraint, a future writer that '
        + 'forgets the column stores 0 again and nothing complains.',
    });
  }
  return out;
}

function main() {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  const violations = collectEmbeddingStateGenerations(root);
  if (violations.length > 0) {
    console.error(`Unchecked embedding state generation (${VIOLATION_KIND}):`);
    for (const v of violations) {
      console.error(`  ${v.gate}: ${v.detail}`);
    }
    process.exitCode = 1;
    return;
  }
  console.log(
    'Every production writer of '
    + `${STATE_TABLE} names ${GENERATION_COLUMN}, and a migration declares `
    + `${REQUIRED_CONSTRAINT}.`,
  );
}

if (process.argv[1] && process.argv[1].endsWith('verify-embedding-state-generation.mjs')) {
  main();
}
