#!/usr/bin/env node
// Self-test for scripts/verify-embedding-state-generation.mjs.
//
// Batch 928. The positive cases are the **pre-fix text of the three writers**,
// copied the way it sits on disk — the `+ "..."` concatenation included, because
// that is the shape the rule has to survive. An earlier version of this rule
// allowed only whitespace between the table name and the column list, and
// therefore matched nothing in the real tree: the concatenation puts a closing
// quote, a newline, a plus and an opening quote in that gap. A detector that
// has never seen the real text reports "clean" for the broken detector, so the
// positive control is the point of this file rather than a formality.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  checkJavaSource,
  collectEmbeddingStateGenerations,
  declaresGenerationConstraint,
  GENERATION_COLUMN,
  MIGRATION_DIR,
  STATE_TABLE,
  VIOLATION_KIND,
} from '../verify-embedding-state-generation.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
let failures = 0;

function expectFlagged(label, source, needle) {
  const found = checkJavaSource(source, 'fixture');
  if (found.length === 0) {
    console.error(`not ok - ${label}: expected a violation, got none`);
    failures += 1;
    return;
  }
  if (needle && !found.some((v) => v.detail.includes(needle))) {
    console.error(`not ok - ${label}: no finding mentions "${needle}"`);
    failures += 1;
    return;
  }
  console.log(`ok   - ${label}`);
}

function expectAccepted(label, source) {
  const found = checkJavaSource(source, 'fixture');
  if (found.length > 0) {
    console.error(`not ok - ${label}: unexpected violation: ${found[0].detail}`);
    failures += 1;
    return;
  }
  console.log(`ok   - ${label}`);
}

// ── the three pre-fix writers, verbatim in the part that matters ──────────────

expectFlagged(
  'the pre-fix EmbeddingPersistenceService.replace, COMPLETED branch',
  `
        jdbcTemplate.update(
                "INSERT INTO \${STATE_TABLE} "
                        + "(document_id, embedding_profile_id, content_hash, chunker_version, "
                        + "status, chunk_count, processing_error, completed_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', ?, NULL, NOW(), NOW()) "
                        + "ON CONFLICT (document_id, embedding_profile_id) DO UPDATE SET "
                        + "content_hash = EXCLUDED.content_hash, "
                        + "completed_at = NOW(), updated_at = NOW()",
                documentId);
`.replaceAll('${STATE_TABLE}', STATE_TABLE),
  'without naming',
);

expectFlagged(
  'the pre-fix EmbeddingPersistenceService.recordFailureIfNoCompleted, FAILED branch',
  `
        jdbcTemplate.update(
                "INSERT INTO \${STATE_TABLE} "
                        + "(document_id, embedding_profile_id, content_hash, chunker_version, "
                        + "status, chunk_count, processing_error, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'FAILED', 0, ?, NOW()) "
                        + "ON CONFLICT (document_id, embedding_profile_id) DO UPDATE SET "
                        + "status = 'FAILED'",
                documentId);
`.replaceAll('${STATE_TABLE}', STATE_TABLE),
  'without naming',
);

expectFlagged(
  'the pre-fix LegacyEmbeddingMigrationService adoption insert',
  `
        jdbcTemplate.update(
                "INSERT INTO \${STATE_TABLE} "
                        + "(document_id, embedding_profile_id, content_hash, chunker_version, "
                        + "status, chunk_count, completed_at, updated_at) "
                        + "VALUES (?, ?, ?, 'legacy-adopted-unknown', 'COMPLETED', ?, NOW(), NOW()) "
                        + "ON CONFLICT (document_id, embedding_profile_id) DO UPDATE SET "
                        + "status = 'COMPLETED'",
                documentId);
`.replaceAll('${STATE_TABLE}', STATE_TABLE),
  'without naming',
);

expectFlagged(
  'an insert that names the column but pairs it with a literal assignment elsewhere',
  `
        jdbcTemplate.update(
                "UPDATE \${STATE_TABLE} SET \${GENERATION_COLUMN} = 0, updated_at = NOW() "
                        + "WHERE document_id = ?",
                documentId);
`.replaceAll('${STATE_TABLE}', STATE_TABLE).replaceAll('${GENERATION_COLUMN}', GENERATION_COLUMN),
  'assigns',
);

// ── the shapes that must not be reported ─────────────────────────────────────

expectAccepted('the post-fix replace, which names the column', `
        jdbcTemplate.update(
                "INSERT INTO \${STATE_TABLE} "
                        + "(document_id, embedding_profile_id, content_hash, chunker_version, "
                        + "status, chunk_count, request_generation, processing_error, "
                        + "completed_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', ?, 1, NULL, NOW(), NOW()) "
                        + "ON CONFLICT (document_id, embedding_profile_id) DO UPDATE SET "
                        + "content_hash = EXCLUDED.content_hash",
                documentId);
`.replaceAll('${STATE_TABLE}', STATE_TABLE));

// The form the job path uses, and the one the writers must not drift into: a
// generation that counts. A rule that only looked for `= 0` would let this pass
// unchanged, which is why the assignment rule rejects any literal.
expectAccepted('the generation allocator, which counts rather than assigning', `
        Long generation = jdbcTemplate.queryForObject("""
                INSERT INTO \${STATE_TABLE} (
                    document_id, embedding_profile_id, content_hash,
                    chunker_version, status, chunk_count,
                    request_generation, active_job_id, updated_at
                ) VALUES (?, ?, ?, ?, 'QUEUED', 0, 1, NULL, CURRENT_TIMESTAMP)
                ON CONFLICT (document_id, embedding_profile_id) DO UPDATE SET
                    request_generation =
                        \${TABLE}.\${GENERATION_COLUMN} + 1
                RETURNING request_generation
                """, Long.class);
`.replaceAll('${STATE_TABLE}', STATE_TABLE)
  .replaceAll('${GENERATION_COLUMN}', GENERATION_COLUMN)
  .replaceAll('${TABLE}', STATE_TABLE));

