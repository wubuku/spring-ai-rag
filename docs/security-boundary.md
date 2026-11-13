# Security Boundaries and Tenant Isolation

> Applies to `1.0.0` (Flyway `V71`).  
> Related: [REST API authentication](rest-api-zh-CN.md#认证), [configuration](configuration-zh-CN.md), [testing guide](testing-guide-zh-CN.md).

This page records the data-plane authorization boundary, the gaps that were fixed, conclusions on input attack surfaces, and how the boundary is verified. It is not an exhaustive security inventory; it pins down **paths that actually allowed cross-tenant access** and the regression entry points that keep them closed.

---

## 1. Trust Model

| Caller | Classification | Data scope |
|--------|----------------|------------|
| Authentication disabled (`rag.security.enabled=false`) | no principal | everything (isolated/local only) |
| root API key / `ADMIN` / key without collection restrictions | `ApiKeyCollectionAccess.isUnrestricted` | everything, unchanged from previous releases |
| restricted `NORMAL` key | bound to `allowedCollectionIds` | allowed collections plus content created by this principal |
| multiple keys of one managed principal | shared `principalId` | shared scope of that principal |

"Restricted" means **a non-blank `allowedCollectionIds` or `allowedCollectionKeys` was explicitly configured**. Blank means unrestricted; this is the existing contract and is not silently tightened.

---

## 2. Fixed Gaps (V71)

### 2.1 File Subsystem

**Problem**: `fs_files` / `fs_import_batches` had no ownership columns, and `/files/tree`, `/files/raw`, `/files/preview` authenticated but never checked Collection ACL. Any `RAG_READ` key could enumerate and download every uploaded file, and could trigger embedding from another caller's PDF import UUID.

**Fix**:

- New columns `fs_files.owner_principal_id`, `fs_files.collection_id`, and the same names on `fs_import_batches`.
- Uploads (`POST /files/pdf`, `POST /files/pdf-to-rag`) stamp the current principal; PDF-to-RAG also stamps the target Collection.
- All read paths now use `listVisibleChildren` / `getVisibleFile` / `getVisibleImportBatch`:
  - unrestricted: original query path unchanged;
  - restricted: `owner_principal_id = current principal` **or** `collection_id ∈ allowed set`;
  - legacy rows with both `null` are invisible to restricted keys (fail-closed; no speculative backfill).
- `/{uuid}/embed` and its SSE variant verify import visibility before embedding, blocking "read someone else's PDF into my collection".

### 2.2 Evaluation / Feedback

**Problem**: `/evaluation/history`, `/evaluation/report`, `/evaluation/metrics`, `/feedback/{stats,history,type}` were global views. Any restricted key could read other callers' `query`, `comment`, and `sessionId`.

**Fix**: `rag_retrieval_evaluations.owner_principal_id` and `rag_user_feedback.owner_principal_id`. Writes stamp the current principal; reads:

- unrestricted: global view unchanged;
- restricted: `owner_principal_id = current principal` only.

Legacy rows without ownership become invisible to restricted keys; root can still audit the full set.

### 2.3 Backward Compatibility

- root / `ADMIN` / authentication-disabled deployments: unchanged.
- restricted keys: can only see **less** data, never more.
- no manual switch on upgrade; nullable V71 migration applies automatically at startup.

### 2.4 Numeric ID enumeration on detail endpoints (403 → 404)

**Problem**: When a restricted key requested a `documentId` / `collectionId` outside its
allow-list, the implementation returned `403`. That differed from the `404` for a missing
ID and let a caller confirm that the numeric ID exists.

**Fix**: `GET /api/v1/rag/documents/{id}` and `GET /api/v1/rag/collections/{id}` (the
deprecated numeric route) now return the same `404` as a missing ID when the ACL rejects.
The `collectionKey` resolver keeps its existing semantics: a restricted caller explicitly
asking for an unknown or unauthorized key still receives `403`, because the key is a
caller-supplied namespace rather than a numeric ID probe.

**Compatibility**: Only restricted keys are affected, and only toward **less** exposure.
Unrestricted keys, root/`ADMIN`, and deployments with authentication disabled are unchanged.
See §5 for regression entry points.

### 2.5 Authorization Matrix (Endpoint × Role × Ownership)

`scripts/verify-security-boundary.py` enumerates **76 assertions** against a real
PostgreSQL instance plus a mock provider. Role dimensions: anonymous / forged key,
principal A (resource owner), principal B (another valid principal), a read-only
restricted key (`RAG_READ`), and root (unrestricted). Every cross-principal assertion
also checks that the response body does not contain protected data (document content,
external IDs, evaluation cases, feedback comments, etc.).

**Status contract** (denials are always 403 or 404, split by probe dimension):

| Expected | Dimension | Reason |
|----------|-----------|--------|
| `401` | missing / forged key | authentication precedes authorization; no resource decision is made |
| `404` | server-side resource ID (numeric ID, UUID, external-ID tuple) is not visible | identical to "not found", blocking existence enumeration |
| `403` | caller-supplied namespace or missing capability (`collectionKey` outside the allow-list, no `RAG_WRITE`, restricted key creating a Collection) | the input itself is a caller namespace/capability; the response does not reveal whether a server-side resource exists |
| `200` + empty/own-only | list endpoints scoped by principal | keeps the list contract and leaks no other principal's counts |

Why both codes coexist: the **ID dimension** must be 404, otherwise a 403/404 difference
is itself an existence oracle. The **namespace/capability dimension** keeps 403: a
restricted key explicitly asking for a disallowed `collectionKey` is an established
contract and does not probe a server-side ID. The tests assert both "another principal's
ID" and "a non-existent ID" for the same resource and require the same status.

**Matrix** (`A` = owner, `B` = another valid principal, `RO` = read-only restricted key,
`root` = unrestricted):

| Resource | Endpoints | Anonymous | A | B | RO | root |
|----------|-----------|-----------|---|----|----|------|
| auth | `GET /collections`, `POST /search`, `GET /files/tree` | 401 | 200 | 200 | 200 | 200 |
| collection | `GET /collections/{id}` | 401 | 200 | **404** | — | 200 |
| collection | `GET /collections/{id}/documents`, `/export`, `GET /collections/by-key/{key}/export` | 401 | 200 | **403** | — | 200 |
| collection | `PUT/DELETE /collections/{id}`, `POST /collections` | 401 | 200 | **403** | **403** | 200 |
| collection | `POST /search`, `POST /chat` with another principal's `collectionKeys` | 401 | 200 | **403** | — | 200 |
| collection | `POST /collections/{own}/documents` referencing another principal's `documentId` | 401 | 200 | **404** | — | 200 |
| document | `GET /documents/{id}`, `/versions`, `/versions/{n}` | 401 | 200 | **404** | — | 200 |
| document | `PATCH/DELETE /documents/{id}`, `disable`, `restore`, `versions/{n}/restore` | 401 | 200 | **404** | **403** (read-only) | 200 |
| document | `DELETE /documents/batch`, `POST /documents/batch/embed` (other or missing IDs) | 401 | 200 | **404** | **403** | 200 |
| chunk | `GET /documents/{id}/chunks/preview` | 401 | 200 | **404** | — | 200 |
| reindex | `POST /documents/{id}/embed`, `/embed/stream`; `GET /embedding-jobs/{uuid}`, `GET /document-processing-runs/{uuid}` | 401 | 200 | **404** | — | 200 |
| upload | `POST /documents/upload`, `POST /files/pdf-to-rag` with another principal's `collectionKey` | 401 | 200 | **403** | **403** (read-only) | 200 |
| import | `GET /files/raw/{uuid}/{name}`, `GET /files/preview/{uuid}/{name}` | 401 | 200 | **404** | — | 200 |
| import | `POST /files/{uuid}/embed` | 401 | 200 | **403** | **403** | 200 |
| import | legacy row with `NULL` ownership (fail-closed) | 401 | — | **404** | — | 200 |
| download | `GET /files/raw?path=` probes: `..`, double encoding, absolute path, NUL, symlink | 401 | 200 | **400/404** | **400/404** | 200 |
| evaluation | `GET /evaluation/suites/{key}`, `POST .../versions`, `POST .../diagnose`, `GET /evaluation/runs/{uuid}` | 401 | 200 | **404** | — | **404** (principal isolation) |
| evaluation | `GET /evaluation/suites`, `/history`, `/report`, `/metrics` | 401 | 200 | 200 (own-only/empty) | — | 200 (global) |
| feedback | `POST /evaluation/feedback`; `GET /feedback/{stats,history,type}` | 401 | 200 | 200 (own-only) | **403** | 200 (global) |
| sse | `POST /chat/stream` with another principal's `collectionKeys` | 401 | 200 | **403** | — | 200 |
| external-sync | `GET /document-sync-runs/{uuid}?collectionKey=`, `complete`, `abort`, `batch-upsert` | 401 | 200 | **404** | — | 200 |
| external-sync | same run addressed with another principal's `collectionKey` | 401 | 200 | **403** | — | 200 |
| external-sync | `POST /documents/upsert`, `/batch-upsert`, `GET/DELETE /documents/by-external-id` targeting another Collection | 401 | 200 | **403** | **403** | 200 |

Notes:

- root is **not** a cross-principal evaluation superuser: suites and runs are isolated
  per principal, so root reading A's suite/run gets 404. This is deliberate (root must
  not accidentally read another principal's evaluation content); operator audit needs a
  dedicated principal or the database layer.
- Import batches have no standalone delete route; deletion is covered through the
  Collection/document paths.
- A read-only key gets 200 for its own readable resources; write, delete, and
  administrative routes uniformly return 403 (capability fail-closed).

**Fixes in this round** (each reproduced by an assertion above before being locked in):

1. **Document detail/mutation 403 → 404**: `RagDocumentController.requireDocumentAccess`
   converts ACL denial into `DocumentNotFoundException`; update, disable, restore, and
   version restore gained ID pre-checks.
2. **Batch existence oracle**: batch delete/re-index return a uniform 404 for both
   "another principal's ID" and "non-existent ID", no longer revealing which entry exists.
3. **Sub-resources**: chunk preview, document processing run, and embedding job map ACL
   denial to 404 (`DocumentChunkPreviewController`, `DocumentProcessingRunService`,
   `EmbeddingJobService`).
4. **Cross-Collection reference**: `POST /collections/{id}/documents` referencing
   another principal's document changes 403 → 404 (`RagCollectionController`,
   `DocumentMutationService`).
