# P1 / 1.0 就绪四项实施进度

> **Purpose**: 跟踪「可卖 / 可发 1.0」第一梯队四项，避免多轮失忆。
> **Prior P0**: `docs/drafts/2026-07-21_P0_IMPLEMENTATION_PROGRESS.md`（已完成）
> **Audit**: `docs/2026-07-21_CODE_AUDIT_REPORT.md`
> **Started**: 2026-07-21

## 用户硬性验证要求（每次停手前自检）

1. 后端：相关集成/单测覆盖本次改动；`mvn clean compile test-compile` 通过；服务可启动
2. 前端：`tsc`/生产构建无错；Vitest + 核心 Mock Playwright（有 UI 改动时）
3. **禁止未验证报喜**；信心来自测试而非仅 code review
4. 硬门槛全绿后，连续 **3** 轮实现检查无改动才交付
5. 关键进展先更新本文件，再做下一步
6. 真实 LLM 验证：`./scripts/start-real-e2e-server.sh` + `real-llm-e2e-smoke.sh`（涉及检索/chat 时）
7. 密钥不进仓库；commit 前扫描 staged diff

## 总览

| ID | 项 | 状态 | 验证 |
|----|----|------|------|
| P1-1 | 质量默认值与可证明增益 | ✅ completed | prod 默认、goldenset、MRR 确定性测试 |
| P1-2 | Collection ↔ API Key 最小 ACL | ✅ completed | 后端全数据面、WebUI、双语文档；183 项定向回归 |
| P1-3 | 运行时选模（真·多实例） | ✅ completed | 配置实例、严格路由、模型级 API、Settings/Chat 选模 |
| P1-4 | 1.0.0 发版收口 | ✅ completed | 版本、CHANGELOG、境内镜像、一键验证、静态 bundle、19/19 门禁 |

## 检查计数器

`convergence_checks = 3`

---

## P1-1 日志

- [x] `application-prod.yml`：query rewrite、heuristic rerank、0.55/0.45 检索权重
- [x] `testdata/goldenset/retrieval-goldenset.json`
- [x] `scripts/run-retrieval-goldenset.sh`：baseline/quality 两轮，输出 MRR/Precision/nDCG delta
- [x] POST Search 按 `RetrievalConfig.useRerank` 执行重排
- [x] `ReRankingServiceTest` 用生产权重证明确定性 MRR 提升
- [x] `docs/quality-defaults.md`

## P1-2 日志

- [x] Flyway V24 + API Key DTO/entity/service 持久化 `allowedCollectionIds`
- [x] Chat/Search/Collection/Document/PDF-to-RAG 数据面 ACL
- [x] restricted key 子 Key 委派不可越权；rotation 保留 ACL
- [x] Controller/security/service 定向测试
- [x] WebUI 创建 Key 时选择 Collection，列表展示范围
- [x] REST API / configuration 双语文档
- [x] `collectionsApi.list` 将 WebUI `page/size` 转为后端 `offset/limit`
- [x] 广覆盖回归：183 tests，0 failure，0 error

## P1-3 日志

- [x] 配置驱动、按 `provider/modelId` 缓存的 ChatModel 实例工厂
- [x] OpenAI-compatible / Anthropic API 类型按 provider 配置创建真实实例
- [x] 显式非法/不可用模型返回 400；未指定模型保留兼容 fallback
- [x] `/rag/models` 返回可选模型级引用、可用状态与默认模型
- [x] 模型对比按模型引用路由
- [x] 外部 JSON 真正全覆盖 YAML，并支持绝对路径 / `file:` URI
- [x] Settings/Chat 模型选择、持久化与 SSE 请求透传
- [x] Axios 自动携带本地 `X-API-Key`
- [x] 修复 Playwright 共享 mock 的 `continue()` 抢占假阳性
- [x] 后端定向 78 tests、WebUI 定向 29 tests、构建、Playwright 16 tests

## P1-4 日志

- [x] 全 Maven 模块与独立 Demo 版本切换为 `1.0.0`
- [x] OpenAPI / Helm appVersion / Docker 与 Helm 默认镜像 tag 切换为 `1.0.0`
- [x] Dockerfile 修复非 reactor Demo 构建、distroless `sh`/`nc` 启动问题
- [x] Docker Compose / Helm / 本地默认端口统一为 `8081`
- [x] Helm ConfigMap 实际挂载，并改为有效的 `rag.*` 质量默认配置
- [x] README / getting-started / deployment / troubleshooting 端口与 base-url 纠偏
- [x] Flyway V24 / 运行时选模 / external models.json 双语文档
- [x] CHANGELOG 与双语 release checklist
- [x] WebUI 生产构建复制到 core static
- [x] `scripts/verify-release.sh` 固化 Maven/WebUI/Playwright/Helm/Docker 与可选在线验证，逐项日志写入 `target/release-verification/`
- [x] Dockerfile 基础镜像可覆盖；本地脚本默认境内镜像、官方源回退、amd64/arm64 可选
- [x] 中国境内 Docker/Maven/npm/Playwright/代理避坑指南与常用入口交叉链接
- [x] 修复 `.gitignore` 忽略 WebUI hash 资源的问题；发布门禁校验入口引用、文件存在与 Git 可跟踪性
- [x] 真实 LLM smoke 按 SSE `data:` JSON 分片拼接 `delta.content`，避免校验码跨分片时产生假阴性
- [x] 最终完整门禁归档：`target/release-verification/20260721-release-complete/summary.md`

