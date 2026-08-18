#!/usr/bin/env bash
# Aggregate verification for the next high-value feature batches.
set -euo pipefail

cd "$(dirname "$0")/.."

./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-retrieval-diagnostics.sh
./scripts/verify-retrieval-filters.sh
./scripts/verify-embedding-operations.sh
./scripts/verify-managed-quality.sh