5. **Sync-run lease timing oracle**: `DocumentSyncRunService` now checks Collection ACL
   before the lease token, removing the "wrong token = 409 / invisible run = 404"
   difference.
6. **External batch upsert fail-open**: `ExternalDocumentService.batchUpsert` used to
   swallow `SecurityException` into a 200 per-item failure; it now rethrows, so an
   authorization failure is never disguised as a business failure.

---

## 3. Input Attack Surface Conclusions

| Vector | Conclusion | Handling |
|--------|------------|----------|
| Path traversal (`../`, URL encoding, backslashes, NUL) | paths are only used for DB queries, never concatenated into filesystem paths; still rejected explicitly | `PdfImportService.validateVirtualPath` rejects `.`/`..`/control characters after decoding (400); temp filenames are sanitized with a character allowlist |
| Oversized upload / upload bomb | size is checked before reading, streaming copy is bounded, Spring multipart rejects oversized requests first | keep `rag.pdf.max-upload-bytes` (20MB default) and multipart limits; tests cover 400 for oversize |
| Invalid `collectionKey` | unknown key returns 403 for restricted callers and 404 for unrestricted | no data beyond existence is returned; covered by the regression matrix |
| Numeric ID enumeration on detail endpoints | restricted keys receive `404` (formerly `403`) for invisible `documentId` / `collectionId` | same as a missing ID, blocking existence probes; see §2.4 |
| Oversized query / title / content | chat and search queries ≤10,000 characters; document title ≤255 and content ≤1M (create and update paths identical) | Bean Validation returns 400 `VALIDATION_FAILED` before the model and persistence; oversized payloads never reach the model and never surface as database length errors |
| NUL byte (U+0000) | PostgreSQL `text` / `jsonb` cannot store NUL | `@NoNulCharacter` rejects it at the API boundary with 400, avoiding persistence 500s and corrupted logs/exports |
| Whitespace-only / malformed text | whitespace-only is rejected; emoji, combining marks, mixed RTL, zero-width characters, long words, and homoglyphs pass within budget | no glyph normalization that would break retrieval; only length and illegal characters are constrained |
| SSRF (external document sync URL) | **not applicable**: sync is push-based (`POST /documents/upsert` receives content); the server never downloads a client-supplied URL | no `java.net.URL` / `RestClient` external-fetch path exists. If one is added later, it must allow only `http/https` and reject internal addresses after DNS resolution (including rebinding) |

