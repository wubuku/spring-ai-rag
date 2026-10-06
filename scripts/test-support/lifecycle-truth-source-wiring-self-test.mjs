#!/usr/bin/env node
// Self-test for scripts/verify-lifecycle-truth-source-wiring.mjs.
//
// Batch 929. The positive case is the **pre-fix text** of the only file in the
// repository that asserts the lifecycle contract against a real database, with
// the two constructions that answered through the fallback SQL and the inserts
// that gave them something to be wrong about.
//
// The negative cases carry the weight that keeps this from becoming a blanket.
// Three other test classes construct `DocumentLifecycleService` with three
// arguments and none of them is a violation, because they mock `JdbcTemplate`
// and plant no rows: testing the fallback is a legitimate thing to test, and the
// rule is about tests whose verdicts depend on real SQL.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  checkTruthSourceWiring,
  collectTruthSourceWiring,
  DERIVATION_TABLES,
  LIFECYCLE_SERVICE,
  VIOLATION_KIND,
} from '../verify-lifecycle-truth-source-wiring.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
let failures = 0;

function expectFlagged(label, source, needle) {
  const found = checkTruthSourceWiring(source, 'fixture');
  if (found === null) {
    console.error(`not ok - ${label}: expected a violation, got none`);
    failures += 1;
    return;
  }
  if (needle && !found.detail.includes(needle)) {
    console.error(`not ok - ${label}: the finding does not mention "${needle}"`);
    failures += 1;
    return;
  }
  console.log(`ok   - ${label}`);
}

function expectAccepted(label, source) {
  const found = checkTruthSourceWiring(source, 'fixture');
  if (found !== null) {
    console.error(`not ok - ${label}: unexpected violation: ${found.detail}`);
    failures += 1;
    return;
  }
  console.log(`ok   - ${label}`);
}

// ── the pre-fix file, in the two places that mattered ────────────────────────

expectFlagged(
  'the pre-fix keyword-only assertion: three-arg construction over planted rows',
  `
    @Test
    void keywordOnlyLifecycleAndRealFulltextUseLocalChunksWithoutVectors() {
        long profileId = insertProfile("keyword-only");
        keywordIndexService.ensureCurrent(document);
        jdbcTemplate.update("""
                INSERT INTO \${TABLE} (
                    document_id, embedding_profile_id, content_hash,
                    chunker_version, status, chunk_count
                ) VALUES (?, ?, ?, ?, 'FAILED', 1, 'provider unavailable')
                """, documentId, profileId, HASH_A, TEXT_CHUNKER);

        var status = new \${SERVICE}(
                jdbcTemplate, profileProvider, descriptorProvider).read(document);
        assertEquals("KEYWORD_ONLY", status.searchability());
    }
`.replaceAll('${TABLE}', 'rag_document_embedding_state')
  .replaceAll('${SERVICE}', LIFECYCLE_SERVICE),
  'fallback SQL',
);

expectFlagged(
  'the pre-fix missing-local-chunks assertion, planting a local index row',
  `
    @Test
    void lifecycleDoesNotReportKeywordReadyWhenLocalChunksAreMissing() {
        jdbcTemplate.update("""
                INSERT INTO \${TABLE} (
                    document_id, local_index_status, content_hash
                ) VALUES (?, 'READY', ?)
                """, documentId, HASH_A);
        DocumentLifecycleService lifecycle = new DocumentLifecycleService(
                jdbcTemplate, profileProvider, descriptorProvider);
        assertEquals("FAILED", lifecycle.read(document).searchability());
    }
`.replaceAll('${TABLE}', 'rag_document_local_index_state'),
  LIFECYCLE_SERVICE,
);

// ── the shapes that must not be reported ─────────────────────────────────────

// The corrected file: same inserts, same construction shape, truth source on.
expectAccepted('the corrected file, which attaches the truth source', `
    @Test
    void keywordOnlyLifecycleAndRealFulltextUseLocalChunksWithoutVectors() {
        jdbcTemplate.update("""
                INSERT INTO rag_document_embedding_state (
                    document_id, embedding_profile_id, content_hash,
                    chunker_version, status, chunk_count, request_generation
                ) VALUES (?, ?, ?, ?, 'FAILED', 1, 1, 'provider unavailable')
                """, documentId, profileId, HASH_A, TEXT_CHUNKER);

        DocumentLifecycleService lifecycle = lifecycleWithProductionTruthSource(
                profileProvider);
        assertEquals("KEYWORD_ONLY", lifecycle.read(document).searchability());
    }

    private DocumentLifecycleService lifecycleWithProductionTruthSource(
            EmbeddingProfileProvider profileProvider) {
        DocumentLifecycleService lifecycle = new DocumentLifecycleService(
                jdbcTemplate, profileProvider, descriptorProvider);
        lifecycle.setIntegrityRepository(new DerivationIntegrityRepository(
                jdbcTemplate, profileProvider, descriptorProvider));
        return lifecycle;
    }
`);

// The three mock-based unit classes: three-arg construction, no planted rows.
expectAccepted('a unit test of the fallback, with no rows to be wrong about', `
    @Test
    void derivesFailedWhenLocalIsReadyButChunksAreMissing() {
        when(jdbcTemplate.queryForList(anyString(), eq(7L), eq(9L)))
                .thenReturn(List.of(stateRow("READY", 3)));
        service = new DocumentLifecycleService(jdbcTemplate, profileProvider, descriptors);
        assertEquals("FAILED", service.read(document).localIndexStatus());
    }
`);

