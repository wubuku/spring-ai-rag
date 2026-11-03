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

---

## 3. Input Attack Surface Conclusions

| Vector | Conclusion | Handling |
|--------|------------|----------|
| Path traversal (`../`, URL encoding, backslashes, NUL) | paths are only used for DB queries, never concatenated into filesystem paths; still rejected explicitly | `PdfImportService.validateVirtualPath` rejects `.`/`..`/control characters after decoding (400); temp filenames are sanitized with a character allowlist |
| Oversized upload / upload bomb | size is checked before reading, streaming copy is bounded, Spring multipart rejects oversized requests first | keep `rag.pdf.max-upload-bytes` (20MB default) and multipart limits; tests cover 400 for oversize |
| Invalid `collectionKey` | unknown key returns 403 for restricted callers and 404 for unrestricted | no data beyond existence is returned; covered by the regression matrix |
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
| PR fast layer | file ACL decision table, path traversal, feedback/evaluation scoping, disabled-security protection | `SecurityBoundaryAccessTest`, `SecurityPostureConfigurationTest`, `UserFeedbackServiceScopingTest`, `RetrievalEvaluationServiceScopingTest` |
| gated layer | real PostgreSQL + mock provider, full cross-Collection / cross-principal matrix over HTTP | `scripts/verify-security-boundary.py` (nightly `soak` job) |

The matrix covers `search`, `chat`, `collections` (detail/documents/export), `documents upload`, `files/pdf-to-rag`, `files tree/raw/preview`, `evaluation history/report/metrics`, and `feedback stats/history/type`.

Run locally:

```bash
python3 scripts/verify-security-boundary.py
```

The script creates a temporary database and mock provider and deletes both on exit; assertions are never relaxed to pass.

---

## 6. Known Residual Risks

- Legacy `fs_files` / feedback / evaluation rows have no ownership and are invisible to restricted keys. Making historical data visible to a specific principal requires a controlled backfill, not a relaxed query.
- Logs and audit exports (for example `logs/*.jsonl`) are out of scope here and remain governed by deployment permissions.
- With authentication disabled there is no principal concept; internal paths (plugins/tools) operate globally. That is the established behavior for isolated environments.