---

## 4. Configuration Misuse Protection

`rag.security.enabled` defaults to `false`. When off, `/api/**` does not validate keys, so exposing that configuration to the public internet publishes the data plane.

Implemented in `SecurityPostureConfiguration`:

1. a WARN banner at startup with the risk and the fix;
2. **refuses to start** when the active profile is `prod` / `production` and security is disabled;
3. health component `ragSecurityPosture` exposes:
   - disabled: `securityEnabled=false`, `risk=HIGH`, `exposedWithoutAuthentication=true`, `warning=...`;
   - enabled: `securityEnabled=true`.
4. health status stays `UP` so orchestrators do not restart a healthy instance for a configuration risk; the risk is surfaced through details and logs.

A production deployment needs at least:

```bash
RAG_SECURITY_ENABLED=true
RAG_ROOT_API_KEY=<random long secret>
```

---

## 5. Verification

| Layer | Content | Entry |
|-------|---------|-------|
| PR fast layer | file ACL decision table, path traversal, feedback/evaluation scoping, disabled-security protection, restricted-key empty tree | `SecurityBoundaryAccessTest`, `SecurityPostureConfigurationTest`, `PdfImportControllerTest.listTree_restrictedKeyReturns200EmptyWhenNothingIsVisible`, `UserFeedbackServiceScopingTest`, `RetrievalEvaluationServiceScopingTest` |
| gated layer | real PostgreSQL + mock provider, 76-assertion "endpoint × role × ownership" authorization matrix over HTTP | `scripts/verify-security-boundary.py` (nightly `soak` job) |
| gated layer | V70 legacy data → V71: fail-closed, root visibility, new-write ownership, backfill script | `scripts/verify-security-upgrade-path.py` (nightly `soak` job) |
| PR fast layer | retrieved-content prompt injection, forged tool arguments, caps under injection pressure | `PromptInjectionContainmentTest`, `RagChatToolPolicyCallbackCallTest.policyBudgetExhaustedShortCircuits` |
| PR fast layer | oversized/malformed input boundaries and NUL | `InputBoundaryValidationTest`, `RagChatControllerCoverageValidationWebTest.nulAndOversizedMessagesAreRejectedAs400WithoutModelWork` |
| gated layer | deployment posture: `prod` refusal, auth-enabled boot, non-prod warning | `scripts/verify-security-deployment-posture.py` (nightly `soak` job) |

