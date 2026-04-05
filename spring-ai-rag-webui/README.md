# spring-ai-rag WebUI

Built-in administrative web interface for the spring-ai-rag RAG service framework.

## Features

- **Dashboard** — System health overview, document counts, active sessions
- **Documents** — Upload, browse, delete documents with chunk preview
- **Collections** — Create and manage knowledge bases
- **Chat** — RAG-powered conversational AI with SSE streaming
- **Search** — Real-time hybrid search (vector + full-text)
- **Metrics** — RAG system metrics and LLM performance
- **Alerts** — SLO monitoring and alert management
- **Settings** — Configure LLM, retrieval, and cache parameters

## Tech Stack

| Category | Technology |
|----------|-----------|
| Framework | React 19 |
| Language | TypeScript 5.9 |
| Build Tool | Vite 8 |
| Styling | CSS Modules |
| Server State | TanStack Query 5 |
| Real-time | Server-Sent Events (SSE) |
| HTTP Client | Axios |
| Routing | React Router 7 |

## Setup

```bash
# Install dependencies
npm install

# Development server (proxies /api/* to localhost:8081)
npm run dev
# Frontend: http://localhost:5173/webui/
# API calls automatically forwarded to http://localhost:8081/

# Backend must be running separately:
# mvn spring-boot:run -pl spring-ai-rag-core
```

# Production build
npm run build

# Lint
npm run lint
```

## API Integration

The WebUI expects the backend to be running at `http://localhost:8081`. Configure the base URL in `src/api/client.ts`.

### Key API Endpoints

| Feature | Endpoint |
|---------|----------|
| Health | `GET /api/v1/health` |
| Documents | `GET/POST /api/v1/rag/documents` |
| Collections | `GET/POST /api/v1/rag/collections` |
| Chat | `POST /api/v1/rag/chat/stream` |
| Search | `POST /api/v1/rag/search` |
| Metrics | `GET /api/v1/rag/metrics/overview` |
| Alerts | `GET /api/v1/rag/alerts` |
| SSE Progress | `POST /api/v1/rag/documents/{id}/embed/stream` |

## Architecture

```
src/
├── api/           # API client modules (documents, chat, collections, etc.)
├── components/    # Shared components (Layout)
├── hooks/         # Custom hooks (useSSE, useFileUpload)
├── pages/         # Route-level page components
├── styles/        # Global CSS and CSS variables
└── types/         # TypeScript type definitions (api.ts)
```

### SSE Streaming

Chat streaming uses the `useSSE` hook:

```typescript
const { messages, sendMessage, isStreaming } = useSSE({
  endpoint: '/api/v1/rag/chat/stream',
  sessionId,
});
```

### File Upload

Document upload with progress tracking via `useFileUpload`:

```typescript
const { upload, progress, isUploading } = useFileUpload({
  endpoint: '/api/v1/rag/documents/upload',
});
```

## Development Notes

- CSS Modules prevent style leakage — each component/page has its own `.module.css`
- TanStack Query handles caching, retries, and loading states
- API responses follow RFC 7807 Problem Details format for errors
- SSE events: `CHUNK` | `METRICS` | `SOURCE` | `ERROR` | `DONE`
