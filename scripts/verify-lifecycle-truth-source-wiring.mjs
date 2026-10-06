#!/usr/bin/env node
// Does a real-database lifecycle test answer with the definition production uses?
//
// Batch 929. `DocumentLifecycleService` reads from
// `DerivationIntegrityRepository` when it has one and falls back to a hand-rolled
// SQL block in the same class when it does not. The two are not equally strict:
// the fallback compares status, hash, chunker and chunk count, while the
// repository also requires a positive generation, contiguous indexes from zero,
// matching dimensions, and one vector per local chunk at the same text and
// offsets.
//
// `DocumentLifecyclePostgresIntegrationTest` is the only place in the repository
// that asserts the lifecycle contract against a real PostgreSQL instance, and
// both of its assertions were built through the three-argument constructor. So
// the suite was checking the definition production does not use, and a
// regression in the real one could not have failed it. Both verdicts turned out
// to be identical once the repository was attached, which is exactly why
// nothing had ever noticed.
//
// ## What counts
//
// A test source file that does all three of:
//
//   1. constructs `new DocumentLifecycleService(`
//   2. plants rows into `rag_document_embedding_state`,
//      `rag_document_local_index_state` or `rag_document_chunks`
//   3. never mentions `setIntegrityRepository(`
//
// Condition 2 is what separates this from "a unit test of the fallback", which
// is a legitimate thing to test: a mocked `JdbcTemplate` plants no rows, so
// those files are not affected, and the rule says nothing about them.
//
// ## What this guarantees, and what it does not
//
// It guarantees: no test file constructs the service over planted rows without
// the file naming an attachment at all.
//
// It does **not** guarantee that the code path which reads the verdict goes
// through the attachment. Two shapes defeat it, both found by the reverse
// control rather than by reasoning:
//
//   1. The attachment is aimed at a different collaborator. The name is matched
//      anywhere in the file, so `persistence.setIntegrityRepository(...)`
//      counts. `NextHighValueFeaturesPostgresIntegrationTest` does exactly that
//      — and never constructs the service, so it is clean by condition 1 — but
//      a file doing both would slip through.
//   2. The attachment sits in a helper nothing calls. This one bit during
//      Batch 929: reverting the two assertions to the three-argument
//      constructor left `lifecycleWithProductionTruthSource` in the file, dead,
//      and the rule stayed green. Reverting a fix and re-running the gate is
//      the only thing that found it; the self-test had a fixture of the same
//      shape and read as a pass.
//
// Deciding either from text would mean resolving which method a call lives in
// and whether anything calls that method, which is a compiler's job. The
// alternative — no rule — is what produced Batch 929. Both limits are pinned by
// the self-test so they stay known ones.

import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

import { stripJavaComments } from './lib/java-source.mjs';

export const VIOLATION_KIND = 'lifecycle-truth-source-unwired';

export const LIFECYCLE_SERVICE = 'DocumentLifecycleService';
export const ATTACH_TRUTH_SOURCE = 'setIntegrityRepository';
export const DERIVATION_TABLES = [
  'rag_document_embedding_state',
  'rag_document_local_index_state',
  'rag_document_chunks',
];

/**
 * @typedef {object} Violation
 * @property {string} gate   the file's path relative to the repository root
 * @property {string} detail why its assertions cannot see the production definition
 */

/**
 * The pure half: everything it needs arrives as text, so the self-test can drive
 * it with fixtures instead of a real repository.
 *
 * @param {string} source Java source, comments and all
 * @returns {{ constructs: boolean, plantsRows: boolean, attached: boolean, tables: string[] }}
 */
export function inspectJavaSource(source) {
  const code = stripJavaComments(source);
  const constructs = code.includes(`new ${LIFECYCLE_SERVICE}(`);
  const attached = code.includes(ATTACH_TRUTH_SOURCE);
  const tables = DERIVATION_TABLES.filter((table) =>
    new RegExp(`INSERT\\s+INTO\\s+${table}\\b`, 'i').test(code));
  return { constructs, plantsRows: tables.length > 0, attached, tables };
}

/**
 * @param {string} source Java source
 * @param {string} gate the reporting path
 * @returns {Violation|null}
 */
export function checkTruthSourceWiring(source, gate) {
  const { constructs, plantsRows, attached, tables } = inspectJavaSource(source);
  if (!constructs || !plantsRows || attached) return null;
  return {
    gate,
    detail:
      `it builds ${LIFECYCLE_SERVICE} without a DerivationIntegrityRepository and `
      + `plants rows into ${tables.join(', ')}, so every lifecycle verdict it `
      + 'asserts comes from the fallback SQL rather than the definition Spring '
      + 'wires in production. Attach the truth source with setIntegrityRepository, '
      + 'or drop the real-database inserts and keep the test a unit test of the '
      + 'fallback.',
  };
}

function testSources(repoRoot) {
  const out = [];
  for (const module of ['spring-ai-rag-api', 'spring-ai-rag-core', 'spring-ai-rag-documents', 'spring-ai-rag-starter']) {
    const root = join(repoRoot, module, 'src', 'test', 'java');
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
          walk(full);
        } else if (entry.name.endsWith('.java')) {
          out.push(full);
        }
      }
    };
    walk(root);
  }
  return out;
}

/**
 * @param {string} repoRoot
 * @returns {Violation[]}
 */
export function collectTruthSourceWiring(repoRoot) {
  const out = [];
  for (const file of testSources(repoRoot)) {
    const found = checkTruthSourceWiring(readFileSync(file, 'utf8'), relative(repoRoot, file));
    if (found) out.push(found);
  }
  return out;
}

function main() {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  const violations = collectTruthSourceWiring(root);
  if (violations.length > 0) {
    console.error(`Lifecycle truth source left unwired (${VIOLATION_KIND}):`);
    for (const v of violations) {
      console.error(`  ${v.gate}: ${v.detail}`);
    }
    process.exitCode = 1;
    return;
  }
  console.log(
    'Every real-database test that asserts a lifecycle verdict attaches '
    + 'DerivationIntegrityRepository, so it answers with the definition production uses.',
  );
}

if (process.argv[1] && process.argv[1].endsWith('verify-lifecycle-truth-source-wiring.mjs')) {
  main();
}