The matrix covers `search`, `chat` (including SSE), `collections`
(detail/documents/write/delete/export), `documents` (read/update/delete/versions/batch/
reindex), `chunks preview`, `files tree/raw/preview` plus download path probes,
`documents upload`, `files/pdf-to-rag`, `import batch embed`, `embedding-jobs`,
`document-processing-runs`, `evaluation` (suite/version/diagnose/run/history/report/
metrics), `feedback` (stats/history/type), `document-sync-runs`
(read/complete/abort/batch-upsert), `documents upsert/by-external-id/batch-upsert`,
read-only capability fail-closed, and orphaned-row fail-closed. Per-assertion
expectations are in §2.5.

Run locally:

```bash
python3 scripts/verify-security-boundary.py
```

The script creates a temporary database and mock provider and deletes both on exit; assertions are never relaxed to pass.

---

## 6. Known Residual Risks

- Legacy `fs_files` / feedback / evaluation rows have no ownership and are invisible to restricted keys. Making historical data visible to a specific principal requires a controlled backfill, not a relaxed query.
- root / `ADMIN` remains principal-isolated for evaluation suites and runs (§2.5); cross-principal evaluation audit needs a dedicated principal or the database layer. root is not a superuser over evaluation content.
- Import batches have no standalone delete endpoint; any future route must follow the §2.5 status contract and be added to the matrix.
- File downloads are DB-mediated (virtual path → DB record), so symlinks under the import directory are unreachable. If storage ever becomes direct-from-disk, add `realpath` containment checks and re-run the §5 download probes.
- Status-code consistency depends on every ID-based endpoint mapping denials explicitly; new ID endpoints must reuse the §2.4 404 masking or they reintroduce an existence oracle.
- `403` (namespace/capability) and `404` (resource ID) are a dimension contract; clients must not treat 403 as "the resource exists".
- Batch semantics: any invisible/missing ID in a batch makes the whole request 404 (rather than per-item failures). This is a deliberate trade-off to avoid per-item existence leakage; clients should pre-validate.
- Multiple keys of one managed principal share ownership (files are owned per principal, not per key). That is design semantics, not cross-tenant access.
- Audit logs and export artifacts are data-plane data (governed by deployment permissions); the confidentiality boundary for application logs and metrics is in §9.
- With authentication disabled there is no principal concept; internal paths (plugins/tools) operate globally. That is the established behavior for isolated environments.
- Multi-tenancy (WP8 stage) uses two SSE authorization layers: connection-time ACL plus re-check on **every delivery**. If a key is revoked or expires while subscribed, the next event delivery is dropped and the old subscription receives nothing. `RagDocumentController.embedDocumentStream` is wired; remaining SSE entrypoints adopt `TenantAuthorizedSsePublisher` incrementally (current implementation surface is request-scoped short streams with no long-lived subscription registry, so the exposure is bounded). Any task ID never triggers an external Provider query; results are authoritative via the owner quadruple (tenant/collection/owner/receipt) in the V82 evidence tables.

