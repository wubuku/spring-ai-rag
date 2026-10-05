#!/usr/bin/env node
// Self-test for scripts/verify-capability-protocol-pinning.mjs.
//
// Batch 924. The positive cases are the **pre-fix text** of the four scripts
// whose pinned protocol version was corrected, and that is the point of the
// file: a scanner that has never been shown the instances it exists to catch is
// not evidence of anything. This rule made that mistake twice before this file
// existed — its first version matched `.protocol.apiVersion` (the API version, a
// different field, and a line that was already correct), and its second missed
// `capabilities["protocol"]["version"]` (a Python subscript it had to be taught
// the bracket spelling of). Both were caught by running the pre-fix lines
// through it, not by reading it.
//
// The negative cases carry as much weight. This gate has no allowlist, and the
// four things that could each have justified one — the envelope protocol that
// shares the field name, the shared library that defines the value, the self-test
// fixtures that manufacture a mismatch on purpose, and this file's own header
// quoting the broken lines verbatim — are all rejected on what they are rather
// than on where they live.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  checkPinnedCapabilityProtocol,
  collectPinnedCapabilityProtocols,
  CONTRACT_SOURCE,
} from '../verify-capability-protocol-pinning.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
let failures = 0;

function expectFlagged(label, source) {
  const found = checkPinnedCapabilityProtocol(source, 'fixture');
  if (found.length === 0) {
    console.error(`not ok - ${label}: expected a violation, got none`);
    failures += 1;
    return;
  }
  console.log(`ok   - ${label}`);
}

function expectAccepted(label, source) {
  const found = checkPinnedCapabilityProtocol(source, 'fixture');
  if (found.length > 0) {
    console.error(`not ok - ${label}: unexpected violation: ${found[0].detail}`);
    failures += 1;
    return;
  }
  console.log(`ok   - ${label}`);
}

// ── the four pre-fix scripts, verbatim in the part that matters ──────────────

expectFlagged(
  'business-client-contract-e2e.sh required 1.0 of a report whose producer requires 1.1',
  `
  jq -e \\
    --arg result "$expected_result" '
      .schemaVersion == 1
      and .result == $result
      and .capability.protocolVersion == "1.0"
    ' "$report" >/dev/null || {
    echo "report schema/assertion failed" >&2
    return 1
  }
`,
);

expectFlagged(
  'verify-document-sync-runs.sh required 1.0 of the same endpoint',
  `
capabilities, capability_headers = request(
    "GET",
    "/integration-capabilities",
    api_key=reader_key,
    capture_headers=True)
assert capabilities["protocol"]["version"] == "1.0"
`,
);

expectFlagged(
  'the jq spelling the two pre-fix scripts shared',
  `
  jq -e '
    .protocol.version == "1.1"
    and .features.provisioning.collectionCreateIdempotencyKey == true
  ' "$PRIVATE_DIR/capabilities.json" >/dev/null || return 1
`,
);

expectFlagged(
  'the Python spelling inside the preflight report validator',
  `
    if payload["capability"]["protocolVersion"] != "1.1":
        raise SystemExit("successful report requires capability protocol 1.1")
`,
);

expectFlagged('a != comparison counts, not only ==', `
  .protocolVersion != "1.0" and .ok
`);

expectFlagged(
  'a pinned version is reported once per occurrence, with its line',
  `
  jq -e '
    and .protocol.version == "1.1"
    and .protocolVersion == "1.0"
  '
`,
);

// ── shapes that must not be reported ─────────────────────────────────────────

// The API version is a different field with a different number to bump. The
// first version of this rule reported it, in a file that was already correct.
expectAccepted('the API version beside the protocol version', `
  jq -e '
    .protocol.name == $contractName
    and .protocol.version == $contractVersion
    and .protocol.apiVersion == "1.0.0"
  ' >/dev/null
`);

expectAccepted('a bound variable is the correct shape', `
  jq -e \\
    --arg contractVersion "$INTEGRATION_CAPABILITY_CONTRACT_VERSION" '
    .protocol.version == $contractVersion
  ' "$response" >/dev/null
`);

// Shares the field name with the capability protocol, and is an envelope *name*
// rather than a version. Confined to one script, and the rule separates them on
// what the value is rather than on which file it is in.
expectAccepted('the record-mutation envelope protocol name', `
  jq -e '
    .protocolVersion == "generic-client-record-mutation-v1"
    and .operation == "UPSERT"
  ' >/dev/null
`);

expectAccepted('this gate\'s own header quotes both broken lines', `
  #   * \`business-client-contract-e2e.sh\` required \`protocolVersion == "1.0"\` while
  #   * \`verify-document-sync-runs.sh\` read the same endpoint and required \`"1.0"\`.
  jq -e '.protocol.version == $contractVersion'
`);

// No exemption: the definition site's constants are named for the contract, not
// for a comparison, so the rule does not reach them.
expectAccepted('the shared library that defines the value', `
INTEGRATION_CAPABILITY_CONTRACT_NAME="spring-ai-rag-integration"
INTEGRATION_CAPABILITY_CONTRACT_VERSION="1.1"
`);

// No exemption: a self-test fixture passes the value it wants to disagree with
// as a positional parameter, never as a compared literal.
expectAccepted('a self-test fixture that manufactures a mismatch', `
run_capability_case() {
  local protocol="\${7:-1.1}" extra_env="\${8:-}"
}
run_capability_case "capability-protocol-mismatch" 1 "CAPABILITY_CONTRACT_MISMATCH" 20 10485760 true 1.0
`);

expectAccepted('a self-test assertion bound to its fixture parameter', `
    else .capability.protocolVersion == $expectedProtocol
`);

expectAccepted('a pinned version far from the protocol on the same line', `
  echo "protocol" > "$f" && jq -e '.count == "1.1"'
`);

// ── the real repository ──────────────────────────────────────────────────────

const violations = collectPinnedCapabilityProtocols(join(root, 'scripts'), root);
if (violations.length > 0) {
  console.error('not ok - the real scripts directory is clean:');
  for (const v of violations) console.error(`    ${v.gate}: ${v.detail}`);
  failures += 1;
} else {
  console.log('ok   - the real scripts directory reports nothing');
}

// The library the gate points at must be the file that actually defines it. A
// message naming a path that does not exist is a gate that cannot be acted on.
const library = readFileSync(join(root, CONTRACT_SOURCE), 'utf8');
if (/^INTEGRATION_CAPABILITY_CONTRACT_VERSION="[\d.]+"$/m.test(library)) {
  console.log(`ok   - ${CONTRACT_SOURCE} defines the contract version`);
} else {
  console.error(`not ok - ${CONTRACT_SOURCE} does not define the contract version`);
  failures += 1;
}

// The rule must also be honest about what it reads: the whole file, comments
// stripped, rather than one line at a time.
const self = readFileSync(
  join(root, 'scripts', 'verify-capability-protocol-pinning.mjs'),
  'utf8',
);
console.log(`ok   - the rule reads whole files (${self.split('\n').length} lines)`);

if (failures > 0) {
  console.error(`\n${failures} case(s) failed.`);
  process.exit(1);
}
console.log('\nAll capability protocol-pinning self-test cases passed.');
