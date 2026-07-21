# P0 五项实施进度

> **Purpose**: 跟踪 P0 高价值缺口落地，避免多轮操作失忆。  
> **Plan**: 会话计划 / `docs/2026-07-21_CODE_AUDIT_REPORT.md`  
> **Started**: 2026-07-21  
> **Updated**: 2026-07-21

## 用户硬性验证要求（每次停手前自检）

1. 后端：相关集成/单测覆盖本次改动；`mvn clean compile test-compile` 通过；服务可启动  
2. 前端：`tsc`/生产构建无错；Vitest + 核心 Mock Playwright  
3. **禁止未验证报喜**；信心来自测试而非仅 code review  
4. 硬门槛全绿后，连续 **3** 轮实现检查无改动才交付  
5. 关键进展先更新本文件，再做下一步  

## 总览

| ID | 项 | 状态 | 验证 |
|----|----|------|------|
| P0-1 | 对话侧 Collection 隔离 | ✅ | 后端单测 + FE build |
| P0-2 | 跨模型 Fallback 接到 Chat | ✅ | RagChatServiceTest / ChatModelRouterTest |
| P0-3 | 专业 Rerank SPI | ✅ | ReRanking + Http + Factory + Advisor tests |
| P0-4 | 生产安全默认 + filter 修复 | ✅ | ApiKeyAuthFilterTest 16 |
| P0-5 | 评估/反馈 WebUI | ✅ | Evaluation.test + Chat.test + build |

## 检查计数器

`convergence_checks = 3`（连续三轮无代码改动）

| 轮次 | 范围 | 结果 |
|------|------|------|
| #1 | 空集合隔离、filter 门闩、fallback 顺序、SPI 默认、路由 | 无实质问题 |
| #2 | stream 与 ask 参数对齐、prod profile、DB key 鉴权 | 无实质问题 |
| #3 | 前端 build/vitest 绿、评估 API 路径、文档进度 | 无实质问题 |

Playwright `pages.spec.ts` 需本机 8081 WebUI 服务；本环境 connection refused，已用 **Vitest + production build + e2e mock 路由** 作为前端硬门槛（与计划一致）。

---

## 验证命令记录

```text
# 后端
mvn -pl spring-ai-rag-api,spring-ai-rag-core,spring-ai-rag-starter -am clean compile test-compile -DskipTests
# → BUILD SUCCESS

mvn -pl spring-ai-rag-core -Dtest=CollectionDocumentResolverTest,HybridSearchAdvisorTest,RagSearchControllerTest,RagChatServiceTest,ChatMemoryMultiTurnTest,RagChatControllerTest,ApiKeyAuthFilterTest,ReRankingServiceTest,HttpRerankProviderTest,RerankProviderFactoryTest,RerankAdvisorTest,ChatModelRouterTest test
# → Tests run: 162, Failures: 0, Errors: 0

# 前端
cd spring-ai-rag-webui && npm run build
# → ✓ built

npx vitest run src/pages/Evaluation.test.tsx src/pages/Chat.test.tsx
# → 11 passed

# Playwright 全量（Mock API + vite preview，不依赖 Spring 8081）
npx vite preview --host 127.0.0.1 --port 4173 &
BASE_URL=http://127.0.0.1:4173 npx playwright test
# → 35 passed (2026-07-21)
# 修复：await mockAllApiCalls；streaming-upload 去掉硬编码 8081；
# navigation 含 Evaluation + /webui 前缀；api-mocks 响应体去多余 data 包装；
# Settings/Documents/Search/Dashboard 选择器对齐当前 i18n UI
```

## P0-1 日志

- [x] ChatRequest.collectionIds + documentIds  
- [x] CollectionDocumentResolver（Search 共用）  
- [x] HybridSearchAdvisor 读 documentIds/maxResults/filterRequested  
- [x] RagChatService chat/stream 完整 request + RetrievalScope  
- [x] 空集合不泄全库  
- [x] WebUI 集合选择器 + SSE body `collectionIds`  
- [x] 测试绿  

## P0-2 日志

- [x] ChatModelRouter.orderedCandidates  
- [x] executeChat 外层 fallback 循环 + 内层 invokeWithRetry  
- [x] CB 在循环前检查；OPEN 不跨模型硬撑  
- [x] 测试更新  

## P0-3 日志

- [x] RerankProvider SPI：heuristic / http / none  
- [x] ReRankingService facade  
- [x] application.yml 配置扩展  
- [x] 测试  

## P0-4 日志

- [x] ApiKeyAuthFilter：enabled 时即使 static key 空白也强制鉴权；DB key 可用  
- [x] application-prod.yml  
- [x] docker-compose SPRING_PROFILES_ACTIVE=postgresql,prod  
- [x] 配置文档中文表更新  
- [x] 测试  

## P0-5 日志

- [x] evaluationApi + Evaluation 页 + 路由/导航/i18n  
- [x] Chat 👍👎 → POST feedback  
- [x] e2e api-mocks 覆盖 evaluation  
- [x] Vitest  

## 实现要点（防失忆）

| 路径 | 说明 |
|------|------|
| `ChatRequest` | `collectionIds` / `documentIds` |
| `CollectionDocumentResolver` | Search + Chat 共用解析 |
| `HybridSearchAdvisor` | 空 filter → 空结果 |
| `RagChatService` | RetrievalScope + fallback 循环 + chatStream(ChatRequest) |
| `ApiKeyAuthFilter` | `if (!authEnabled)` only pass-through |
| `application-prod.yml` | security + circuit breaker on |
| `retrieval/rerank/*` | SPI |
| `webui/.../Evaluation.tsx` | 评估页 |


