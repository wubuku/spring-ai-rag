# 下一批改进实施进度：Client SDK、结构化证据与生产恢复验收

> [English](2026-10-16_CLIENT_SDK_EVIDENCE_READINESS_PROGRESS.md) | [中文](2026-10-16_CLIENT_SDK_EVIDENCE_READINESS_PROGRESS-zh-CN.md)

## 状态

- 分支：`feat/client-sdk-evidence-readiness`
- 基线：`03943a68`，创建分支前工作区干净且与 `origin/main` 同步
- 当前阶段：规划检查已 3/3 通过；代码、门禁、双语长青文档、全量回归、外部 Client 验收、真实模型验收和实施检查均已 3/3 完成，准备 Git 交付
- 数据库迁移：本批预计不新增迁移

## 计划检查记录

| 轮次 | 时间 | 范围 | 发现问题 | 处理与结果 |
|---|---|---|---|---|
| 预重置 1 | 2026-10-16 16:15 +0800 | OpenAI DTO/Controller、Chat event 顺序、幂等 replay、迁移版本 | 无 | 现有 `ChatExecutionResult`、`ChatResponse`、`SourcesAvailable → Completed` 和 V64 事实支持增量投影与无迁移实施；通过 |
| 触发重置 | 2026-10-16 16:15 +0800 | 双语规划、活跃规划导航、兼容表述、现有 release/test 入口 | 发现活跃规划导航缺少本批条目 | 立即补充 `docs/drafts/README*` 中英文条目，计数重置为 0 |
| 重置后 1 | 2026-10-16 16:15 +0800 | 双语规划、活跃规划导航、兼容表述、现有 release/test 入口 | 无 | 通过 |
| 重置后 2 | 2026-10-16 16:15 +0800 | Java record 构造兼容、Testcontainers/Flyway 模式、SDK 门禁复用、未决项 | 无 | 通过 |
| 重置后 3 | 2026-10-16 16:15 +0800 | 规划文件完整性、链接、代码事实、迁移上限和验收闭环交叉核对 | 无 | 连续三轮无修改，规划检查最终 `3/3` 通过 |

## 实施记录

- 已新增 `OpenAiRagExtension`、`OpenAiRagSource`，并在 OpenAI 非流式 JSON、普通 SSE finish
  chunk、keyed JSON replay 和 snapshot SSE replay 中增量投影 `rag`；旧 Java record 构造器保留。
- `rag.sources` 通过专用 DTO 脱敏，不输出 `chunkText` 和任意 metadata；投影复用已有最终执行结果，
  不重新检索、不重复调用模型。
- 已新增 Python/Node SDK 示例、`docs/client-sdk-quickstart*` 和
  `scripts/verify-client-sdk-quickstart.sh`；默认离线，`CLIENT_SDK_LIVE=1` 才进行真实 HTTP。
- 已新增 `scripts/verify-recovery-readiness.sh`；它使用一次性 PostgreSQL，运行 V1 到源码当前最高
  迁移，并把数据库实际 `flyway_schema_history` 与源码清单对账；不触碰调用方数据库，也不宣称是备份/灾备。
- 已将 `--with-client-sdk`、`--with-recovery-readiness` 接入 `scripts/verify-release.sh`；默认发布流程
  不额外调用模型或恢复门禁。
- 已同步 REST API、OpenAI readiness、Client Contract Kit、developer reference、testing guide、
  release checklist、index 的中英文说明。

## 测试记录

已通过/已执行：

```text
mvn clean test（全 reactor 通过；既有 External Starter Consumer 条件测试因未显式启用而 skipped）
./scripts/verify-gated-it.sh（完整 PostgreSQL/Testcontainers 门禁通过，含 V1→V64 Flyway、预算/治理、文档处理、恢复和 Client 集成套件）
OpenAiRagExtensionTest；OpenAiCompatibilityEventMappingTailTest：9/9
./scripts/verify-client-consumer-contract.sh
./scripts/verify-client-sdk-quickstart.sh --self-test
./scripts/verify-openai-compatibility.sh --self-test
./scripts/verify-recovery-readiness.sh（一次性 PostgreSQL、Flyway、预算/治理 PostgreSQL 集成测试）
./scripts/verify-project-docs.sh；git diff --check；./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-openai-sdk-e2e.sh --self-test（Runner 20 个正/负向变异全部通过）
scripts/dev.sh（隔离端口启动、readiness、auth、capabilities、collections、Vite proxy、停止与端口清理）
真实 MiniMax/Embedding 冒烟（provider preflight、隔离 Collection/Document、真实 embedding、限定搜索、KNOWLEDGE 问答、引用、Native SSE、证据评估通过）
./scripts/verify-release.sh --skip-docker --with-openai-sdk --with-client-sdk --with-recovery-readiness（22 passed、0 failed、7 skipped）
```

官方 OpenAI SDK 的 Mock HTTP E2E 只证明协议适配和失败变异，不替代真实模型质量证据；真实模型验收已单独执行。
默认开发 `.env` 未启用 OpenAI 兼容入口，因此 `scripts/dev.sh` 验收记录该端点为按配置关闭，不能据此替代独立的 OpenAI SDK 门禁。
AGENT 引用注册表验收因真实 MiniMax 提供商未声明 AGENT capability 而按能力门控跳过，不伪造通过。
发布组合门禁中的 7 个 skipped 是显式关闭的 Docker、runtime E2E、goldenset、质量回归、real-LLM 和 next-high-value 选项，不将它们解释为通过；real-LLM 已由独立冒烟执行。
所有结果均区分 Mock/离线证据与真实模型证据，且未把 WebUI 通过替代外部 Client API 证据；仓库未保存 API Key、prompt、答案正文、来源正文或完整 provider 异常。

## 实施检查记录

实施检查记录：最终验收证据落档后连续完成三轮只读检查，均未发现问题：

1. 事务、迁移、并发、恢复、授权与来源脱敏边界：无问题。
2. 外部 API 增量兼容、JSON/SSE/replay 一致性、旧 Java 构造器与 SDK secret-safety：无问题。
3. 发布入口、双语导航、运行时清理、构建产物隔离与最终 diff hygiene：无问题。
