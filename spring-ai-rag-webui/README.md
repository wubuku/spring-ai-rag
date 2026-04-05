# spring-ai-rag WebUI

> **Built-in administrative web interface for the spring-ai-rag RAG service framework.**

[![][pipeline-badge]][pipeline] [![][java-badge]][java] [![][react-badge]][react] [![][typescript-badge]][typescript]

[English](./README.md) · [中文](./README-zh-CN.md)

---

## Features

| Module | Description |
|--------|-------------|
| **Dashboard** | System health overview, document/collection counts, active sessions, system metrics |
| **Documents** | Upload files, browse with keyword search, preview chunk content inline, batch delete |
| **Collections** | Create knowledge bases, manage document associations, export/import |
| **Chat** | RAG-powered conversational AI with SSE streaming, conversation history sidebar, export to JSON/Markdown |
| **Search** | Real-time hybrid search (vector + full-text), configurable retrieval parameters |
| **Metrics** | RAG system metrics (queries/sec, avg latency, cache hit rate), LLM performance charts |
| **Alerts** | SLO monitoring, alert history, silence schedules for maintenance windows |
| **Settings** | LLM provider selection, retrieval parameters, cache configuration — persisted to localStorage |

## Tech Stack

| Category | Technology | Version |
|----------|------------|---------|
| Framework | React | 19 |
| Language | TypeScript | 5.9 |
| Build Tool | Vite | 8 |
| Styling | CSS Modules | — |
| Server State | TanStack Query | 5 |
| Real-time | Server-Sent Events (SSE) | — |
| HTTP Client | Axios + axios-retry | — |
| Routing | React Router | 7 |
| Charts | Recharts | — |

## Quick Start

### Prerequisites

- Node.js 18+
- spring-ai-rag backend running at `http://localhost:8081`

### Run in development

```bash
cd spring-ai-rag-webui
npm install

# Start Vite dev server (proxies /api/* to localhost:8081)
npm run dev
# → http://localhost:5173/webui/
```

The backend must be running separately:

```bash
# From project root
mvn spring-boot:run -pl spring-ai-rag-core
```

### Production build

```bash
npm run build
# Output: dist/ (copy to spring-ai-rag-core/src/main/resources/static/webui/)
```

## Project Structure

```
src/
├── api/                    # Typed API clients
│   ├── client.ts          # Axios instance with retry interceptor
│   ├── chat.ts            # Chat endpoints + SSE streaming
│   ├── collections.ts     # Collection CRUD + export/import
│   ├── documents.ts       # Document upload + batch operations
│   └── metrics.ts         # Metrics overview
├── components/            # Shared UI components
│   ├── ChatSidebar/       # Chat history sidebar (localStorage)
│   ├── CreateCollectionModal/  # Collection creation form
│   ├── Layout/            # App shell (sidebar + header)
│   ├── Skeleton/          # Loading placeholder
│   └── Toast/             # Notification system
├── hooks/                 # Custom React hooks
│   ├── useSSE.ts          # SSE streaming hook (Chat + Embed progress)
│   └── useFileUpload.ts   # Multipart file upload with progress
├── pages/                 # Route-level page components
│   ├── Dashboard.tsx       # System overview + metrics charts
│   ├── Documents.tsx       # Document management
│   ├── Chat.tsx           # RAG chat interface
│   ├── Collections.tsx    # Collection management
│   ├── Metrics.tsx        # Detailed metrics dashboard
│   ├── Alerts.tsx         # Alert management
│   └── Settings.tsx        # Configuration panel
├── styles/
│   ├── global.css         # CSS variables, resets, base styles
│   └── variables.css      # Theme tokens (colors, spacing)
├── types/
│   └── api.ts             # Shared TypeScript interfaces
└── App.tsx               # Router + QueryClient provider
```

## Key API Endpoints

| Feature | Method | Endpoint |
|---------|--------|----------|
| Health | GET | `/api/v1/rag/health` |
| Documents | GET | `/api/v1/rag/documents?page=0&size=20&title=keyword` |
| Documents | POST | `/api/v1/rag/documents/batch` |
| Documents | POST | `/api/v1/rag/documents/upload` |
| Document SSE | POST | `/api/v1/rag/documents/{id}/embed/stream` |
| Batch SSE | POST | `/api/v1/rag/documents/batch/embed/stream` |
| Collections | GET | `/api/v1/rag/collections/{id}/documents?keyword=` |
| Collections | POST | `/api/v1/rag/collections` |
| Collection Export | GET | `/api/v1/rag/collections/{id}/export` |
| Chat Stream | POST | `/api/v1/rag/chat/stream` |
| Chat History | GET | `/api/v1/rag/chat/history/{conversationId}` |
| Chat Export | GET | `/api/v1/rag/chat/export/{conversationId}?format=json\|md` |
| Search | POST | `/api/v1/rag/search` |
| Metrics | GET | `/api/v1/rag/metrics/overview` |
| Cache Stats | GET | `/api/v1/rag/cache/stats` |
| Alerts | GET | `/api/v1/rag/alerts` |
| SLO Configs | CRUD | `/api/v1/rag/alerts/slos/configs/{name}` |
| Silence Schedules | CRUD | `/api/v1/rag/alerts/silence-schedules/{name}` |

## Development Notes

### SSE Streaming

Chat uses the `useSSE` hook:

```typescript
const { messages, sendMessage, isStreaming } = useSSE({
  endpoint: '/api/v1/rag/chat/stream',
  sessionId: conversationId,
});
```

### File Upload

Multipart upload with per-file progress via `useFileUpload`:

```typescript
const { uploadFiles, isUploading } = useFileUpload({
  onComplete: (fileName) => showToast(`${fileName} uploaded`),
  onError: (fileName, error) => showToast(`${fileName}: ${error}`, 'error'),
});
```

### API Retry

The Axios client has automatic retry with exponential backoff for 429 and 5xx responses:

```typescript
// src/api/client.ts
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 429 || error.response?.status >= 500) {
      // axios-retry handles backoff automatically
      return Promise.reject(error);
    }
    return Promise.reject(error);
  }
);
```

### CSS Modules

Each component/page has its own scoped `.module.css`. Theme variables are defined in `src/styles/variables.css`:

```css
/* Use theme tokens */
.myComponent {
  background: var(--color-surface);
  color: var(--color-text);
  border: 1px solid var(--color-border);
}
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `VITE_API_BASE` | Backend API base URL | `/api/v1/rag` |

```bash
# .env.local (Vite loads .env.* files automatically)
VITE_API_BASE=/api/v1/rag
```

## Scripts

```bash
npm run dev          # Start Vite dev server
npm run build        # Production build (tsc + vite build)
npm run lint         # ESLint
npm run lint:fix     # ESLint auto-fix
npm run preview      # Preview production build locally
```

## License

Same as the [spring-ai-rag](../README.md) project.

[pipeline-badge]: https://img.shields.io/github/actions/workflow/status/wubuku/spring-ai-rag/ci.yml
[java-badge]: https://img.shields.io/badge/Java-21%2B-blue
[react-badge]: https://img.shields.io/badge/React-19-blue
[typescript-badge]: https://img.shields.io/badge/TypeScript-5.9-blue
[pipeline]: https://github.com/wubuku/spring-ai-rag/actions
[java]: https://adoptium.net/
[react]: https://react.dev/
[typescript]: https://www.typescriptlang.org/