---

## 7. V71 Upgrade And Backfill

### 7.1 Upgrade behavior

- V71 adds **nullable columns** and runs automatically on startup; no manual switch
  or re-index is required.
- Legacy files, feedback, and evaluations stay at `owner_principal_id IS NULL`.
  Restricted keys cannot see them; root / `ADMIN` / deployments with auth disabled
  still see everything. This is a deliberate fail-closed choice, not an omission.
- New writes after upgrade (upload, PDF-to-RAG, evaluation, feedback) record the
  current principal; a restricted key only sees its own new data.

### 7.2 Deployment templates

- The Helm chart defaults to `postgresql,prod` and now exposes
  `secrets.securityEnabled` (default `"true"`), injected explicitly as
  `RAG_SECURITY_ENABLED`. `secrets.rootApiKey` is the recommended production
  prerequisite. Setting `securityEnabled` to `"false"` makes `prod` refuse to start.
- Docker Compose defaults to `SPRING_PROFILES_ACTIVE=postgresql,prod` and
  `RAG_SECURITY_ENABLED=true`. To fully disable auth in an isolated environment,
  set both `SPRING_PROFILES_ACTIVE=postgresql` and
  `RAG_SECURITY_ENABLED=false`; either one alone is not enough.
- With auth disabled, startup logs and the `ragSecurityPosture` health component
  report `risk=HIGH`.

### 7.3 Controlled backfill (never automatic)

Assigning ownership of historical data is an operator decision. The script is not
run on startup, by migrations, or by any gate:

```bash
# 1) Default dry-run: count and sample only, no writes
python3 scripts/backfill-security-ownership.py --owner-principal-id <principal>

# 2) Review the impact, then write explicitly
python3 scripts/backfill-security-ownership.py --owner-principal-id <principal> --apply

# 3) Files only, also assign legacy files without a Collection
python3 scripts/backfill-security-ownership.py \
  --owner-principal-id <principal> --only files --files-collection-id 12 --apply
```

Semantics:

- Updates only rows with `owner_principal_id IS NULL`; it never overwrites an
  existing owner.
