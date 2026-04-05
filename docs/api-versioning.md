# API Versioning Strategy

> Created: 2026-04-05
> Status: 📋 Planned

---

## Overview

This document outlines the API versioning strategy for spring-ai-rag v1 → v2 transition.

## Current State (v1)

All REST endpoints are under `/api/v1/rag/*`:
- `POST /api/v1/rag/chat` — RAG chat
- `POST /api/v1/rag/chat/stream` — SSE streaming chat
- `GET/POST /api/v1/rag/documents` — Document CRUD
- `POST /api/v1/rag/documents/batch` — Batch operations
- `GET/POST /api/v1/rag/collections` — Collection management
- `GET /api/v1/rag/search` — Hybrid search
- `GET /api/v1/rag/health` — Health check
- `GET /api/v1/rag/metrics` — Metrics
- `GET /api/v1/rag/evaluate` — Retrieval evaluation
- `GET/POST /api/v1/rag/ab-tests` — A/B experiments
- `GET/POST /api/v1/rag/alerts` — Alerts

## When to Version

Breaking changes require a new API version:
- Removing or renaming fields in request/response DTOs
- Changing field types
- Changing endpoint behavior semantically
- Removing endpoints
- Changing authentication requirements

Non-breaking changes do NOT require new versions:
- Adding new optional fields to requests/responses
- Adding new endpoints
- Adding new optional query parameters

## v2 Breaking Changes (Planned)

| # | Change | Rationale |
|---|--------|-----------|
| 1 | Standardize response envelope: `{ "data": ..., "meta": { "traceId": ..., "timestamp": ... } }` | Consistent wrapper for all responses |
| 2 | Rename `ChatRequest.model` → `ChatRequest.provider` + `ChatRequest.modelId` | Clarify multi-model routing parameters |
| 3 | Remove deprecated endpoints: `/batch/create-and-embed` | Already marked @Deprecated, remove in v2 |
| 4 | SSE event format: standardize `event: chunk\|done\|error` naming | Current inconsistency in event names |
| 5 | Collection ID from numeric Long → UUID string | Better scalability and security |
| 6 | Error response: RFC 7807 Problem Details only (remove custom ErrorResponse) | Standard compliance |

## v2 Non-Breaking Additions

| # | Feature | Rationale |
|---|---------|-----------|
| A1 | `POST /api/v2/rag/chat` — Full v2 Chat API | New versioned endpoint |
| A2 | `GET /api/v1/rag` → redirect to API docs | Better developer experience |
| A3 | Batch SSE progress tracking | Real-time batch operation progress |
| A4 | `DELETE /api/v1/rag/cache` — Cache invalidation admin | Operations need cache control |

## Versioning Strategy

**URL Path Versioning** (`/api/v1/`, `/api/v2/`):
- Most explicit and readable
- Easy to route in API gateways
- Standard industry practice

**Timeline**:
- v1 remains available for minimum 12 months after v2 release
- v1 endpoints marked `@Deprecated` with sunset date in response headers
- After 12 months, v1 moved to `/api/v1-deprecated/` path

## Implementation Plan

1. **Phase 1**: Document this strategy (N13)
2. **Phase 2**: Implement `/api/v2/rag/chat` with new response envelope
3. **Phase 3**: Migrate other endpoints to v2
4. **Phase 4**: Add deprecation headers to v1
5. **Phase 5**: Archive v1 after sunset period

## Migration Guide (v1 → v2)

```bash
# v1
curl -X POST http://localhost:8081/api/v1/rag/chat \
  -H "Content-Type: application/json" \
  -d '{"query": "What is RAG?", "collectionIds": [1]}'

# v2 (example new format)
curl -X POST http://localhost:8081/api/v2/rag/chat \
  -H "Content-Type: application/json" \
  -d '{"query": "What is RAG?", "collections": ["uuid-1"]}'
```

## References

- [RFC 7807 Problem Details](https://tools.ietf.org/html/rfc7807)
- [API Versioning Strategies](https://stackoverflow.com/questions/389169/best-practices-for-api-versioning)
