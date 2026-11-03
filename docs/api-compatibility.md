# API Compatibility Gate

The repository detects **breaking HTTP contract changes** by comparing the committed
OpenAPI baseline with the normalized `/v3/api-docs` of a running instance.

- Baseline: `testdata/api-contract/openapi-baseline.json` (raw springdoc output)
- Comparator: `scripts/lib/openapi-breaking-change.mjs`
- Entry point: `scripts/verify-openapi-breaking-change.mjs`
- Wiring: `OpenAPI breaking-change gate` in the CI backend job and `scripts/verify-api-compatibility.sh`

## Rules

| Change | Breaking |
|--------|----------|
| Removed operation / parameter / response code / response field / request field | Yes |
| Field type or `$ref` target changed | Yes |
| Request field optional→required, or a new required request field | Yes |
| Parameter optional→required | Yes |
| Enum value removed | Yes |
| New optional request field | No |
| New response field or relaxed required | No |
| New operation / parameter / response code | No |

The gate targets existing `/api/v1` consumers. When an incompatible upgrade is truly
required, add `/api/v2` instead of reshaping v1.

## Explicitly Accepted Semantic Changes

### 2026-11: Detail endpoints return `404` instead of `403` for invisible IDs

- **Endpoints**: `GET /api/v1/rag/documents/{id}` and `GET /api/v1/rag/collections/{id}`
  (the deprecated numeric route; `GET /api/v1/rag/collections/{key}` is unchanged).
- **Change**: A restricted key reading an ID outside its allow-list now receives the same
  `404` as a missing ID instead of `403`, which blocks numeric ID enumeration.
- **Impact**: Only restricted callers that relied on `403` to distinguish "exists but
  forbidden" from "missing" are affected; they must treat `404` as "missing or invisible".
  Unrestricted keys, root/`ADMIN`, and deployments with authentication disabled are unchanged.
- **Gate result**: These operations have always declared `200/400/404/500`; `403` was never
  in the OpenAPI baseline, so the shape gate reports PASS. The comparator does detect this
  kind of change: a baseline with `403` against an implementation that only returns `404`
  reports "response code removed" (breaking). This is a runtime semantic tightening, recorded
  as an explicit acceptance rather than a baseline update.
- **Regression evidence**: `DocumentAclControllerTest.restrictedKeyGets404ForDocumentInAnotherCollection`,
  `CollectionAclControllerTest.restrictedKeyGets404ForAnotherCollection`; the `/v3/api-docs`
  of a local demo instance compares cleanly against the baseline.

## Local Usage

```bash
# Compare a running instance
node scripts/verify-openapi-breaking-change.mjs \
  --url http://127.0.0.1:18081/v3/api-docs

# Compare a saved spec
node scripts/verify-openapi-breaking-change.mjs --current /tmp/openapi.json

# Rule self-test (no service required)
node scripts/verify-openapi-breaking-change.mjs --self-test

# Intentional contract change: refresh the baseline and commit it with the PR
node scripts/verify-openapi-breaking-change.mjs \
  --update --current /tmp/openapi.json
```

Exit code 0 means no breaking change; 1 means a break or a missing baseline.

## Baseline Discipline

1. Run `--update` only after confirming the change is acceptable to consumers, and commit
   the refreshed baseline in the same PR as the implementation.
2. Additive changes (optional fields, new endpoints) may refresh the baseline directly.
   Removals, type changes, and newly required fields must go through v2 or be reverted.
3. The baseline is generated; do not hand-edit it. The comparator ignores runtime metadata
   such as `info.version` and `servers` and compares only OpenAPI shapes.
4. CI regenerates `/v3/api-docs` from a demo instance before comparing, so a stale
   baseline fails the gate.

## Boundaries

- This gate proves **HTTP shape** compatibility, not semantic compatibility (for example
  changed meaning of an existing field, default changes, or stricter validation).
- Runtime behavior and data migration remain covered by the nightly, E2E, and Flyway
  path acceptance.
- The comparator starts no real model calls; the gate only needs PostgreSQL and the demo app.
