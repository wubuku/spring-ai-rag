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
