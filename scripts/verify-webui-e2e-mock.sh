#!/usr/bin/env bash
# WebUI mock Playwright acceptance gate: every non-`-real` e2e spec, in one run.
#
# Batch 804 surveyed which e2e specs any gate actually runs. Fourteen of twenty
# are named by a scripts/verify-*.sh. Six were not: `dashboard.spec.ts`,
# `evaluation-tabs.spec.ts` and `files.spec.ts` appeared in no script and no
# document, and `alignment.spec.ts`, `files-real.spec.ts` and
# `workspace-continuity.spec.ts` only in archived progress notes.
#
# Running the unreachable ones paid for the survey immediately: 22 tests, 21
# passed, and one that could never have passed. Running the *reachable* ones
# alongside them found three more — a tab strip refactored to proper ARIA tabs
# without its selectors following, and a search box addressed by its raw
# translation key. None of those could have been caught by a spec nobody ran.
#
# This script closes the gap by running the whole mock suite at once. It needs
# no database, no model provider and no running backend: the specs stub every
# API call, and the preview server serves the production build. The specs that
# do need a live backend are the `*-real` ones, which `playwright.preview.config.ts`
# ignores and which the `verify-*.sh` scripts that need them already run.
#
# Env:
#   E2E_MOCK_PORT  preview port (default 15174, matching the config)
#   E2E_PROJECT    Playwright project (default chromium)
set -euo pipefail

cd "$(dirname "$0")/.."

# Without a usable toolchain the run below would fail for the wrong reason and
# `|| true` would turn a missing environment into a green gate. A gate that
# cannot fail is worse than no gate.
for command_name in node npx; do
  command -v "$command_name" >/dev/null || {
    echo "Missing required command: ${command_name}" >&2
    echo "This gate cannot run; treat it as failed, not as passed." >&2
    exit 1
  }
done

if [[ ! -d spring-ai-rag-webui/node_modules/@playwright/test ]]; then
  echo "Missing spring-ai-rag-webui/node_modules; run npm install first." >&2
  exit 1
fi

E2E_PROJECT="${E2E_PROJECT:-chromium}"

echo "Running the full WebUI mock e2e suite (playwright.preview.config.ts)."
echo "This ignores *-real.spec.ts, which need a live backend."
echo

cd spring-ai-rag-webui
exec npx playwright test \
  --config playwright.preview.config.ts \
  --project="${E2E_PROJECT}" \
  --reporter=line
