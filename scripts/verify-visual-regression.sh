#!/usr/bin/env bash
# WebUI visual regression gate: Playwright screenshot baselines for the key pages.
#
# The existing e2e specs assert semantics (a heading exists, a tab switches) but
# observe nothing about appearance: change a design token or a grid and every
# assertion still passes. This gate compares rendered pixels against committed
# baselines, so a layout shift fails the build.
#
# Scope: Dashboard, Documents, Chat, Collections, Files at desktop (1440×900) and
# mobile (390×844). Thresholds are tuned to absorb font anti-aliasing but not
# layout movement — see `docs/testing-guide-zh-CN.md` → 视觉回归.
#
# Baselines are per-platform (`*-chromium-darwin.png`, `*-chromium-linux.png`).
# CI must render with the same Playwright image the Linux baselines were made
# with; the workflow step therefore runs *this script* inside that container.
#
# Env:
#   E2E_PROJECT  Playwright project (default chromium)
set -euo pipefail

cd "$(dirname "$0")/.."

# A gate that cannot fail is worse than no gate: a missing toolchain would
# otherwise abort inside Playwright and read as an environment problem.
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

echo "Running WebUI visual regression (playwright.visual.config.ts, 5 pages × 2 viewports)."
echo "Baselines resolve per platform; update them only from the same renderer as CI."
echo

cd spring-ai-rag-webui
exec npx playwright test \
  --config playwright.visual.config.ts \
  --project="${E2E_PROJECT}" \
  --reporter=line \
  e2e/visual.spec.ts
