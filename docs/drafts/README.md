# Active Plans

> [English](README.md) | [中文](README-zh-CN.md)

`docs/drafts/` contains only plans and progress ledgers that are **still being
prepared or implemented**. It is not the long-term home for stable project
facts; shipped behavior must be extracted into the corresponding bilingual
evergreen documents.

Read the [Planning, Implementation, And Acceptance Workflow](../delivery-workflow.md)
before creating or maintaining an active plan/progress ledger. This page defines
only the draft lifecycle; it does not duplicate plan content or review-loop rules.

## Rules

1. Use a stable filename for an active plan, such as
   `NEXT_HIGH_VALUE_FEATURES_PLAN.md`, instead of inferring status from a date.
2. Once implementation starts, a matching `*_PROGRESS.md` may record enough
   context to resume the work safely.
3. When work is completed, cancelled, or superseded, first update the relevant
   evergreen `docs/`, then move the plan/progress file with `git mv` into
   [`archive/`](archive/README.md).
4. Preserve or add a `YYYY-MM-DD_` prefix when archiving; the date represents
   the archival or last-valid point in time.
5. `AGENTS.md`, `docs/index*`, and evergreen documents link only to active plans
   or the archive index, not to an individual historical draft as current truth.

## Source-Of-Truth Order

When information conflicts, use:

1. Current code, migrations, and automated tests.
2. Evergreen guides and references under `docs/`.
3. Current active plans in this directory.
4. Historical plans and implementation ledgers under `archive/`.

Active plans are listed here and in `docs/index*`.

## Current Active Plans

The [business-service integration readiness P0
plan](BUSINESS_CLIENT_INTEGRATION_READINESS_PLAN.md) passed three consecutive
reviews on `main` and is being implemented in a dedicated feature worktree.
Resume context, verification evidence, and the next action are tracked in the
[matching progress ledger](BUSINESS_CLIENT_INTEGRATION_READINESS_PROGRESS.md).

Completed, stopped, and superseded materials remain available in the
[historical archive](archive/README.md).