- `--only` accepts `files` / `feedback` / `evaluations`; all three by default.
- Connection settings come from `POSTGRES_HOST/PORT/USER/PASSWORD`; the database
  comes from `--database` or `POSTGRES_DATABASE`.
- Backfill only changes visibility, not content; the root view is unaffected.
- Back up first: the operation is one-way and has no automatic rollback script.

### 7.4 Acceptance

```bash
python3 scripts/verify-security-upgrade-path.py
python3 scripts/verify-security-deployment-posture.py
```

Both scripts use a temporary database and mock provider and delete them on exit.
The upgrade script first constructs V70-shaped data (ownerless files/feedback/
evaluation), upgrades to V71, and verifies fail-closed, root visibility, new-write
ownership, and the backfill script's dry-run/apply behavior.

---

## 8. Browser Security Headers And Injection Surface

### 8.1 Response headers (issued by the application)

`SecurityHeadersFilter` (order `-100`, mapped to `/*`) applies the same headers to
the WebUI static assets, `/api/**`, SSE, and error responses:

| Header | Value |
|--------|-------|
| `Content-Security-Policy` | `default-src 'self'; base-uri 'self'; script-src 'self'; style-src 'self'; style-src-attr 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; media-src 'self' blob:; object-src 'self' blob:; frame-src 'self'; worker-src 'self'; manifest-src 'self'; form-action 'self'; frame-ancestors 'none'` |
| `X-Content-Type-Options` | `nosniff` |
| `Referrer-Policy` | `no-referrer` |
| `X-Frame-Options` | `DENY` (belt and braces with `frame-ancestors 'none'`) |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=(), payment=(), usb=(), magnetometer=(), gyroscope=(), accelerometer=(), interest-cohort=()` |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains`, **HTTPS only** |

Why it is not stricter:

- **Scripts are external**: the only inline theme bootstrap script moved to
  `public/theme-init.js`, so `script-src` does not need
  `'unsafe-inline'`. A new inline script violates the policy immediately.
- **`style-src-attr 'unsafe-inline'` is the only concession**: React writes
  `style` attributes (both in the Vite output and at runtime); disabling them
  breaks layout. Stylesheets themselves remain `style-src 'self'`. No nonce/hash
  is used because the static artifacts are generated at build time; runtime nonce
  injection would turn static files into template rendering.
- **`blob:` only for `img` / `media` / `object`**: file preview shows PDFs/images
  through `blob:` URLs; scripts and connections remain `'self'` only.
- **`connect-src 'self'` covers same-origin API and SSE**: no CDN and no
  cross-origin WebSocket.
- **HSTS only over HTTPS**: direct `request.isSecure()` or a trusted proxy's
  `X-Forwarded-Proto: https`. The Helm chart sets
  `server.forward-headers-strategy: framework` by default when an Ingress
  terminates TLS. Docker Compose runs directly and keeps it disabled to avoid
  client-spoofed `X-Forwarded-*` affecting security decisions and client IP.

### 8.2 Injection surface conclusions

| Surface | Conclusion |
|---------|------------|
| Chat answers / citations | Rendered through React text interpolation, so HTML is escaped; CSP is the second line of defense and tests show inline `<script>` and inline event handlers are rejected |
| Rich text preview | Only `FilePreview` uses `dangerouslySetInnerHTML`, fed by the server-side `MarkdownRendererService` jsoup Safelist (removes `script/style/iframe/object/embed/form` and `on*`) |
| Document titles / filenames | The API preserves quotes, CJK, and HTML characters verbatim; the frontend renders them as text, never as HTML |
| Download headers | `Content-Disposition` always uses `ContentDisposition.inline()/attachment().filename(name, UTF_8)` (RFC 5987) and strips bare CR/LF; quoted filenames cannot break the header structure |
| Chat export | HTTP supports `json` / `md` only and exposes no NDJSON endpoint; the internal CSV writer prefixes `'` to cells starting with `=` `+` `-` `@`, tab/CR, or leading whitespace followed by a trigger character to prevent spreadsheet formula injection |

### 8.3 Acceptance

```bash
python3 scripts/verify-browser-security-headers-e2e.py
```