// The same statement quoted inside a Javadoc. stripJavaComments has to reach
// this, or every file that explains the defect would trip the rule.
expectAccepted('the pre-fix statement quoted inside a Javadoc', `
    /**
     * <p>下面写状态行时<b>必须</b>显式给出 {@code \${GENERATION_COLUMN}}。
     *
     * <pre>
     * "INSERT INTO \${STATE_TABLE} "
     *         + "(document_id, embedding_profile_id, content_hash, chunker_version, "
     *         + "status, chunk_count, processing_error, completed_at, updated_at) "
     * </pre>
     */
    void example();
`.replaceAll('${STATE_TABLE}', STATE_TABLE).replaceAll('${GENERATION_COLUMN}', GENERATION_COLUMN));

// A different table with a same-named column. The rule is about this table.
expectAccepted('another table that happens to have a request_generation column', `
        jdbcTemplate.update(
                "INSERT INTO rag_embedding_jobs "
                        + "(id, document_id, embedding_profile_id, request_generation) "
                        + "VALUES (?, ?, ?, 0)",
                jobId);
`);

expectAccepted('a file that never mentions the state table', `
    public void unrelated() {
        jdbcTemplate.update("INSERT INTO rag_documents (title) VALUES (?)", title);
    }
`);

// ── the migration half ───────────────────────────────────────────────────────

if (declaresGenerationConstraint(`
ALTER TABLE \${STATE_TABLE}
    ADD CONSTRAINT ck_\${TABLE}_generation
        CHECK (\${GENERATION_COLUMN} > 0);
`.replaceAll('${STATE_TABLE}', STATE_TABLE)
  .replaceAll('${TABLE}', STATE_TABLE)
  .replaceAll('${GENERATION_COLUMN}', GENERATION_COLUMN))) {
  console.log('ok   - a migration that declares the positive-generation check');
} else {
  console.error('not ok - a migration that declares CHECK (request_generation > 0) was not recognised');
  failures += 1;
}

// Mentioned in prose only. A migration whose comment explains the constraint
// does not enforce it, and reading a comment as a declaration is how a gate
// ends up green on a database that would still accept 0.
if (declaresGenerationConstraint(`
-- the state table should really declare CHECK (\${GENERATION_COLUMN} > 0)
ALTER TABLE \${STATE_TABLE} ADD CONSTRAINT ck_loose CHECK (\${GENERATION_COLUMN} >= 0);
`.replaceAll('${STATE_TABLE}', STATE_TABLE).replaceAll('${GENERATION_COLUMN}', GENERATION_COLUMN))) {
  console.error('not ok - a constraint mentioned only in a comment was read as declared');
  failures += 1;
} else {
  console.log('ok   - a constraint mentioned only in a comment is not a declaration');
}

if (declaresGenerationConstraint(`
/* CHECK (\${GENERATION_COLUMN} > 0) */
ALTER TABLE \${STATE_TABLE} ADD CONSTRAINT ck_loose CHECK (\${GENERATION_COLUMN} >= 0);
`.replaceAll('${STATE_TABLE}', STATE_TABLE).replaceAll('${GENERATION_COLUMN}', GENERATION_COLUMN))) {
  console.error('not ok - a constraint inside a block comment was read as declared');
  failures += 1;
} else {
  console.log('ok   - a constraint inside a block comment is not a declaration');
}

// ── the real repository ──────────────────────────────────────────────────────

const violations = collectEmbeddingStateGenerations(root);
if (violations.length > 0) {
  console.error(`not ok - the real tree is clean (${VIOLATION_KIND}):`);
  for (const v of violations) console.error(`    ${v.gate}: ${v.detail}`);
  failures += 1;
} else {
  console.log('ok   - the real tree reports nothing');
}

// The message names the table and the column, and points at the constraint the
// migration half requires. A rule whose message cannot be acted on sends the
// reader somewhere that will not help them.
const gateSource = readFileSync(
  join(root, 'scripts', 'verify-embedding-state-generation.mjs'), 'utf8');
for (const needle of [STATE_TABLE, GENERATION_COLUMN, 'CHECK (request_generation > 0)']) {
  if (gateSource.includes(needle)) {
    console.log(`ok   - the gate's own text names ${needle}`);
  } else {
    console.error(`not ok - the gate's own text never names ${needle}`);
    failures += 1;
  }
}

// And the migration it depends on is really there, rather than the gate passing
// because it looked in a directory that does not exist.
const migration = readFileSync(
  join(root, MIGRATION_DIR, 'V60__backfill_embedding_state_generation.sql'), 'utf8');
if (declaresGenerationConstraint(migration)) {
  console.log('ok   - V60 declares the positive-generation check');
} else {
  console.error('not ok - V60 does not declare CHECK (request_generation > 0)');
  failures += 1;
}

if (failures > 0) {
  console.error(`\n${failures} case(s) failed.`);
  process.exit(1);
}
console.log('\nAll embedding-state-generation self-test cases passed.');
