#!/usr/bin/env bash
# Reject explicit pessimistic coordination in production data-access code.
set -euo pipefail

cd "$(dirname "$0")/.."

SOURCE_ROOTS=(
  spring-ai-rag-api/src/main
  spring-ai-rag-core/src/main
  spring-ai-rag-documents/src/main
  spring-ai-rag-starter/src/main
)

PATTERN='FOR[[:space:]]+(NO[[:space:]]+KEY[[:space:]]+)?UPDATE|FOR[[:space:]]+SHARE|SKIP[[:space:]]+LOCKED|PESSIMISTIC_(READ|WRITE|FORCE_INCREMENT)|pg_(try_)?advisory_((xact_)?lock|unlock)|LOCK[[:space:]]+TABLE'

matches="$(
  rg -n -i \
    --glob '*.java' \
    --glob '*.sql' \
    --regexp "$PATTERN" \
    "${SOURCE_ROOTS[@]}" || true
)"

if [[ -n "$matches" ]]; then
  echo "Explicit pessimistic coordination is forbidden in production data-access code:" >&2
  printf '%s\n' "$matches" >&2
  echo >&2
  echo "Use conditional UPDATE/DELETE ... RETURNING, optimistic versions, unique constraints," >&2
  echo "ON CONFLICT DO NOTHING, leases, and bounded retries instead." >&2
  exit 1
fi

echo "No explicit pessimistic locks or advisory locks found in production sources."