## 最终 1.0 门禁证据（2026-07-21）

- 一键命令：`VERIFY_RUN_ID=20260721-release-complete VERIFY_GENERATED_AT='2026-07-21 (Asia/Shanghai)' ./scripts/verify-release.sh --with-local-runtime`
- 总结果：19 passed、0 failed、0 skipped
- Maven：API 530 + Documents 74 + Core 2557 + Starter 52 = 3213 tests
- WebUI：lint、153 Vitest、生产构建、41 个内嵌资源、37 Playwright
- 部署：Helm lint/template；DaoCloud + 阿里云 Maven mirror 构建 `linux/arm64` 非 root 镜像
- 运行时：PostgreSQL profile 启动；HTTP E2E 66/66；退出后 `18081` 无监听
- Goldenset：baseline/quality 均为 MRR 1.0、Precision@5 0.24、nDCG 1.0，输出 `GOLDENSET_OK`
- 真实模型：MiniMax-M3 + SiliconFlow BGE-M3，隔离文档的 ask/stream 与自动清理共 10/10
- 缺陷闭环：首轮 18/19 定位为 code 跨 SSE JSON 分片导致的脚本假阴性；按分片解析并拼接后最终 19/19

## 验证命令记录

- `mvn -pl spring-ai-rag-core -am clean test -Dtest=PdfImportControllerTest,ReRankingServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
  - 2026-07-21：53 tests，0 failure，0 error
- `mvn -pl spring-ai-rag-core -am -Dtest=ApiKeyCollectionAccessTest,CollectionAclControllerTest,DocumentAclControllerTest,PdfImportControllerTest,RagSearchControllerTest,RagChatControllerTest,ApiKeyManagementServiceTest,ApiKeyControllerTest,GlobalExceptionHandlerTest,ReRankingServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 2026-07-21：183 tests，0 failure，0 error
- `npm run build`
  - 2026-07-21：通过
- `npm run test:run -- src/pages/ApiKeys.test.tsx`
  - 2026-07-21：7 tests 通过
- `mvn -pl spring-ai-rag-core -am -Dtest=ChatModelRouterTest,ConfiguredChatModelFactoryTest,MultiModelConfigLoaderTest,ModelControllerTest,ModelComparisonServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 2026-07-21：78 tests，0 failure，0 error
- `npm run test:run -- src/hooks/useSSE.test.ts src/pages/Chat.test.tsx src/pages/Settings.test.tsx src/pages/ApiKeys.test.tsx`
  - 2026-07-21：29 tests 通过
- `BASE_URL=http://127.0.0.1:4174 npx playwright test e2e/chat.spec.ts e2e/pages.spec.ts --project=chromium`
  - 2026-07-21：16 tests 通过
- `mvn clean compile test-compile`
  - 2026-07-21：全部 5 个 reactor 模块通过（版本 `1.0.0`）
- `helm lint ./k8s` + `helm template ...`
  - 2026-07-21：Chart lint 通过；渲染确认 image `1.0.0`、端口 `8081`、ConfigMap 挂载与质量默认
- external models JSON 文档解析 + 发布残留扫描 + `git diff --check`
  - 2026-07-21：中英文 JSON 示例有效；无活动态 SNAPSHOT/8080/V23/base-url `/v1` 残留；diff check 通过
- `mvn -pl spring-ai-rag-core -am -Dtest=ApiVersionRequestMappingHandlerMappingTest,OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 2026-07-21：28 tests，0 failure，0 error；修复版本映射复制 params/headers 时的 `ArrayStoreException`
- `mvn clean test`
  - 2026-07-21：API 530 + Documents 74 + Core 2555 + Starter 52 = 3211 tests，0 failure，0 error
- `VERIFY_RUN_ID=20260721-release-complete VERIFY_GENERATED_AT='2026-07-21 (Asia/Shanghai)' ./scripts/verify-release.sh --with-local-runtime`
  - 2026-07-21：19 passed、0 failed、0 skipped；Maven 3213、Vitest 153、Playwright 37、HTTP E2E 66/66、goldenset、真实 LLM 10/10
- 连续三轮 staged 无改动收敛检查
  - 2026-07-21：3/3 通过；三轮 Git index tree 均为 `3209de5e92a4422c20799c4d063c380fd13bd3ac`
  - 每轮覆盖 staged `git diff --check`、全部 Shell 语法、版本/端口/Flyway/base-url、密钥、中英文文档配对和 WebUI bundle
