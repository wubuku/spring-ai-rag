# 下一批改进规划：Client SDK 接入、结构化证据与生产恢复验收

> [English](2026-10-16_CLIENT_SDK_EVIDENCE_READINESS_PLAN.md) | [中文](2026-10-16_CLIENT_SDK_EVIDENCE_READINESS_PLAN-zh-CN.md)
>
> 状态：实施检查 3/3 已通过，已验收，待 Git 交付
> 日期：2026-10-16

## 1. 目标与结论

本批次面向外部 Client API，不以 WebUI 通过作为完成标准，实施三项互相配套但边界清晰的能力：

1. **Client SDK 最小接入包**：让业务开发者可以脱离 WebUI 和内部 Java 类，直接使用 Python/Node 官方 SDK 或 curl 接入。
2. **OpenAI 兼容层结构化证据扩展**：在不破坏既有 OpenAI 响应的前提下，让客户端能够获取授权范围内的来源、引用注册表和确定性证据评估。
3. **生产恢复验收入口**：把数据库迁移/恢复、幂等账本、治理账本、证据数据和服务健康检查编排成可重复执行的脱敏验收门禁；不声称替代真实备份系统或云厂商恢复服务。

推荐一次性实施，但按以下顺序收敛：先冻结响应扩展和 SDK 示例，再加入自动化验收，最后执行全量回归。

## 2. 当前上下文

### 2.1 已有能力

- `/api/v1/rag/**` 是原生 Client API，已有 `sources`、`evidenceAssessment`、`traceId`、`metadata` 和 Native SSE。
- `/v1/models` 与 `/v1/chat/completions` 是默认关闭的受控 OpenAI 兼容入口；当前支持 text-only、`n=1`、JSON/SSE、Bearer/Header 认证和幂等重放。
- OpenAI 入口现已在不改变标准字段语义的前提下增量返回可选顶层 `rag`；RAG 来源和证据诊断仍以 Native API 为完整能力入口，OpenAI 兼容层只返回已授权且脱敏的来源投影。
- `ChatExecutionResult`、`ChatResponse`、`ChatEvent.SourcesAvailable` 已携带来源、证据评估和 citation registry，OpenAI Controller 只需做协议投影，不应重新执行检索。
- 官方 Python/Node SDK 黑盒门禁、Client Consumer Contract Kit、真实模型冒烟和 Starter-only Consumer 已有基础门禁。
- 当前最高 Flyway 版本为 V64；本批不改变 schema，因此不新增迁移。

### 2.2 关键约束

- 保留已有端点、HTTP method、旧请求字段、旧响应字段、旧 SSE 事件和旧错误语义。
- 新字段只增不改：OpenAI 响应新增顶层 `rag` 对象，旧客户端可忽略；Native API 不删除或重命名任何字段。
- 证据只来自当前请求已授权且最终实际提供给模型的来源；不得泄漏未入最终来源列表的候选、跨 Collection 内容、credential、prompt 或完整异常。
- `Idempotency-Key` 的 JSON 和快照 SSE 重放必须保留同一答案、sources、evidenceAssessment、citationRegistry 和 completion ID。
- 结构化证据不得触发额外 LLM、embedding、rerank 或数据库检索调用。
- 不使用显式悲观锁、`SKIP LOCKED` 或 advisory lock。
- 文档与测试必须覆盖外部 Client API；WebUI 只能作为补充。

## 3. 目标协议

### 3.1 非流式 OpenAI 响应

在现有 `chat.completion` 响应末尾增加可选的 `rag` 字段：

