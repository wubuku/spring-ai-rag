#!/usr/bin/env node
// Self-test for scripts/verify-surefire-method-selection.mjs.
//
// Batch 925. The positive case is the **pre-fix text** of the one script in the
// repository that selects surefire tests by method name, and that is the point:
// a rule never shown the instance it exists for is not evidence of anything.
//
// The negative cases carry the weight that keeps this rule from becoming a
// blanket. Twenty scripts pass `-Dsurefire.failIfNoSpecifiedTests=false` and
// none of them is a violation, because they select whole classes — a missing
// class leaves no report, and each of them already checks for the report. Only a
// selector containing `#` is the shape surefire answers with silence.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  checkMethodSelection,
  collectMethodSelections,
  METHOD_SELECTION_SOURCE,
} from '../verify-surefire-method-selection.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
let failures = 0;

function expectFlagged(label, source) {
  const found = checkMethodSelection(source);
  if (found === null) {
    console.error(`not ok - ${label}: expected a violation, got none`);
    failures += 1;
    return;
  }
  console.log(`ok   - ${label}`);
}

function expectAccepted(label, source) {
  const found = checkMethodSelection(source);
  if (found !== null) {
    console.error(`not ok - ${label}: unexpected violation: ${found.detail}`);
    failures += 1;
    return;
  }
  console.log(`ok   - ${label}`);
}

// ── the pre-fix script, verbatim in the part that matters ─────────────────────

expectFlagged(
  'the pre-fix next-high-value-feature shape, with the stale method name',
  `
  POSTGRES_METHODS="migrationsCreateDurableControlPlanesFromEmptyDatabase+strictIntegrityRejectsSameCountButMismatchedVectorContent"
  local selector="NextHighValueFeaturesPostgresIntegrationTest#\${POSTGRES_METHODS}"
  mvn -pl spring-ai-rag-core -am \\
    -Dnext-high-value.it.enabled=true \\
    "-Dtest=\${selector}" \\
    -Dsurefire.failIfNoSpecifiedTests=false test
`,
);

expectFlagged('a method selector written out in full', `
  mvn -pl spring-ai-rag-core -am \\
    "-Dtest=SomePostgresIntegrationTest#alpha+beta" \\
    -Dsurefire.failIfNoSpecifiedTests=false test
`);

// ── shapes that must not be reported ─────────────────────────────────────────

// The corrected script, which sources the library.
expectAccepted('the corrected script, which sources the library', `
  source scripts/lib/surefire-method-selection.sh
  local selector="NextHighValueFeaturesPostgresIntegrationTest#\${POSTGRES_METHODS}"
  assert_surefire_methods_exist "$source_file" "$POSTGRES_METHODS" || return 1
`);

// The shape twenty other scripts use. A missing class leaves no report, and
// each of them already checks for the report — so `failIfNoSpecifiedTests=false`
// is the right call there and this rule must not object to it.
expectAccepted('a class-only selector with failIfNoSpecifiedTests=false', `
  mvn -pl spring-ai-rag-core -am \\
    -Dcollection-provisioning.it.enabled=true \\
    "-Dtest=CollectionProvisioningPostgresIntegrationTest" \\
    -Dsurefire.failIfNoSpecifiedTests=false test
  [[ -f "$report" ]] || { echo "Missing PostgreSQL acceptance report" >&2; return 1; }
`);

expectAccepted('a comma-separated list of test classes', `
  "-Dtest=DerivationRepairControllerWebTest,EmbeddingJobControllerWebTest" \\
    -Dsurefire.failIfNoSpecifiedTests=false test
`);

expectAccepted('a Playwright selector, which is a different runner entirely', `
  npx playwright test e2e/api-key-real.spec.ts --project=chromium
`);

expectAccepted('this gate\'s own header quotes the broken selector', `
  #   local selector="NextHighValueFeaturesPostgresIntegrationTest#\${POSTGRES_METHODS}"
  local selector="NextHighValueFeaturesPostgresIntegrationTest"
`);

expectAccepted('a script that never invokes Maven', `
  echo "run the acceptance suite by hand"
`);

// ── the real repository ──────────────────────────────────────────────────────

const violations = collectMethodSelections(join(root, 'scripts'), root);
if (violations.length > 0) {
  console.error('not ok - the real scripts directory is clean:');
  for (const v of violations) console.error(`    ${v.gate}: ${v.detail}`);
  failures += 1;
} else {
  console.log('ok   - the real scripts directory reports nothing');
}

// The message names a file and a function. Both have to be there, or the rule
// sends a reader somewhere that cannot help them.
const library = readFileSync(join(root, METHOD_SELECTION_SOURCE), 'utf8');
if (/^assert_surefire_methods_exist\(\) \{/m.test(library)) {
  console.log(`ok   - ${METHOD_SELECTION_SOURCE} defines assert_surefire_methods_exist`);
} else {
  console.error(`not ok - ${METHOD_SELECTION_SOURCE} does not define assert_surefire_methods_exist`);
  failures += 1;
}

if (failures > 0) {
  console.error(`\n${failures} case(s) failed.`);
  process.exit(1);
}
console.log('\nAll surefire method-selection self-test cases passed.');
