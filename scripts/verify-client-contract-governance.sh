#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

MANIFEST="${API_CONTRACT_MANIFEST:-testdata/api-contract/v1-compatibility.json}"
SPEC_FILE="${API_CONTRACT_SPEC_FILE:-}"

[[ -f "$MANIFEST" ]] || {
  echo "Missing API contract manifest: $MANIFEST" >&2
  exit 2
}

node scripts/lib/api-contract-check.mjs --self-test >/dev/null

node - "$MANIFEST" <<'NODE'
const fs = require('node:fs');
const manifest = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
if (manifest.contractPolicy?.changeMode !== 'ADDITIVE_ONLY') {
  throw new Error('contract policy must remain ADDITIVE_ONLY');
}
for (const field of ['error', 'message', 'status', 'failureClass']) {
  if (!manifest.contractPolicy.requiredErrorProperties?.includes(field)) {
    throw new Error(`missing required error property: ${field}`);
  }
}
if (manifest.reliability?.failureClassHeader !== 'X-RAG-Failure-Class') {
  throw new Error('failure class response header contract changed');
}
if (manifest.governance?.policyPath !== '/rag/data-governance/policy'
    || manifest.governance?.auditPath !== '/rag/audit-logs'
    || manifest.governance?.auditRequiresManagementAccess !== true) {
  throw new Error('governance endpoint contract changed');
}
NODE

if [[ -n "$SPEC_FILE" ]]; then
  [[ -s "$SPEC_FILE" ]] || {
    echo "OpenAPI spec is empty: $SPEC_FILE" >&2
    exit 1
  }
  node scripts/lib/api-contract-check.mjs \
    --spec "$SPEC_FILE" \
    --manifest "$MANIFEST" >/dev/null
fi

echo "Client API contract governance verification passed"