```json
{
  "id": "chatcmpl-rag-...",
  "object": "chat.completion",
  "created": 1760000000,
  "model": "rag-default",
  "choices": [],
  "usage": {},
  "rag": {
    "traceId": "...",
    "evidenceAssessment": {
      "status": "SUPPORTED",
      "retrievalExecuted": true,
      "sourceCount": 2,
      "citedSourceCount": 2,
      "invalidCitationIds": [],
      "contextLimited": false,
      "safeAnswerApplied": false,
      "reasonCode": "citation_supported"
    },
    "sources": [],
    "citationRegistry": {
      "version": 1,
      "handlePrefix": "c",
      "sourceCount": 2,
      "handles": ["c1", "c2"]
    }
  }
}
```

规则：

- `rag` 是增量字段；原有标准字段保持原类型和含义。
- `sources` 使用专用的 `OpenAiRagSource` 脱敏投影，只包含最终授权来源的身份、排序和文件级信息；明确不包含 `chunkText` 与任意 metadata。需要完整正文时使用 Native API。
- `evidenceAssessment` 使用已有稳定状态值；`PLAIN` 请求可省略 `rag`，或只返回 `NOT_APPLICABLE` 评估，不做虚假证据声明。
- `citationRegistry` 只投影最终 sources 中存在的 request-local handles，不暴露中间候选。
- `traceId` 与已有 `X-Trace-Id` 对齐；header 仍保留，不能要求客户端解析 JSON 才能追踪。
- 证据投影默认启用；仅在 OpenAI 兼容入口实际生成 RAG 证据时出现，不引入 legacy-only 默认路径。

### 3.2 流式 OpenAI 响应

- 保留现有 role chunk、content chunk、finish chunk 和 `[DONE]` 顺序。
- 在 finish chunk 的同一 JSON 对象增加 `rag` 字段；不新增非标准 SSE event 名称，不打乱旧 chunk 序列。
- 普通实时流的 `SourcesAvailable` 事件只用于在内存中准备最终扩展，不单独发送私有 chunk。
- keyed snapshot stream 在 finish chunk 增加同样的 `rag`；重放与首次完成完全一致。
- 上游失败仍使用现有 OpenAI error envelope 和 `[DONE]` 规则；不输出部分未授权来源。

### 3.3 SDK 接入包

新增中文/英文最小接入指南，以及可执行 Python/Node 示例。示例必须：

- 使用 `base_url=<service>/v1`，不把上游 `/v1` 误拼到 provider 配置；
- 展示完整 `messages`、model alias、`Authorization`、Collection scope；
- 展示 JSON、SSE 和 `Idempotency-Key`；
- 只打印安全摘要，不打印 key、完整 prompt、answer、source chunk 或异常正文；
- 明确 `/v1` 与 `/api/v1/rag/**` 的能力差异；需要完整原生 SSE 事件时使用 Native API；
- 通过固定 mock 服务可离线运行，真实服务通过环境变量显式启用。

## 4. 实施范围

### 4.1 API 与 Core

- 在 `spring-ai-rag-api` 增加 OpenAI RAG extension DTO，保留旧 record 构造器兼容性。
- 在 `OpenAiCompatibilityController` 中从 `ChatExecutionResult`/`ChatResponse` 投影 `rag`，不重复执行业务逻辑。
- 为普通 JSON、普通 SSE、keyed JSON replay、keyed snapshot SSE 分别补充断言。
- 让 `SourcesAvailable` 和 `Completed` 的顺序驱动流式证据快照；无来源或失败场景必须安全。

### 4.2 SDK 与验收脚本

- 新增 `docs/client-sdk-quickstart-zh-CN.md` 与英文对应文档。
- 新增 Python/Node 最小示例和自测/验收脚本；优先复用现有官方 SDK 依赖和 mock HTTP 服务。
- 将 SDK 接入门禁接入 `scripts/verify-release.sh` 的显式选项，默认发布门禁不调用真实 LLM。
- 新增恢复/迁移验收脚本：在临时 PostgreSQL 中从 V1 初始化到当前最高迁移，执行只读 schema、预算、治理与契约自测，并输出脱敏 manifest；服务 API 的 live 证据由 Client Contract/SDK 门禁负责，不由数据库恢复脚本伪造。
- 恢复门禁不伪造备份文件，不执行破坏性删除，不覆盖用户数据库；所有数据库使用临时隔离实例。