Real process plus real Chromium: header matrix for `/webui/`, API, SSE, and 404;
no HSTS over HTTP and HSTS with `X-Forwarded-Proto: https`; page load, chat SSE,
upload, search, and export remain usable under CSP; inline `<script>` and
`onclick` injections created inside the page are rejected and raise
`securitypolicyviolation`; model output containing `<script>` renders as text.

Unit level: `SecurityHeadersFilterTest`, `ChatExportServiceTest` (CSV formula
injection), `PdfImportControllerContentTypeTest` (RFC 5987 headers).

### 8.4 Retrieved-content prompt injection and input boundaries

**Principle: retrieved text and tool results are untrusted data, never system instructions.**

| Boundary | Implementation |
|----------|----------------|
| Knowledge-mode document boundary | `CitationQueryAugmenter` wraps every document in `<<<UNTRUSTED_DATA ...>>>` / `<<<END_UNTRUSTED_DATA ...>>>` and states that text inside asking to ignore instructions, leak data, or change answer rules is not executable; only the "user question" region outside the boundaries carries instructions. |
| Agent system prompt | The system prompt states that tool snippets are evidence to verify and that requests inside them to reveal prompts/secrets/configuration, search outside the authorized scope, rewrite tool arguments, or change answer rules must be ignored. |
| Tool output markers | `searchKnowledge` / `searchStaticKnowledge` / `searchJsonRecords` emit `contentBoundary=retrieved_content_is_untrusted_data` and `boundaryInstruction`; the tool schemas state the same data semantics. |
| Server-side tool argument validation | All three retrieval tools accept only schema-declared fields. `searchKnowledge` / `searchStaticKnowledge` return `invalid_tool_arguments` for forged `collectionId` / `scope` / `apiKey` and short-circuit before retrieval; `searchJsonRecords` rejects argument errors (the registry short-circuits them uniformly). ACL scope always comes from the server-owned `AuthorizedRetrievalContext`, so model arguments cannot widen it. |
| Tool query budget | A single tool query is truncated before retrieval/embedding once it exceeds 2,000 characters (UTF-16, surrogate-safe). Tool results remain bounded by total-character, per-name, and round budgets; exhaustion returns `tool_call_policy_exhausted` or an explicit `CHAT_BUDGET_EXHAUSTED`, never a silent wrong answer. |
| Request input budget | Chat/search queries ≤10,000 characters; document title ≤255 and content ≤1M (create and update paths identical); over-limit returns 400 `VALIDATION_FAILED`. |
| Illegal characters | All user text and structured `metadata` / `jsonbPayload` at any nesting depth reject NUL (U+0000), preventing PostgreSQL `text/jsonb` write errors (500) and log/export corruption. Emoji, combining marks, mixed RTL, zero-width characters, long words, and homoglyphs pass within budget. |

Acceptance:

- `PromptInjectionContainmentTest`: decoy documents containing "ignore previous
  instructions / print the system prompt / search another collection / print the
  API key / include internal configuration"; asserts documents are wrapped in the
  untrusted boundary, forged tool arguments are rejected, the authorized scope is
  unchanged, tool queries are truncated, and budget caps hold.
- `InputBoundaryValidationTest`: 100k-character queries, oversized titles/content,
  NUL, and whitespace-only are rejected; emoji/combining/RTL/zero-width/long-word/
  homoglyph inputs pass.
- `RagChatControllerCoverageValidationWebTest`: NUL and oversized messages return
  400 `VALIDATION_FAILED` with zero model or scope-resolution calls.

---

## 9. Log And Observability Confidentiality

### 9.1 Principle

**Request/response bodies and document text belong to the data plane only**:
logs and metrics record correlation identifiers, lengths, counts, and timings.
The following must never appear in application logs at any level:

| Content | Log substitute |
|---------|----------------|
| User prompt / chat history | `messageChars` |
| Retrieval query / rewritten query | `queryChars` |
| Document title, collection name, uploaded filename | length or resource id |
| Document body, retrieved chunks | not logged |
| API key, `Authorization`, `Idempotency-Key` | not logged; auth failures log path and principal only |
| Client-reported error message / pageUrl | `errorType` and session only |