// Plants rows, never builds the lifecycle service.
expectAccepted('a suite that plants derivation rows for a different subject', `
    @Test
    void repairPreviewBoundsItsResultSet() {
        jdbcTemplate.update("""
                INSERT INTO rag_document_embedding_state (
                    document_id, embedding_profile_id, content_hash
                ) VALUES (?, ?, ?)
                """, documentId, profileId, HASH_A);
        assertEquals(1, service.preview(COLLECTION_ID).items().size());
    }
`);

// The whole pre-fix shape quoted in a Javadoc. stripJavaComments has to reach
// this, or every file explaining the defect would trip the rule.
expectAccepted('the pre-fix shape quoted inside a Javadoc', `
    /**
     * <p>Both assertions were built through the three-argument constructor:
     * <pre>
     * new \${SERVICE}(jdbcTemplate, profileProvider, descriptorProvider)
     * jdbcTemplate.update("INSERT INTO rag_document_embedding_state (...")
     * </pre>
     */
    void example() {}
`.replaceAll('${SERVICE}', LIFECYCLE_SERVICE));

// ── the real repository ──────────────────────────────────────────────────────

const violations = collectTruthSourceWiring(root);
if (violations.length > 0) {
  console.error(`not ok - the real test tree is clean (${VIOLATION_KIND}):`);
  for (const v of violations) console.error(`    ${v.gate}: ${v.detail}`);
  failures += 1;
} else {
  console.log('ok   - the real test tree reports nothing');
}

// The real file must still be doing what the fix intends: planting rows *and*
// constructing the service *and* attaching the repository. A gate that passes
// because the file stopped planting rows would be green for the wrong reason.
const realLifecycle = readFileSync(
  join(root, 'spring-ai-rag-core/src/test/java/com/springairag/core/integration/DocumentLifecyclePostgresIntegrationTest.java'),
  'utf8');
for (const [label, needle] of [
  ['constructs the service', `new ${LIFECYCLE_SERVICE}(`],
  ['attaches the truth source', 'setIntegrityRepository'],
  ...DERIVATION_TABLES.map((t) => [`plants ${t}`, `INSERT INTO ${t}`]),
]) {
  if (realLifecycle.includes(needle)) {
    console.log(`ok   - the real lifecycle suite ${label}`);
  } else {
    console.error(`not ok - the real lifecycle suite does not ${label}`);
    failures += 1;
  }
}

// The stated limits. The attachment is recognised by name, wherever it is aimed,
// and whether anything calls the method that performs it is not decidable from
// the text. Saying so in a comment is not the same as knowing it, so both are
// pinned here — and the second one is the shape that actually fooled this rule
// during Batch 929.
const aimedElsewhere = `
    @Test
    void verdict() {
        jdbcTemplate.update("INSERT INTO rag_document_embedding_state (document_id) VALUES (1)");
        DocumentLifecycleService lifecycle = new DocumentLifecycleService(
                jdbcTemplate, profileProvider, descriptorProvider);
        persistence.setIntegrityRepository(new DerivationIntegrityRepository(
                jdbcTemplate, profileProvider, descriptorProvider));
        assertEquals("READY", lifecycle.read(document).searchability());
    }
`;
if (checkTruthSourceWiring(aimedElsewhere, 'fixture') === null) {
  console.log('ok   - stated limit: an attachment aimed at another collaborator is not reported');
} else {
  console.error('not ok - the gate now catches the first stated limit, so the header is wrong about it');
  failures += 1;
}

const deadHelper = `
    @Test
    void revertedToTheFallback() {
        jdbcTemplate.update("INSERT INTO rag_document_embedding_state (document_id) VALUES (1)");
        DocumentLifecycleService lifecycle = new DocumentLifecycleService(
                jdbcTemplate, profileProvider, descriptorProvider);
        assertEquals("READY", lifecycle.read(document).searchability());
    }

    private DocumentLifecycleService lifecycleWithProductionTruthSource(
            EmbeddingProfileProvider profileProvider) {
        DocumentLifecycleService lifecycle = new DocumentLifecycleService(
                jdbcTemplate, profileProvider, descriptorProvider);
        lifecycle.setIntegrityRepository(new DerivationIntegrityRepository(
                jdbcTemplate, profileProvider, descriptorProvider));
        return lifecycle;
    }
`;
if (checkTruthSourceWiring(deadHelper, 'fixture') === null) {
  console.log('ok   - stated limit: an attachment in a helper nothing calls is not reported');
} else {
  console.error('not ok - the gate now catches the second stated limit, so the header is wrong about it');
  failures += 1;
}

// The gate must therefore not be advertised as more than it is. Its success
// message says what it checked; if it started claiming the verdict comes from
// production's definition, the message would be asserting something the rule
// cannot see. The fragment is deliberately short: the finding text is assembled
// by string concatenation, so a longer phrase would not be contiguous in the
// source and this check would fail for a reason that has nothing to do with the
// property being pinned.
const gateSource = readFileSync(
  join(root, 'scripts', 'verify-lifecycle-truth-source-wiring.mjs'), 'utf8');
if (gateSource.includes('asserts comes from the fallback SQL')) {
  console.log('ok   - the finding names which definition the assertions came from');
} else {
  console.error('not ok - the finding no longer says what the assertions were answered by');
  failures += 1;
}

if (failures > 0) {
  console.error(`\n${failures} case(s) failed.`);
  process.exit(1);
}
console.log('\nAll lifecycle truth-source wiring self-test cases passed.');
