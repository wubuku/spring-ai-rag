# Developer Reference

> [English](developer-reference.md) | [中文](developer-reference-zh-CN.md)

> **Purpose**: Provide copyable build, startup, database, model, WebUI, E2E, and release-verification commands.
> **Maintenance**: Commands must match repository scripts. Local Agent state may link here, but this document never depends on local state.

Documentation hub: [index.md](index.md). Stable project context: [project-context.md](project-context.md).

## 1. Fixed Conventions

| Item | Value |
|------|-------|
| Java | 21+ |
| Maven | 3.9+ |
| Default port | `8081` |
| Local profile | `postgresql` |
| Real-LLM E2E port | `18081` |
| Embedding | SiliconFlow `BAAI/bge-m3` |
| Vector dimension | `1024` |
| Flyway | V1–V24 |

Do **not** append `/v1` to an OpenAI or Embedding `base-url`. Spring AI appends `/v1/chat/completions` or `/v1/embeddings`.

## 2. Build And Test

```bash
mvn clean compile
mvn test
mvn clean package -DskipTests
```

One module or test:

```bash
mvn test -pl spring-ai-rag-core
mvn test -pl spring-ai-rag-core -Dtest=RagDocumentControllerTest
```

Coverage:

```bash
mvn clean test jacoco:report
open spring-ai-rag-core/target/site/jacoco/index.html
```

See [testing-guide.md](testing-guide.md) for the test strategy.

### Documentation System

Run the project-documentation boundary, link, bilingual-structure, invariant, command, whitespace, and secret checks with:

```bash
./scripts/verify-project-docs.sh
```

## 3. Start And Health Check

One-command backend and frontend development:

```bash
./scripts/dev.sh
```

The launcher exports the complete repository-root `.env` to Maven / Spring Boot
and starts:

```text
Backend: http://127.0.0.1:8081
WebUI:   http://127.0.0.1:15173/webui/unlock
```

If neither `.env` nor the caller environment defines `RAG_ROOT_API_KEY`, the
launcher generates an ephemeral root credential for the current backend process.
On macOS it is copied to the clipboard and is never written to files or logs.
Status, stop, and port overrides:

```bash
./scripts/dev.sh --status
./scripts/dev.sh --stop
BACKEND_PORT=18082 FRONTEND_PORT=15174 ./scripts/dev.sh
RAG_DEV_OPEN_BROWSER=false ./scripts/dev.sh
```

Backend only:

```bash
bash scripts/start-server.sh
```

Manual start:

```bash
set -a
source .env
set +a
export SPRING_PROFILES_ACTIVE=postgresql
mvn spring-boot:run -pl spring-ai-rag-core -DskipTests
```

Port cleanup and health:

```bash
lsof -ti :8081 | xargs kill -9 2>/dev/null
curl -fsS http://127.0.0.1:8081/actuator/health
```

Swagger: `http://127.0.0.1:8081/swagger-ui.html`

## 4. Database

- PostgreSQL connection values come from `.env`.
- Required extension: `vector`.
- Recommended extension: `pg_trgm`; `pg_jieba` is optional.
- Migrations: `spring-ai-rag-core/src/main/resources/db/migration/`.

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

Prefer a Docker PostgreSQL image with the extensions installed. See [postgresql-extensions.md](postgresql-extensions.md).

## 5. Model Configuration

### Embedding

```text
Provider: SiliconFlow
Model: BAAI/bge-m3
Dimensions: 1024
Base URL: https://api.siliconflow.cn
```

### Chat Providers

| Provider | Configuration |
|----------|---------------|
| OpenAI-compatible | `spring.ai.openai.*` |
| Anthropic | `spring.ai.anthropic.*` |
| MiniMax | `spring.ai.minimax.*` |

Select the default provider with `LLM_PROVIDER` / `app.llm.provider`. See [multi-model-external-config.md](multi-model-external-config.md) for model instances and external configuration.

Store real credentials only in `.env`; never put them in shell examples, Markdown, or Git.

## 6. WebUI

```bash
cd spring-ai-rag-webui
npm ci
npm run lint
npm run test:run
npm run build
```

Development:

```bash
npm run dev
```

Direct use listens on `http://127.0.0.1:15173/webui/` and proxies `/api` to
`http://127.0.0.1:8081` by default. For normal full-stack development, run
`./scripts/dev.sh` from the repository root so the launcher keeps ports and the
proxy target aligned.

The release build embeds assets under:

```text
spring-ai-rag-core/src/main/resources/static/webui/
```

## 7. E2E

### HTTP E2E

```bash
bash scripts/start-server.sh
BASE_URL=http://127.0.0.1:8081 bash scripts/e2e-test.sh
```

### WebUI Playwright

```bash
cd spring-ai-rag-webui
npm run build
npx vite preview --host 127.0.0.1 --port 4173
BASE_URL=http://127.0.0.1:4173 npx playwright test
```

### Real LLM

```bash
./scripts/start-real-e2e-server.sh
BASE_URL=http://127.0.0.1:18081 ./scripts/real-llm-e2e-smoke.sh
```

The flow performs provider preflight, unique-document creation, embedding, search, ask, and stream. Mock Playwright is not a substitute for real-LLM validation.

## 8. Goldenset And Release Gates

Retrieval goldenset:

```bash
BASE_URL=http://127.0.0.1:8081 ./scripts/run-retrieval-goldenset.sh
```

One-command release verification:

```bash
./scripts/verify-release.sh
./scripts/verify-release.sh --with-local-runtime
```

Logs and summaries are written to `target/release-verification/<run-id>/`. See [release-checklist.md](release-checklist.md).

## 9. Docker And Mainland-China Networking

Preferred local build:

```bash
./scripts/docker-build-local.sh
```

Keep Dockerfile base images overridable instead of hard-coding regional mirrors. See [china-network-guide.md](china-network-guide.md) for DaoCloud, Aliyun Maven, npm, Playwright, and Git proxy guidance.

## 10. Key Paths

| Path | Purpose |
|------|---------|
| `spring-ai-rag-api/` | DTOs and SPIs |
| `spring-ai-rag-core/` | Core implementation and runnable app |
| `spring-ai-rag-starter/` | Auto-configuration |
| `spring-ai-rag-documents/` | Document processing |
| `spring-ai-rag-webui/` | React admin UI |
| `scripts/` | Startup, E2E, goldenset, documentation and release verification |
| `docker/` | Dockerfile and Compose |
| `k8s/` | Helm chart |

## 11. Troubleshooting

- General: [troubleshooting.md](troubleshooting.md)
- Configuration: [configuration.md](configuration.md)
- Mainland-China networking: [china-network-guide.md](china-network-guide.md)
- Claude Code + grok: [claude-grok-proxy.md](claude-grok-proxy.md)
