# LLM Budget Uncertain Settlement and Cost Safety

> [English](budget-settlement-safety.md) | [中文](budget-settlement-safety-zh-CN.md)

## 1. Default Behavior

LLM calls reserve durable budget before contacting the provider. Daily/monthly limits are shared across
instances and enabled by default, without extra model calls for ordinary Chat. This ledger is not a provider
invoice; configured prices and tokenizer estimates do not establish an absolute provider spending bound.

TTL expiration does not prove that no consumption occurred. After a crash, timeout, or early cleanup,
the reservation becomes `EXPIRED`; its estimate moves from reserved to settled **without a refund**.
The first late settlement changes `EXPIRED` to `SETTLED` and adjusts the original UTC day/month windows
by actual usage minus the estimate. Missing usage retains the estimate. Duplicate settlement is inert;
CAS contention retries at most three times. Accounting failure does not replace the answer, and cleanup
eventually conservatively charges outstanding reservations.

`release` is only for calls known not to have reached a provider, not a generic timeout/cancellation refund.
Released or settled rows cannot be charged again by cleanup or callbacks. Callbacks after ledger deletion
cannot settle; retention defaults to 30 days. Very late or deleted history is not fabricated as actual usage.
Terminal retention fixes a reserved-word SQL alias; active reservations, their windows and recent rows remain protected.

## 2. Cost Constraints

When any `daily-cost-limit`, `monthly-cost-limit`, or `max-request-cost` is configured, the model must have
explicit finite non-negative input/output prices. Missing or invalid prices fail before dispatch with the
existing `503 LLM_BUDGET_SERVICE_UNAVAILABLE`. Explicit zero means free and differs from missing prices.
Token-only deployments without cost limits still require no prices. Token/cost exhaustion retains
`429 LLM_BUDGET_EXCEEDED`; streams retain their existing error and terminal protocol.

Each logical execution/call ordinal has one reservation with immutable owner and estimates. Placeholder
insertion and daily/monthly checks share a transaction; rejection rolls everything back, preventing double
reservation or cross-owner reuse.

## 3. Automatic Migration and Compatibility

Flyway V66 backfills retained legacy `EXPIRED` rows, charging previously refunded estimates to their
original settled windows and creating missing windows. SETTLED/RELEASED/RESERVED rows are unchanged.
Migration runs once without manual repair. History already deleted by old versions cannot be reconstructed.

No API field, route, JSON envelope, SSE order, finishReason, or public Java constructor changes. The only
policy tightening is rejecting unknown prices when cost caps are configured. Stop old instances before
starting the new version and migrating: old cleanup workers must not keep refunding against the shared DB.
Old binaries can read the unchanged schema but restore the old cost risk; prefer forward fixes, not manual
reverse refunds.

## 4. Verification Entry Points

- `scripts/verify-gated-it.sh LlmBudgetAndGovernancePostgresIntegrationTest`: real PostgreSQL,
  V65→V66, fresh/repeated migration, concurrent reservations, expiry/late settlement races, original windows,
  and pricing policy.
- `python3 scripts/verify-budget-settlement-e2e.py`: isolated DB, real HTTP and controlled provider;
  Native/OpenAI JSON/SSE, rejection without dispatch, in-flight TTL, replay, SIGKILL and restart.
- [Testing guide](testing-guide.md) and [configuration reference](configuration.md): comprehensive Client/SDK,
  real-model and `scripts/dev.sh` acceptance. A controlled provider is not evidence of real model quality.
