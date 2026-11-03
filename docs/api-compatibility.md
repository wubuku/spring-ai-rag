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
| Field widened to nullable (`T → T|null`) | No |
| New operation / parameter / response code | No |

The gate targets existing `/api/v1` consumers. When an incompatible upgrade is truly
required, add `/api/v2` instead of reshaping v1.

## Explicitly Accepted Semantic Changes

### 2026-11: springdoc 2.8.17 rendering change and streaming export baseline refresh

- **Trigger**: `springdoc-openapi` 2.8.4 → 2.8.17 (with swagger-core 2.2.34 → 2.2.47),
  clearing the swagger-ui webjar DOMPurify advisory. No Java type or controller signature changed.
- **Rendering change (not a runtime change)**:
  - The `JsonNode` component moved from `{"type":"object"}` to `{}` (no type constraint).
    An untyped schema accepts any JSON, which is a **widening**: payloads valid before
    remain valid. The eight affected request fields (`answerRequirements`, `jsonbPayload`,
    `metadataContains`, `payloadContains`, `definition`) are still Jackson `JsonNode`
    at runtime.
  - Wrapper types annotated `@Schema(nullable = true)` (for example
    `ApiKeyRotationPrepareRequest.overlapSeconds`) render as `type: ["integer","null"]`
    instead of `type: integer`. The comparator now normalizes `T → T|null` as
    nullability widening and a self-test case covers it.
  - `Map<String, JsonNode>` collections render `additionalProperties` as `{}`
    instead of `{"type":"object"}` (the same widening).
  - A few parameters and response headers now document constraints already expressed
    by annotations: `exclusiveMinimum`, a parameter `default`, and a typed
    `Retry-After` header. Runtime validation and defaults are unchanged.
- **Pre-existing change also captured by the refresh**: the collection export response
  moved from the JSON `CollectionExportResponse` schema to `StreamingResponseBody`
  (streaming export, commit `1163acd5`). The comparator only checks operations, fields
  and response codes, not media-type top-level `$ref`s, so that drift was not reported
  when it landed; this refresh aligns the baseline with the actual output.
- **Consumer impact**: No operation, request/response field, response code or required
  entry was removed; `JsonNode` fields still carry free-form JSON. Only the OpenAPI type
  annotation is widened.
- **Regression evidence**: `node scripts/verify-openapi-breaking-change.mjs --self-test`
  passes in full; a freshly generated local demo `/v3/api-docs` compares as
  `PASS OpenAPI 无破坏性变更` against the refreshed baseline.

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
