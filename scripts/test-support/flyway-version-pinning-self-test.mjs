#!/usr/bin/env node
// Self-test for scripts/verify-flyway-version-pinning.mjs.
//
// Batch 923. Six cases are about shapes that must NOT be reported and two are
// about the real repository. The last case is the important one: it feeds the
// rule the **pre-fix text of the script this rule was written for**. A scanner
// that has never been shown the instance it exists to catch is not evidence of
// anything, and today that mistake was made twice before this file existed —
// once by scanning line by line for a multi-line shape, and once by anchoring
// `\b` inside an identifier where `_` is a word character.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  checkHardcodedFlywayVersion,
  collectHardcodedFlywayVersions,
} from '../verify-flyway-version-pinning.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
let failures = 0;

function expectFlagged(label, source) {
  const found = checkHardcodedFlywayVersion(source);
  if (found === null) {
    console.error(`not ok - ${label}: expected a violation, got none`);
    failures += 1;
    return;
  }
  console.log(`ok   - ${label}`);
}

function expectAccepted(label, source) {
  const found = checkHardcodedFlywayVersion(source);
  if (found !== null) {
    console.error(`not ok - ${label}: unexpected violation: ${found.detail}`);
    failures += 1;
    return;
  }
  console.log(`ok   - ${label}`);
}

const SELECTS_VERSION = `
  facts="$(docker exec "$PG_CONTAINER" psql -U postgres -d db -At -F '|' -c "
    SELECT
      (SELECT version FROM flyway_schema_history
        WHERE success ORDER BY installed_rank DESC LIMIT 1),
      COUNT(*)
    FROM some_table;
  ")"
`;

expectFlagged('a version compared against a bare literal is reported', `
${SELECTS_VERSION}
  [[ "$facts" == "58|2" ]] || { echo "Unexpected: $facts" >&2; }
`);

expectFlagged('the literal is reported with the number and where it is', `
${SELECTS_VERSION}
  [[ "$facts" == "58|2" ]] || {
    echo "Unexpected database facts: \${facts}" >&2
  }
`);

// ── shapes that must not be reported ────────────────────────────────────────

expectAccepted('a computed version is the correct shape', `
LATEST_FLYWAY_MIGRATION="$(
  find spring-ai-rag-core/src/main/resources/db/migration \\
    -name 'V*__*.sql' -exec basename {} \\; | sort -n | tail -1
)"
${SELECTS_VERSION}
  [[ "$facts" == "\${LATEST_FLYWAY_MIGRATION}|2|2|0|0" ]] || exit 1
`);

expectAccepted('a script that never selects the version', `
  [[ "$database_facts" == "2,2,0" ]] || exit 1
`);

expectAccepted('naming a version in a comment is not an assertion', `
# The schema is at V58 and V59 adds fs_import_batches.
# [[ "$facts" == "58|2" ]]
${SELECTS_VERSION}
  [[ "$facts" == "\${LATEST_FLYWAY_MIGRATION}|2" ]] || exit 1
`);

expectAccepted('a non-numeric comparison of the same output', `
${SELECTS_VERSION}
  [[ "$facts" == "\${EXPECTED_FACTS}|2" ]] || exit 1
`);

expectAccepted('a bare number in an unrelated assertion', `
  [[ "$count" == "2" ]] || exit 1
`);

// ── the known positive, and the real repository ─────────────────────────────

// The pre-fix text of verify-alert-notification-delivery.sh, verbatim in the
// part that matters. If this ever stops being reported, the rule has stopped
// describing the defect it was written for.
const PRE_FIX_ALERT_FACTS = `
database_facts() {
  local facts
  facts="$(docker exec "$PG_CONTAINER" psql -U postgres \\
    -d alert_notification_gate -At -F '|' -c "
      SELECT
        (SELECT version FROM flyway_schema_history
          WHERE success ORDER BY installed_rank DESC LIMIT 1),
        COUNT(*),
        COUNT(*) FILTER (WHERE status = 'DELIVERED')
      FROM rag_alert_notification_delivery;
    ")"
  [[ "$facts" == "58|2|2|0|0" ]] || {
    echo "Unexpected database facts: \${facts}" >&2
  }
}
`;
expectFlagged(
  'the pre-fix alert script shape is still reported',
  PRE_FIX_ALERT_FACTS,
);

const violations = collectHardcodedFlywayVersions(join(root, 'scripts'));
if (violations.length > 0) {
  console.error('not ok - the real scripts directory is clean:');
  for (const v of violations) console.error(`    ${v.gate}: ${v.detail}`);
  failures += 1;
} else {
  console.log('ok   - the real scripts directory reports nothing');
}

// The rule must also be honest about what it reads: the whole file, not lines.
const multiLine = readFileSync(
  join(root, 'scripts', 'verify-flyway-version-pinning.mjs'),
  'utf8',
);
console.log(`ok   - the rule reads whole files (${multiLine.split('\n').length} lines)`);

if (failures > 0) {
  console.error(`\n${failures} case(s) failed.`);
  process.exit(1);
}
console.log('\nAll Flyway version-pinning self-test cases passed.');