### 4.3 长青文档

- 更新 `docs/rest-api*` 的 OpenAI 响应和 SSE 章节。
- 更新 `docs/openai-compatibility-readiness*` 的当前事实和后续边界。
- 更新 `docs/client-contract-kit*`、`docs/developer-reference*`、`docs/testing-guide*`、`docs/release-checklist*`。
- 中英文文档保持结构和事实等价。

## 5. 非目标

- 本批不实现 OAuth/OIDC、tenant schema、billing 或供应商账单。
- 本批不开放 tools、structured output、sampling、多模态或 `EACH_COLLECTION`。
- 本批不改变 Native API 的请求/响应语义，不替换现有引用协议，不修改已执行 Flyway migration。
- 本批不把恢复验收脚本宣称为完整的云备份/灾备产品。

## 6. 测试与验收矩阵

### 6.1 规划完成前

完成规划文档后，执行至少三轮系统性检查；检查范围包括 OpenAI DTO/Controller、Chat event 顺序、幂等 replay、已有 Client Contract Kit、release gates、迁移版本和双语文档。发现实质问题立即修订文档并将计数重置为 0。

### 6.2 实施完成后

至少覆盖：

1. `spring-ai-rag-api` DTO 序列化、旧构造器和 null omission。
2. Core OpenAI JSON：PLAIN、KNOWLEDGE 有来源、无证据、安全回答、错误。
3. Core OpenAI SSE：role/content/finish/`rag`/`[DONE]`，UTF-8 和上游错误。
4. keyed JSON/SSE 精确重放及 `X-RAG-Idempotent-Replay`。
5. Native `/api/v1/rag/chat*` 旧字段与 Native SSE 不回归。
6. 官方 Python/Node SDK 黑盒门禁与 Client Consumer Contract Kit。
7. PostgreSQL/Flyway 从 V1 到当前版本的迁移与恢复验收。
8. Maven 全量、禁锁门禁、文档门禁、`git diff --check`。
9. `scripts/dev.sh` 隔离端口启动、健康检查、API 真实流程和停止。
10. 如 `.env` 中存在可用模型，额外执行脱敏真实 LLM JSON/SSE/证据/幂等验收；没有真实凭据时明确记录为未执行，不以 Mock 冒充。

### 6.3 兼容性硬门槛

- 既有 OpenAI 标准字段快照不变，只允许增加 `rag`。
- 旧客户端只读取 `choices[0].message.content` 时仍得到相同答案。
- 标准 SDK 的 JSON/SSE 解析不因额外字段失败。
- Native API 的旧端点、method、字段、SSE event name 和错误码保持兼容。
- 无证据、未授权来源、provider 失败和 replay 均不得泄漏敏感数据。

## 7. 交付顺序

1. 规划 3/3 检查并记录。
2. 增加 DTO、投影和单元/MockMvc 测试。
3. 增加 SDK 示例、指南和自测门禁。
4. 增加恢复/迁移验收入口。
5. 更新双语长青文档和 release gate。
6. 实施 3/3 检查。
7. 执行分层测试、真实服务验收和 `scripts/dev.sh` 验证。
8. 提交特性分支，合并 `origin/main` 后复验，合并到 `main`、push，并确认工作区干净。

## 8. 可恢复上下文

继续实施时先阅读本规划、对应 `*_PROGRESS`、`docs/rest-api-zh-CN.md` 的 OpenAI 章节、`docs/client-contract-kit-zh-CN.md`、`docs/delivery-workflow-zh-CN.md`，再从 `OpenAiCompatibilityController`、`OpenAiChatCompletionResponse`、`OpenAiChatCompletionChunk` 和现有 OpenAI Controller 测试进入。不得用 WebUI 通过替代外部 Client API 验收。