## Real LLM E2E (repeatable)

### Scripts
- `scripts/start-real-e2e-server.sh` — start on **:18081** (avoids 8080/8081 conflicts), loads `.env`, strips `/v1`
- `scripts/real-llm-e2e-smoke.sh` — **real** embed + search + chat/ask (+ stream); fails closed if keys invalid

### Startup fixes discovered while bringing server up
1. `logback-spring.xml` — `postgresql` profile had **no root appender** → silent death; now includes `postgresql,local`
2. Dual `EmbeddingModel` — MiniMax auto-config + SiliconFlow; fixed via exclude + `@Qualifier("embeddingModel")` for cache wrapper
3. `ReRankingService` — mark Spring ctor `@Autowired` (two ctors)

### 2026-07-21 real LLM run result
- Server **UP** on `http://127.0.0.1:18081` after fixes
- Preflight embedding/chat against SiliconFlow: **HTTP 401 Token is invalid** for `.env` keys
  - `SILICONFLOW_API_KEY` / `SPRING_AI_OPENAI_API_KEY` both rejected
- Therefore create/embed/search/chat cannot complete until user refreshes keys in `.env`
- After keys work: `./scripts/start-real-e2e-server.sh && BASE_URL=http://127.0.0.1:18081 ./scripts/real-llm-e2e-smoke.sh`

### How we ALWAYS do real LLM E2E (remember)
1. Prefer dedicated port (default **18081**)
2. Start with explicit `-Dspring.ai.openai.*` / `-Drag.embedding.*` (do not rely on env inheritance alone)
3. **Preflight keys** with raw HTTPS to provider before claiming RAG works
4. Pipeline: create doc with unique token → embed → search hit → chat must quote token
5. Never treat Mock Playwright as real-LLM verification

### Key probe matrix (2026-07-21, no secrets printed)
| Source | Provider | Result |
|--------|----------|--------|
| `.env` SiliconFlow embed/chat | siliconflow.cn | **401 Token is invalid** |
| `.env.deepspeed` SiliconFlow | siliconflow.cn | **401** |
| `.env.deepspeed` DeepSeek chat | api.deepseek.com | **402 Insufficient Balance** |
| `.env` MiniMax chat | api.minimaxi.com | **429 rate/quota limit** (key accepted but plan exhausted) |

**Blocked on valid embedding + chat credits.** Server itself is healthy on :18081 after startup fixes.


### 2026-07-21 evening — REAL LLM E2E PASSED (MiniMax-M3 + SiliconFlow)

**Server**: `http://127.0.0.1:18081`  
**Chat**: `app.llm.provider=minimax`, model `MiniMax-M3`, key `SPRING_AI_MINIMAX_API_KEY` (pay-as-you-go)  
**Embed**: SiliconFlow `BAAI/bge-m3` via `SILICONFLOW_API_KEY`  
**Note**: do **not** send OpenAI-style `dimensions` to SiliconFlow (causes HTTP 400 code 20015).

| Step | Result |
|------|--------|
| health | UP |
| create doc 125 | OK |
| embed | COMPLETED embeddingsStored=1 |
| search REAL_E2E_* | hits≥1, top doc 125 |
| chat/ask | answer contains `REAL_E2E_1784632501_5300` (TOKEN_IN_ANSWER True), sources=5 |
| chat/stream | SSE chunks with token + `event:done` |

**Corrections vs earlier misdiagnosis**:
1. `.env` did **not** have `OPENAI_API_KEY`; MiniMax pay key is `SPRING_AI_MINIMAX_API_KEY` / `ANTHROPIC_API_KEY`.
2. `SPRING_AI_OPENAI_API_KEY` was an **invalid** SiliconFlow chat key — not the MiniMax key.
3. Embed failures after key fix were **dimensions parameter**, not auth.

**Repeat recipe**:
```bash
# MiniMax chat + SF embed on :18081
export $(grep -v '^#' .env | grep -v '^$' | xargs)
# start with: app.llm.provider=minimax + spring.ai.minimax.* + rag.embedding.* = SILICONFLOW
./scripts/start-real-e2e-server.sh   # or java -cp with -Dapp.llm.provider=minimax ...
# manual pipeline or improved smoke after dimensions fix
```


### Scripts default stack (updated)

`start-real-e2e-server.sh` defaults:
- **Port** `18081`
- **Chat** `LLM_PROVIDER=minimax` → `SPRING_AI_MINIMAX_*` / MiniMax-M3
- **Embed** SiliconFlow `SILICONFLOW_API_KEY` + `BAAI/bge-m3`
- Alternates: `LLM_PROVIDER=anthropic` | `openai`

Full smoke after script update:
```text
./scripts/start-real-e2e-server.sh
# Creating MiniMax ChatModel ... MiniMax-M3 / Using MiniMax ChatModel as primary
BASE_URL=http://127.0.0.1:18081 ./scripts/real-llm-e2e-smoke.sh
# PASS=8 FAIL=0 probe=REAL_E2E_1784636883_12101
# embed COMPLETED, search hit, ask TOKEN_IN_ANSWER True, stream TOKEN_IN_STREAM True
```

