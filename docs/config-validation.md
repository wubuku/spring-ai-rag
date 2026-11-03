# Configuration Failure Matrix

This page records what happens when configuration is wrong: whether the failure occurs at startup or on the first call, whether the message names the configuration property, and which cases used to degrade silently and now fail explicitly. Background lives in the [configuration reference](configuration.md), [external model configuration](multi-model-external-config.md), and the [testing guide](testing-guide.md).

## 1. Overview

Rule: **anything decidable at startup fails at startup**. Errors that genuinely require a request (upstream 401, missing collection) must surface on the first call with an actionable error code and message.

| Wrong configuration | Failure point | Outcome |
|---------------------|---------------|---------|
| Upstream `base-url` ends with `/v1` | Startup | Normalized with WARN; no more `/v1/v1/...` |
| Upstream `base-url` contains an unresolved placeholder | Startup | `IllegalStateException` naming the property and placeholder |
| Remote host over plaintext `http` | Startup | Refuses to start; escape hatch is `rag.security.allow-insecure-upstream` |
| Empty or invalid embedding key | Startup | Fails while creating the `embeddingModel` bean |
| Empty chat provider key | Startup (marking) / first call (error) | Model marked unavailable; first call fails when no fallback exists |
| Chat or embedding route references a missing model | Startup | Refuses to start, naming `app.models.*.primary/fallbacks` |
| `rag.embedding` dimensions disagree with the Profile | Startup | Refuses to start (requires a database) |
| Profile key disagrees with a seeded Profile | Startup | Refuses to start (requires a database) |
| Unreachable datasource or bad credentials | Startup | Flyway or pool initialization fails |
| Invalid Hikari pool parameters (negative, `min>max`) | Startup | Refuses to start, naming `spring.datasource.hikari.*` |
| `rag.timeout.*` outside the allowed range | Startup | Refuses to start, naming the timeout |
| Invalid `rag.async.*` thread-pool parameters | Startup | Refuses to start, naming the pool parameter |
| `security.enabled=true` with no static key | Startup (WARN) | Starts, but every request fails closed (401) |
| `prod` plus `security.enabled=false` | Startup | Refuses to start |
| Explicit `app.models.config-file` missing, unreadable, or invalid JSON | Startup | Refuses to start, naming `app.models.config-file` |
| `models.json` uses kebab-case keys | Startup | Refuses to start (those keys would silently no-op) |
| `models.json` contains unknown keys | Startup (WARN) | Ignored, with full paths listed |
| `collectionKey` does not exist | First call | HTTP 404 plus `COLLECTION_NOT_FOUND` |
| Database migration version below the application | Startup | Flyway migrates automatically |
| Database migration version above the application | Startup | Flyway validation fails; startup is refused |

## 2. Upstream Addresses And Credentials

### 2.1 base-url normalization

All model upstream addresses go through `UpstreamEndpointPolicy`:

- a trailing `/` is removed;
- a trailing `/v1` is removed with a WARN, because Spring AI appends `/v1/...` itself;
- an unresolved `${...}` placeholder is treated as missing configuration and fails;
- a non-`http(s)` URL, missing host, embedded userinfo, query, or fragment fails.

Covered properties:

| Path | Property |
|------|----------|
| Built-in ChatModel | `spring.ai.openai.base-url`, `spring.ai.anthropic.base-url`, `spring.ai.minimax.base-url` |
| External models | `app.models.providers.<id>.base-url` |
| Embedding | `rag.embedding.base-url`, `rag.embedding.profiles.*.base-url` |

### 2.2 Plaintext http boundary

Only loopback hosts (`localhost`, `127.x`, `::1`) may use plaintext `http` by default. Remote hosts must use `https`; a trusted private network can opt in explicitly:

```yaml
rag:
  security:
    allow-insecure-upstream: ${RAG_ALLOW_INSECURE_UPSTREAM:false}
```

This switch only affects model upstream addresses. It does not relax `rag.security.enabled` authentication semantics.

### 2.3 Missing keys

- **Embedding**: fails during bean creation (startup) with `Embedding API key is not configured for the selected Profile`. This is correct because retrieval cannot work without a key.
- **Chat**: `ConfiguredChatModelFactory` marks models with an empty key, an unresolved placeholder, or invalid configuration as unavailable. When the primary model is unavailable and no fallback is usable, the first call fails with an actionable model-resolution error. When a fallback is configured, it switches — this is intentional (see section 8).

## 3. Models And Routing

### 3.1 Referenced models must exist

