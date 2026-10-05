#!/usr/bin/env node
// Self-test for scripts/verify-playwright-suite-selection.mjs.
//
// Batch 926. The positive cases are the **pre-fix text** of the two scripts this
// rule was written from, and that is the point: a rule never shown the instance
// it exists for proves nothing.
//
// The negative cases are the heavier half, and two of them exist because the
// rule was wrong twice before this file did. It first reported
// `verify-next-high-value-feature.sh`, which scopes its run by passing
// `"$PLAYWRIGHT_SPEC"` — a variable the rule could not see. And its fix for
// that used a `^$` anchor that missed the same line, because the argument opens
// with a quote. Seventeen of the nineteen Playwright invocations in this
// repository are legitimately scoped; a rule that flags them is noise, and noise
// is how a gate gets ignored.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  checkPlaywrightInvocations,
  collectPlaywrightInvocations,
} from '../verify-playwright-suite-selection.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
let failures = 0;

function expectFlagged(label, source) {
  const found = checkPlaywrightInvocations(source);
  if (found.length === 0) {
    console.error(`not ok - ${label}: expected a violation, got none`);
    failures += 1;
    return;
  }
  console.log(`ok   - ${label}`);
}

function expectAccepted(label, source) {
  const found = checkPlaywrightInvocations(source);
  if (found.length > 0) {
    console.error(`not ok - ${label}: unexpected violation: ${found[0].detail}`);
    failures += 1;
    return;
  }
  console.log(`ok   - ${label}`);
}

// ── the two pre-fix scripts, verbatim in the part that matters ───────────────

expectFlagged('the pre-fix jsonb-records invocation, verbatim', `
  local rc=0
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:\${PLAYWRIGHT_PORT}" npx playwright test
  ) || rc=$?
  cleanup_playwright_preview
  return "$rc"
`);

expectFlagged('the pre-fix release invocation is the same shape', `
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:\${PLAYWRIGHT_PORT}" npx playwright test
  ) || rc=$?
`);

expectFlagged('a bare invocation carrying only a reporter flag', `
  npx playwright test --reporter=line
`);

// ── shapes that must not be reported ─────────────────────────────────────────

// Both corrected scripts.
expectAccepted('the corrected invocation, with an explicit config', `
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:\${PLAYWRIGHT_PORT}" \\
      npx playwright test --config playwright.hosted-preview.config.ts
  ) || rc=$?
`);

expectAccepted('the canonical mock suite, which owns its own webServer', `
exec npx playwright test \\
  --config playwright.preview.config.ts \\
  --project="\${E2E_PROJECT}" \\
  --reporter=line
`);

// The case the rule got wrong first: a spec in a variable. Naming it is
// scoping the run, and a static reader cannot resolve it — so it has to accept
// it rather than report it.
expectAccepted('a spec passed through a variable', `
  npx playwright test "$PLAYWRIGHT_SPEC" --project=chromium
`);

expectAccepted('several specs named on wrapped continuation lines', `
  npx playwright test \\
    e2e/rerank-document-diversity-real.spec.ts \\
    e2e/files-real.spec.ts \\
    --project=chromium
`);

// Naming a real spec on purpose is the entire point of the alert and api-key
// acceptance runs.
expectAccepted('a real spec named deliberately', `
  npx playwright test e2e/api-key-real.spec.ts --project=chromium
`);

expectAccepted('a spec filtered by grep instead of named', `
  npx playwright test --grep "Collection lifecycle"
`);

expectAccepted("this gate's own header quotes the broken invocation", `
  #   BASE_URL="http://127.0.0.1:\${PLAYWRIGHT_PORT}" npx playwright test
  npx playwright test e2e/documents.spec.ts
`);

expectAccepted('a script that never invokes Playwright', `
  mvn -pl spring-ai-rag-core -am test
`);

// ── the real repository ──────────────────────────────────────────────────────

const violations = collectPlaywrightInvocations(join(root, 'scripts'), root);
if (violations.length > 0) {
  console.error('not ok - the real scripts directory is clean:');
  for (const v of violations) console.error(`    ${v.gate}: ${v.detail}`);
  failures += 1;
} else {
  console.log('ok   - the real scripts directory reports nothing');
}

// The message tells the reader which config to pass. If that file is not there,
// the gate sends them somewhere that cannot help them.
const config = join(root, 'spring-ai-rag-webui', 'playwright.hosted-preview.config.ts');
if (/testIgnore:\s*\[[^\]]*-real\.spec\.ts/.test(readFileSync(config, 'utf8'))) {
  console.log('ok   - playwright.hosted-preview.config.ts excludes the real specs');
} else {
  console.error('not ok - playwright.hosted-preview.config.ts does not exclude them');
  failures += 1;
}

if (failures > 0) {
  console.error(`\n${failures} case(s) failed.`);
  process.exit(1);
}
console.log('\nAll Playwright suite-selection self-test cases passed.');
