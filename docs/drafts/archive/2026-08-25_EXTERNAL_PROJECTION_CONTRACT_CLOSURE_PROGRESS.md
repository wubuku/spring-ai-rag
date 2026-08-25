# 外部投影生产合同闭环 P0 实施进度

> **状态**：实现与合并前验收完成，连续实现审查 `3/3`，待最终 Git 交付
>
> **日期**：2026-08-25
>
> **规划**：[2026-08-25_EXTERNAL_PROJECTION_CONTRACT_CLOSURE_PLAN.md](2026-08-25_EXTERNAL_PROJECTION_CONTRACT_CLOSURE_PLAN.md)
>
> **实施分支**：`feat/external-projection-contract-closure-20260825`
>
> **实施基线**：`main == origin/main` @ `17f098f4`
>
> **实施工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-external-projection-contract-closure`

本文记录恢复任务需要的项目内上下文、检查计数、验证证据和下一步。不记录 raw credential、
完整业务 payload、外部项目路径或外部项目背景。

## 1. 当前结论

- 上一轮业务服务接入就绪能力已合并并推送 `main`，对应归档账本状态为已完成。
- 当前活动规划只处理通用 RAG 外部投影合同的剩余 P0 缺口，不重复 `/auth/me` 实现。
- 本轮冻结范围：
  - lookup/tombstone 边界校验；
  - 全数据面跨 Collection 真实 HTTP deny；
  - identity/revision 长度与非法 Collection key 边界；
  - clean Git commit/版本/V48/合同计数的 release manifest；
  - 部署、健康、迁移和验收文档纠偏。
- 不改 schema，不新增 Flyway；目标仍为 V48。
- 不改变 Chat/LLM；真实 Chat LLM 测试不适用，真实 Spring AI embedding HTTP path 继续使用
  确定性 stub。

## 2. 清单

- [x] 探索当前 JSON Record controller/service/DTO、ACL、合同脚本和部署文档。
- [x] 编写自包含实施规划。
- [x] 规划连续 `3/3` 无修改审查。
- [x] 规划文档门禁、commit 和 push，`main` 恢复干净（`17f098f4`）。
- [x] 基于最新 `origin/main` 创建专用分支和隔离 worktree。
- [x] Slice A：边界校验与 focused/OpenAPI 测试。
- [x] Slice B：扩展双 principal、全数据面 ACL 和字段边界真实合同。
- [x] Slice C：release manifest 与 final clean gate。
- [x] Slice D：双语长青文档与部署文档。
- [x] 合并前完整 readiness 与实现 `3/3`。
- [ ] 同步 `origin/main` 后完整复验与 Git 交付。
- [ ] 合并并推送 `main`，清理隔离 worktree。

## 3. 规划检查账本

| 时间 | 计数 | 范围 | 结果 | 处理 |
|---|---:|---|---|---|
| 2026-08-25 | 0/3 | 初稿 | 待检查 | 规划正文已建立 |
| 2026-08-25 | 0/3 | 第 1 轮：P0 闭环/自包含/兼容 | 发现实质问题 | 冻结 API version 取自真实 `/v3/api-docs`；增加 provider 失败后 Record 保留合同；修正 manifest 失败场景；计数保持 0 |
| 2026-08-25 | 0/3 | 重启第 1 轮：兼容校验 | 发现实质问题 | raw `@Pattern` 会破坏现有 trim 兼容；改为共享 `@ValidSourceNamespace`，OpenAPI 限制显式声明；计数保持 0 |
| 2026-08-25 | 0/3 | 重启第 1 轮：失败注入可实施性 | 发现实质问题 | 冻结 stub `--fail-marker`、测试环境变量和 job default/max attempts=1；计数保持 0 |
| 2026-08-25 | 0/3 | 第 2 轮：API/ACL/脚本可行性 | 发现实质问题 | 失败 fixture 若写 A 会破坏精确 readiness 断言；改为 B principal/Collection B，并复用为双向越权真实目标；计数重置为 0 |
| 2026-08-25 | 0/3 | 重启前：fixture 顺序 | 发现实质问题 | 合法 boundary Record 固定排在 A 最终 freshness 之后，避免 `SKIP` Record 污染精确计数；计数保持 0 |
| 2026-08-25 | 0/3 | 重启第 1 轮：Git/证据可复现性 | 发现实质问题 | 原顺序在 clean readiness 后归档草稿，会让 manifest SHA 落后于最终提交；改为先归档并提交最终特性 tip，再跑 clean readiness，合入后在最终 `main` merge commit 上再次完整验证；计数保持 0 |
| 2026-08-25 | 3/3 | 三轮固定范围审查 | 连续三轮无实质问题 | 第 1 轮核对 P0 闭环/自包含/兼容，第 2 轮核对 API/ACL/失败注入/manifest，第 3 轮核对切片/验收/回滚/Git 交付；期间未修改规划正文 |

## 4. 下一步

- 归档规划/进度并提交最终特性候选；
- fetch/merge 最新 `origin/main`，按合并后候选基线完整复验；
- 在 clean 的 feature tip 和最终 `main` merge commit 上分别执行完整 readiness；
- 推送 feature/main，确认干净并安全移除隔离 worktree。

## 5. 实施与验证证据

- Slice A focused：
  `RagJsonRecordControllerWebTest`、`JsonRecordServiceTest`、
  `OpenApiContractTest`、`CollectionKeyValidatorTest`、
  `SourceNamespaceValidatorTest` 共 61 tests，failures/errors/skipped 均为 0。
- 2026-08-25：已重新核对特性 worktree、规划冻结合同和现有 readiness/HTTP 脚本；
  工作区只有本任务 Slice A 修改，开始 Slice B/C。
- 2026-08-25：Slice B/C 主体代码已完成，等待真实服务验证：
  - embedding stub 的成功请求为 200、marker 失败请求为 503，counter 同时记录 requests、
    inputs 和 failedRequests；
  - readiness clean gate 在 dirty tree 上按预期失败，并生成 `result=FAIL` 的 manifest；
    未到达的 API version 与 HTTP contract checks 均为 JSON `null`；
  - Shell/Python 静态编译与 `git diff --check` 通过。
- 2026-08-25：第一轮 `VERIFY_PHASE=real` 在 provider 失败终态等待处失败。原因是 Spring AI
  1.1.8 的模型级 `spring.ai.retry.max-attempts` 默认值为 10，内部退避约 100 秒，超过合同
  90 秒等待预算；业务 Record 未被删除。测试服务已显式设置
  `SPRING_AI_RETRY_MAX_ATTEMPTS=1` 后重跑，但进一步确认当前 `EmbeddingModelConfig` 手动构造
  `OpenAiEmbeddingModel`，不会接收 Spring AI 自动配置的 RetryTemplate。修正方案是新增
  `rag.embedding.retry-max-attempts` 显式预算；默认 10 保持现有生产行为，验收环境设为 1，
  并增加配置单测证明实际模型使用该预算。
- 2026-08-25：增加 `EmbeddingModelConfigTest` 后，Slice A 与 retry budget 聚焦集合共
  66 tests，failures/errors/skipped 均为 0；开始第三次 `VERIFY_PHASE=real`。
- 2026-08-25：第三次真实阶段证明单次 retry budget 生效，provider 失败在预算内进入
  `FAILED`，且 Record 的 identity/revision/payload/enabled/documentRevision 均保持。随后
  anti-enumeration helper 把短数字 document ID `2` 的任意字符出现误判为泄漏；已收紧为只
  匹配结构化 ID 字段或 `document/record id` 语境，准备重跑。
- 2026-08-25：第四次真实阶段通过 Collection B 的 search/lookup/upsert/tombstone 403 与
  防枚举检查，随后在未知 Collection 无附加敏感 ID 的调用上触发 Bash 3.2 空数组
  `set -u` 兼容错误。已改为直接转发 `"$@"`；同时为合同 EXIT trap 显式保存原退出码，避免
  cleanup 掩盖失败后继续读取不存在的 summary。
- 2026-08-25：第五次 `VERIFY_PHASE=real` 完整通过：
  - 109 项真实 HTTP 合同；
  - Flyway V48、明文 credential 为零、成功 embedding job 的 PostgreSQL 事实；
  - provider 503 失败后 Record 保持与 failedRequests 计数；
  - 真实 API Key Playwright 1/1；
  - PASS manifest 记录 API `1.0.0`、V48、109 checks、完整 SHA 和 DIRTY 开发态。
  Slice B/C 完成，进入双语长青文档更新。
- 2026-08-25：Slice D 完成：
  - REST 文档固化 lookup/tombstone 参数边界、通用 `403` 防枚举与 provider 失败后 Record
    保留合同；
  - 配置文档和样例增加 `rag.embedding.retry-max-attempts` /
    `RAG_EMBEDDING_RETRY_MAX_ATTEMPTS`，默认 10、范围 1–10，并明确其独立于 job 尝试预算；
  - 测试/开发者参考/发布清单记录 109 项合同、clean-tree gate 和 release manifest；
  - 部署指南更新为 V48，并区分 liveness/readiness 与 Collection 派生就绪。
- 2026-08-25：合并前静态门禁通过：
  - `verify-project-docs.sh` 10/10；
  - `verify-no-pessimistic-locks.sh` 无生产代码违规；
  - `git diff --check` 与 added-line secret scan 通过。
  开始执行完整 dirty-tree readiness，作为实现收敛审查前的硬门槛。
- 2026-08-25：合并前完整 readiness 通过，证据目录
  `.verification/business-client-readiness/20260825-full-premerge/`：
  - focused API/Core 123 tests、三组 PostgreSQL 集成矩阵、Flyway V48 和
    `mvn clean compile test-compile` 全部通过；
  - WebUI typecheck、218 项 Vitest、生产构建、核心 Mock Playwright 1/1 通过；
  - 真实 Spring Boot + Spring AI embedding HTTP、109 项业务合同、数据库只读事实和真实
    API Key Playwright 1/1 通过；
  - 16 个 readiness steps 全部 PASS，DIRTY 开发态 manifest schema/facts 验证通过。
  基本集成硬门槛完成，开始三轮限定范围只读实现收敛。
- 2026-08-25：实现收敛第 1、2 轮未发现实质问题；第 3 轮发现非默认
  `retry-max-attempts` 使用 `SimpleRetryPolicy`，会把 Spring AI 的 transient-only
  分类和指数退避退化为“所有异常立即重试”。已改为保留
  `TransientAiException`/`ResourceAccessException` 分类和 2 秒起、5 倍、3 分钟上限的
  默认指数退避，只改变最大尝试次数；增加测试证明 transient 错误按预算重试、永久错误
  不重试。实现审查计数重置为 0，先重跑受影响门槛和完整 readiness。
- 2026-08-25：重试语义修复后的 focused 验证通过：
  `EmbeddingModelConfigTest` 6 tests、0 failures/errors/skips；项目文档 10/10、Shell/Python
  语法与 `git diff --check` 通过。开始重新执行完整 dirty-tree readiness。
- 2026-08-25：重试语义修复后的完整 dirty-tree readiness 再次通过，证据目录
  `.verification/business-client-readiness/20260825-full-premerge-after-retry-fix/`：
  - focused API 5 tests、Core 119 tests，三组 PostgreSQL 集成矩阵与 Flyway V48 通过；
  - `mvn clean compile test-compile`、WebUI typecheck、218 项 Vitest、生产构建和核心
    Mock Playwright 1/1 通过；
  - 真实 Spring Boot、109 项 HTTP 合同、数据库事实和真实 API Key Playwright 1/1 通过；
  - 16 个 readiness steps 全部 PASS，manifest 为 `result=PASS`、DIRTY 开发态。
- 2026-08-25：实现收敛重新从 0 开始并连续完成三轮只读检查：
  1. 生产代码与公开契约：DTO/共享校验、controller/service 防御性校验、OpenAPI 与
     embedding retry 分类/退避/默认兼容，无实质问题；
  2. 验收与失败注入：109 项 HTTP 合同、双向 ACL、防枚举、provider 503、数据库事实、
     cleanup 和 PASS/FAIL manifest，无实质问题；
  3. 长青文档与交付证据：双语配置/API/接入/测试/发布文档与实现一致，文档 10/10、
     禁锁、diff 和密钥扫描通过，无实质问题。
  三轮期间未修改实现、测试或文档，实现审查达到 `3/3`。