Every `providerId/modelId` referenced by `app.models.chat-model.primary`, `app.models.chat-model.fallbacks`, `app.models.embedding-model.primary`, and `app.models.embedding-model.fallbacks` must exist in `app.models.providers`, with the provider enabled and the model type matching. Otherwise startup fails, and the message contains the property name and the invalid reference.

### 3.2 Embedding Profile and vector dimensions

At startup `EmbeddingProfileRegistry`:

- validates that the Profile key, provider, model, revision, distance, and normalization are non-empty;
- accepts only `COSINE`;
- validates dimensions through `EmbeddingVectorColumns.columnFor`, which allows 384 / 768 / 1024 / 1536;
- compares every configured field against the seeded Profile in the database and refuses to start on any mismatch, including dimensions.

So "change only `dimensions` without creating a new Profile" cannot silently corrupt data; it fails at startup. Changing models requires a new Profile and re-embedding. See [project context](project-context.md).

## 4. Datasource And Pool

- Unreachable datasource or bad credentials: Flyway or pool initialization throws at startup, which is fast-fail.
- `spring.datasource.hikari.maximum-pool-size < 1`, `minimum-idle < 0`, `minimum-idle > maximum-pool-size`, or `connection-timeout < 250`: `RagDataSourceStartupValidator` refuses to start. Previously `min>max` only produced a Hikari WARN and was silently clamped to `maximum-pool-size`; it now fails explicitly.
- When `spring.datasource.url` is absent, validation is skipped so database-less slice tests keep working.

## 5. Security Switches

- `prod` / `production` profile with `rag.security.enabled=false`: refuses to start, telling the operator to set `RAG_SECURITY_ENABLED=true` and `RAG_ROOT_API_KEY`.
- `rag.security.enabled=true` with both `api-key` and `root-api-key` empty: starts with a WARN. This is intentional fail-closed behaviour: every `/api/**` request returns 401, while managed API keys already registered in the database keep working. The `ragSecurityPosture` health check stays UP so orchestration does not restart a healthy instance.

## 6. Runtime Errors And 404

A missing `collectionKey` is not a startup error because collections are runtime data. `CollectionIdentityResolver` throws `RagException` with `COLLECTION_NOT_FOUND`, mapped to HTTP 404, and the message contains the invalid `collectionKey`.

## 7. Migration Versions

- Database below the application: Flyway migrates to the latest version at startup, with no manual switch.
- Database above the application (for example after rolling back to an old image): Flyway validation fails and startup is refused, so old code cannot read or write a newer schema.
- Migration files live under `spring-ai-rag-core/src/main/resources/db/migration/`; schema changes must add a migration.

## 8. Silent Degradation Inventory

The following behaviours still degrade instead of failing, because they are runtime resilience rather than configuration errors:

| Behaviour | Reason | Should it fail instead? |
|-----------|--------|-------------------------|
| Chat switches to a fallback when the primary is unavailable | The point of multi-model routing | No; `/models` exposes the unavailable reason |
| Unknown `models.json` keys are ignored | Forward compatibility | No; full paths are listed at startup |
| Security enabled without a static key only warns | Managed keys still work | No; there is a clear WARN and health detail |
| Vector dimension mismatch | — | Changed to a startup failure; no longer degrades |
| Hikari `min>max` is clamped | — | Changed to a startup failure; no longer degrades |
| Missing `app.models.config-file` falls back to YAML | — | Changed to a startup failure; no longer degrades |

## 9. Tests And Gates

- Startup-validation unit tests: `UpstreamEndpointPolicyTest`, `RagAsyncPropertiesTest`, `RagTimeoutPropertiesTest`, `RagDataSourceStartupValidatorTest`, `MultiModelConfigLoaderTest`, `ConfiguredChatModelFactoryStartupValidationTest`, `SecurityPostureConfigurationTest`.
- Assertions that need a database for Profile dimensions and key consistency live in gated suites and run through `scripts/verify-gated-it.sh`.
- Besides `mvn test`, a commit must pass `scripts/verify-project-docs.sh`, `scripts/verify-integration-test-switches.mjs`, and `scripts/verify-no-pessimistic-locks.sh`.

## 10. Troubleshooting

1. Start with the property name in the startup failure message and fix that property or environment variable.
2. A 404 mentioning `/v1/v1`: confirm the configured `base-url` does not end with `/v1`.
3. Remote `http` rejected: use `https`; enable `allow-insecure-upstream` only inside a trusted private network.
4. Embedding startup failure: check that `rag.embedding.*` and the active Profile agree on key, dimensions, model, and revision.
5. `min>max` or timeout-range errors: adjust within the bounds stated in the message instead of relying on framework clamping.