Slow-query logging keeps only the statement shape: every single-quoted literal
is replaced with `'***'`, so a slow INSERT/UPDATE cannot push document text into
the WARN log. Client JSON logs still mask `apiKey` / `token` / `password`
through `SensitiveDataMaskingConverter`.

### 9.2 Actuator and error bodies

- `/actuator` exposes only `health,info,metrics,prometheus`; `env`, `beans`,
  `loggers`, and `configprops` return 404.
- `/actuator/health` keeps the `ragSecurityPosture` details but disables the
  built-in `diskSpace` indicator, which would publish absolute host paths.
  Container health should use quota/probes, not host disk paths.
- 5xx error bodies pass through `ErrorMessageSanitizer`, which removes stack
  traces, class names, and local paths; exports contain no internal paths or
  connection strings.

### 9.3 Acceptance

```bash
python3 scripts/verify-log-secrecy-e2e.py
```

Real process plus isolated PostgreSQL and mock provider: send prompts, retrieval
queries, document titles/bodies, upload filenames/content, client errors, invalid
API key/Bearer, and `Idempotency-Key` headers carrying `SECRET_CANARY_*`, then
scan the full application log (the demo sets `com.springairag` to DEBUG, covering
INFO and DEBUG), actuator responses, error bodies, and collection/chat exports.

The only allowed canary locations are the data plane itself: collection/chat
exports must contain the exported body and title (otherwise redaction is
over-broad). Credential canaries and internal path markers must have zero hits
in logs, metrics, error bodies, and exports.

---

## 10. WebUI Credential Storage And Session Security

### 10.1 Storage locations

| Location | Holds API key | Notes |
|----------|---------------|-------|
| Module memory | yes | module-level variable in `credentialStore`, read by React via subscription |
| `localStorage` | no | writes are forbidden; legacy `rag-api-key*` entries are cleared at startup |
| `sessionStorage` | no | stores only non-sensitive route/workspace state; cleared on logout |
| Cookie | no | credentials are never stored in cookies |
| URL / Referer | no | credentials travel only in the `X-API-Key` request header; `Referrer-Policy: no-referrer` |

Resulting session semantics:

- **Refresh locks the app**: memory does not survive a page load, so refresh
  returns to Unlock; there is no "remember me".
- **Tabs are independent**: each tab holds its own memory credential; unlocking
  tab A does not unlock tab B.
- **Logout clears everything**: memory credential, legacy plaintext entries,
  and session-level workspace state are removed together.
- **No reachable XSS surface**: injected scripts can read `localStorage` /
  `sessionStorage` / cookies / URL and find nothing; the module closure value is
  not attached to the global object.

### 10.2 Acceptance

```bash
python3 scripts/verify-webui-credential-security-e2e.py
```

Real backend plus real Chromium: scan every storage surface after unlock; refresh
returns to Unlock; a new tab stays locked; an injected in-page script scanning
storage/globals/form values finds no credential; logout clears storage; no request
URL or `Referer` contains the credential, which appears only in the `X-API-Key`
request header.

## Tenant lifecycle platform-side boundary (WP9)

- The `/api/v1/rag/tenants` endpoints are platform-side: only an actor with a
  configured environment root credential, root authentication, and a place in the
  `rag.tenant.lifecycle-actors` whitelist may call them. When the root credential
  is not configured the endpoints fail closed (403); no static or database
  credential can create a tenant.
- Every lifecycle call is audited as a capability event (actor / target tenant /
  allowed or not) without tenant-private content. Rejecting a non-`default`
  tenant creation in SINGLE mode records a `REJECT` audit entry as well.
- After a tenant is `DISABLED` or soft-deleted (`deleted_at`), the tenant-status
  subquery in the authentication projection returns no row and
  `PrincipalTenantResolver` fails closed (treated as non-`ACTIVE`), so existing
  credentials stop resolving immediately.
- The business data plane has no cross-tenant entry point: credential tables and
  the tenant lifecycle platform table do not use RLS but are only accessed on the
  authentication and platform-management paths, never on business queries.
