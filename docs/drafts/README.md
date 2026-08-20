# Active Plans

> [English](README.md) | [中文](README-zh-CN.md)

`docs/drafts/` contains only plans and progress ledgers that are **still being
prepared or implemented**. It is not the long-term home for stable project
facts; shipped behavior must be extracted into the corresponding bilingual
evergreen documents.

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

Active plans are listed here and in `docs/index*`. An otherwise empty directory
means there is no approved or in-progress active plan.

## Current Active Plans

- [Next High-Value Features Plan](NEXT_HIGH_VALUE_FEATURES_PLAN.md): atomic
  external-document Collection relocation and derivation-integrity operations.
- [Planning Progress](NEXT_HIGH_VALUE_FEATURES_PROGRESS.md): research,
  review-counter, and resumable context for the active plan.
