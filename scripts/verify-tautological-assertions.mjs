#!/usr/bin/env node
// Reject Java test assertions that pass no matter what the code under test does.
//
// The rule and its two false-positive traps are documented in
// ./lib/tautological-assertion-check.mjs. The short version: `assertTrue(true)`
// and a top-level `x || true` are findings; `x == false` and `assertEquals(a, a)`
// are not, because the first is a real assertion and the second calls `equals`.
//
// Scope is deliberately `*/src/test/java/**` and nothing else. This repository's
// gate self-tests live in `scripts/test-support/` and contain assertion shapes on
// purpose; a scan that reached them would either report every fixture or need a
// directory exception, and a directory exception is the allowlist this rule does
// not need.

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { scan } from './lib/tautological-assertion-check.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

/**
 * Batch 902: a gate that runs at import cannot be read by a test. This one is
 * reached by `scripts/test-support/tautological-assertions-self-test.mjs`, which
 * imports the scanner rather than this entry point — but the entry point keeps
 * the same guard as every other gate so the shape stays uniform.
 */
function main() {
  const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));

  const totals = { findings: [], files: 0, assertions: 0 };
  for (const module of ['spring-ai-rag-api', 'spring-ai-rag-core',
    'spring-ai-rag-starter', 'spring-ai-rag-documents']) {
    const dir = path.join(root, module, 'src/test/java');
    const result = scan(dir);
    totals.files += result.files;
    totals.assertions += result.assertions;
    for (const f of result.findings) {
      totals.findings.push({ ...f, file: `${module}/${f.file}` });
    }
  }

  console.log(`Java test files scanned: ${totals.files}, assertion call sites: ${totals.assertions}.`);
  if (totals.findings.length === 0) {
    console.log('No assertion takes a self-satisfying literal, and none has a top-level `|| true` / `&& false`.');
    process.exit(0);
  }

  console.error(`\n${totals.findings.length} assertion(s) that pass regardless of the code under test:\n`);
  for (const f of totals.findings) {
    console.error(`  ${f.file}:${f.line}  [${f.rule}]`);
    console.error(`      ${f.text}`);
    console.error(`      ${f.why}`);
    console.error('');
  }
  console.error('Fix it by asserting the thing the test is about. If the test is genuinely');
  console.error('"does not throw", write assertDoesNotThrow(() -> ...) — that names the');
  console.error('contract and fails loudly if it stops holding. Deleting the assertion is');
  console.error('better than keeping one that cannot fail.');
  process.exit(1);
}

if (isMainModule(import.meta.url)) main();
