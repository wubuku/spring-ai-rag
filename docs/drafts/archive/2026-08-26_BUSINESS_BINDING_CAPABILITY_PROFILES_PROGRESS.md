# 业务绑定能力画像与 P0 发布验收闭环实施进度

> 对应规划：[2026-08-26_BUSINESS_BINDING_CAPABILITY_PROFILES_PLAN.md](2026-08-26_BUSINESS_BINDING_CAPABILITY_PROFILES_PLAN.md)

## 1. 恢复入口

- 任务：把已落地的 `RAG_READ` / `RAG_WRITE` 纳入通用业务 binding 预检、真实 HTTP
  合同、发布证据和真实 LLM 验收。
- 规划基线：`main` / `48b09b37`，与 `origin/main` 对齐。
- 规划 worktree：
  `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
- 计划实施分支：`feat/business-binding-capability-profiles-20260826`
- 计划实施 worktree：
  `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-business-binding-capability-profiles`
- Flyway：V1-V49；本轮无 migration。
- 当前阶段：原能力画像交付已经合入 `main`；后续通用 Client 生命周期验收、真实
  Chat/Embedding 验证、并发补强和文档去客户化已经完成。最终 clean candidate
  readiness `16/16` 与真实 LLM 双实例门槛 `13/13` 均已通过，正在归档本轮文档并完成
  `main` 的最终 Git 交付。

## 2. 已完成探索

- 核对 `/auth/me`、principal policy、credential rotation、Collection ACL 和中央
  capability filter。
- 核对业务接入 guide、部署 binding preflight、preflight self-test、真实 HTTP 合同、
  readiness gate、release manifest 和真实 LLM gate。
- 确认 P0 数据面与身份自描述主体已经存在，不需要新增外部客户专用 API。
- 确认剩余实质缺口：
  - preflight 不验证 capabilities；
  - HTTP 合同未按 query/dispatcher 拆分能力；
  - readiness 仍硬编码 V48，当前 main 已是 V49；
  - business integration/TODO 长青文档保留旧全权限假设；
  - 真实 LLM principal 未显式收敛为只读。

## 3. 冻结决策

- 预检画像为 `READ_ONLY` / `READ_WRITE`，默认 `READ_WRITE`。
- 画像精确映射到规范 capability 数组，不接受任意字符串集合。
- 预检 mode 和 credential profile 分离；canary mutation 只允许 READ_WRITE。
- query principal 推荐 `NORMAL + RESTRICTED + RAG_READ`。
- dispatcher principal 推荐
  `NORMAL + RESTRICTED + RAG_READ,RAG_WRITE`。
- release manifest 记录验证过的两个画像，运行时 migration 与仓库动态 latest 相等。
- 受管 principal 回归使用显式只读 principal，并把 5 次调用作为确定性预期值以验证拒绝和
  replay 不增加调用；它不是完整客户生命周期验收的总调用上限。
- 真实 LLM/Embedding 客户生命周期验收不设置总调用次数预算；以写入、真实嵌入、检索、
  JSON/SSE Chat、Memory、幂等、轮换、失败恢复和重启连续性等场景证据充分为停止条件。
- 当前交付结束前扫描全部 Git 跟踪的代码和文档，禁止残留任何特定客户项目名称、背景知识
  或只有该客户团队才能理解的叙述；有通用价值的内容改写为本项目自包含的“典型外部
  Client 需求与通用设计”。
- 本轮不新增 API、schema、V50 或外部业务模型。

## 4. 规划审查账本

计数器：`3`

### 轮次 1：已修复，计数器重置

- 时间：2026-08-26 12:02 CST
- 范围：需求闭环、自包含性、默认值、非目标和命令可执行性。
- 发现：
  - preflight report 未区分调用方期望画像与已经由 `/auth/me` 验证的实际画像；
  - 完整 readiness 只规划了 clean-tree 模式，不能直接用于尚未提交的开发态。
- 处理：
  - 报告冻结为 `expectedCapabilityProfile` 加可空的
    `principal.capabilityProfile`，PASS 时两者必须相等；
  - 区分 dirty development gate 与提交/merge 后 clean candidate gate。
- 结果：已修复，规划审查计数重置为 `0`。

### 重审轮次 2：已修复，计数器重置

- 时间：2026-08-26 12:05 CST
- 范围：脚本/API/安全/兼容/数据可行性。
- 发现：preflight 的退出 trap 会在完整 Python 输入校验前写 report；如果新增
  capability profile 直接来自环境变量，非法或敏感原始值可能进入证据。
- 处理：规划明确 profile 必须在 shell bootstrap 阶段先收敛为固定枚举，非法值清空并
  使用稳定失败类别，report/summary 不得回显原始输入；Python 层仍执行第二次校验。
- 结果：已修复，规划审查计数重置为 `0`。

### 连续无修改轮次 1：通过

- 时间：2026-08-26 12:06 CST
- 范围：需求闭环、自包含性、通用项目边界、默认值和非目标。
- 结果：未发现实质问题，连续无修改计数 `1/3`。

### 连续无修改轮次 2：通过

- 时间：2026-08-26 12:07 CST
- 范围：脚本/API/安全/兼容/数据可行性。
- 结果：未发现实质问题，连续无修改计数 `2/3`。

### 连续无修改轮次 3：通过

- 时间：2026-08-26 12:08 CST
- 范围：实施顺序、验收证据、真实 LLM 预算、发布、回滚、Git 与 worktree 交付。
- 结果：未发现实质问题，连续无修改计数达到 `3/3`，允许进入实施。

## 5. 实施切片

| 切片 | 状态 | 证据 |
|---|---|---|
| 上一轮 plan/progress 归档 | 已完成 | `docs/drafts/archive/2026-08-26_OPERATION_SCOPED_API_CAPABILITIES_*` |
| 新规划与进度账本 | 已完成 | 当前两份活动文档 |
| 规划连续三轮审查 | 已完成 | 本文 §4，连续 `3/3` |
| preflight 能力画像与 self-test | 已完成 | 11 个负向场景通过；`bash -n` 与 `git diff --check` 通过 |
| HTTP query/dispatcher 合同 | 已完成 | 只读 lookup/search、写 `403`、状态不变、读写 dispatcher 与 rotation |
| V49 readiness/release manifest | 已完成 | 动态 latest migration；记录 `READ_ONLY` / `READ_WRITE` |
| 真实 LLM 只读 principal 合同 | 已完成 | 显式 `RAG_READ`；写拒绝不计 provider；确定性合同调用数为 5 |
| 双语长青文档 | 已完成 | integration/testing/release/TODO 中英文同步 |
| 完整 Mock/真实 HTTP/真实 LLM 验收 | 已完成 | readiness 16/16；managed principal 13/13；确定性合同 5 次真实调用，另有按场景驱动的客户生命周期验收 |
| 原特性分支 merge main、tag、push、清理 worktree | 已完成 | `origin/main=2fb58078`；tag `business-client-p0-ready-2026-08-26` |
| 后续生命周期补强、去客户化与最终交付 | 已完成 | 本文 §9；最终 readiness `16/16`、真实 LLM `13/13` 与零命中痕迹扫描 |

## 6. 下一步

1. 归档当前 plan/progress 并修复移动后的相对链接。
2. 重跑文档、禁锁、diff、新增行密钥与外部项目痕迹检查。
3. 获取并合并最新 `origin/main`；如基线变化，则重新执行受影响的完整门槛。
4. 提交并推送 `main`，确认本地与远端引用一致且工作区干净。

## 7. 实施记录

- 2026-08-26 12:10 CST：规划/归档提交 `afb78dd0` 已推送到 `origin/main`。
- 2026-08-26 12:10 CST：从 `afb78dd0` 创建
  `feat/business-binding-capability-profiles-20260826`，worktree 为
  `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-business-binding-capability-profiles`。
- 创建时 main、origin/main 与特性基线一致；目标 tag 尚不存在，计划端口均空闲。
- 2026-08-26 12:16 CST：完成 binding preflight capability profile 实现和
  11 个负向 self-test；非法画像不会进入报告，`READ_ONLY` 不能误用于 mutation canary。
- 2026-08-26 12:17 CST：开始收敛 HTTP 合同、readiness release manifest 与真实 LLM
  只读 principal 验收；固定先完成 Mock/桩门槛，再读取主工作区 `.env` 执行预期为 5 次
  provider 调用的确定性回归合同。
- 2026-08-26 12:22 CST：脚本语法、11 个 preflight self-test、`git diff --check`、
  项目文档门禁 10/10 和聚焦 Maven 125 tests 全部通过，无 failure/error/skipped。
- 2026-08-26 12:26 CST：dirty-tree 完整业务 readiness
  `20260826-capability-profiles-dirty-1` 通过 16/16：
  - PostgreSQL managed principal 9 tests、document lifecycle 12 tests、JSONB records
    3 tests；
  - `mvn clean compile test-compile`；
  - WebUI TypeScript、218 个 Vitest、生产构建；
  - 核心 Mock Playwright、真实服务 HTTP/WebUI Playwright；
  - business HTTP contract 160 checks、Flyway V49、明文凭据数 0。
- 2026-08-26 12:32 CST：真实 LLM 双实例验收
  `20260826-capability-profiles-real-llm-dirty-1` 通过 13/13：
  - 前置 PostgreSQL matrix、`mvn clean compile test-compile`、完整 Maven test
    3028 core tests + 44 starter tests、WebUI 218 tests/typecheck/build/Mock Playwright
    全部通过；
  - `READ_ONLY` principal 的写请求为 `403`，provider 调用增量为 `0`；
  - native JSON/SSE、OpenAI-compatible JSON/SSE、跨实例幂等重放、credential
    rotation、session/principal continuity 全部通过；
  - 真实 provider 调用总数精确为 `5`，未输出或提交任何 `.env` 密钥。
- 2026-08-26 12:35 CST：实现与 dirty-tree 验收账本提交为 `b0dcbb3a`；随后
  `git fetch origin --prune` 并合并 `origin/main`。远端 main 仍为 `afb78dd0`，
  合并结果为 `Already up to date`，合并后候选基线为 `b0dcbb3a`。接下来只采用该
  合并后基线的 clean-tree readiness 与真实 LLM 结果作为最终结论。
- 2026-08-26 12:39 CST：clean-tree readiness
  `20260826-capability-profiles-postmerge-clean-1` 通过 `16/16`，release manifest
  记录 Git `465b0b6a`、Flyway V49、160 项 HTTP 合同和
  `READ_ONLY` / `READ_WRITE` 两个已验证画像。
- 2026-08-26 12:45 CST：clean-tree 真实 LLM 双实例验收
  `20260826-capability-profiles-postmerge-real-llm-clean-1` 通过 `13/13`；完整 Maven、
  WebUI、Mock Playwright 和真实服务门槛先通过，随后 5 次真实 provider 调用全部成功，
  只读写拒绝与幂等 replay 的 provider 增量均为 0。

## 9. 后续客户生命周期验收

- 2026-08-26：接续真实客户生命周期验收时，发现重启恢复断言错误地把
  `lifecycle.embeddingStatus` 断言为 `FRESH`。当前项目契约实际为
  `embeddingStatus=READY` 且 `searchability=READY`；这是验收脚本错误，不是已证实的
  后端行为失败。
- 2026-08-26：已将重启恢复断言修正为上述双 READY 条件，并把客户合同 fixture 中的
  外部项目专用标识改为通用外部客户端标识。
- 2026-08-26：修正后的真实服务 readiness
  `20260826-general-client-real-recovery-1` 通过：
  - 真实 Spring Boot、一次性 PostgreSQL 和真实 WebUI Playwright 全部通过；
  - HTTP 合同 `251` 项通过，覆盖双 Collection、只读 query、相互隔离的 dispatcher、
    capability/ACL、凭据轮换、客户端 envelope 编译、投影清洗、CAS、tombstone、恢复和
    scope 分路查询；
  - 后端真实重启后，principal、外部身份、精确 replay、持久化异步 embedding 和受限检索
    均恢复。
- 2026-08-26：真实 provider smoke
  `20260826-general-client-lifecycle-1` 通过 `10/10`：
  - OpenAI-compatible Chat 服务和 SiliconFlow `BAAI/bge-m3` 1024 维 embedding 探测成功；
  - 真实文档 embedding、隔离 Search、JSON Chat 和 SSE Chat 全部成功。
- 2026-08-26：同一证据批次继续完成无总调用次数上限、按场景驱动的真实客户生命周期：
  - 两个 Collection、读写 dispatcher、只读 query principal；
  - 创建、两路真实 embedding、双 Collection vector/hybrid Search；
  - 初始/更新/删除后/恢复后/轮换后真实 Chat，及轮换后的 SSE；
  - stale CAS `409`、tombstone、恢复、query/dispatcher credential 轮换；
  - 后端重启后的 lookup、exact replay、双 Collection Search、真实 Chat、再次 CAS 更新和
    再次真实 embedding 收敛。
- 2026-08-26：受管 API principal 最终真实 LLM 门槛
  `20260826-general-client-real-llm-final-1` 通过 `13/13`。完整 Maven 测试为 Core
  `3029` 项通过、`7` 项按设计跳过，Starter `44` 项通过；WebUI `218` 项 Vitest、
  TypeScript、生产构建、alignment、核心 Mock Playwright、双实例真实服务和真实 WebUI
  均通过。原生/兼容 JSON/SSE、跨实例 replay、只读拒绝和 principal continuity 全部成功。
- 清理一次性数据时确认一个独立的公开 API 缺口：Collection 删除拒绝仍含稳定外部身份的
  文档，而 permanent-delete 也拒绝外部托管文档。测试只对本次创建的 document ID 使用了
  受控 SQL 清理；正式能力边界已同步到 REST API，并在双语 TODO 记录“受保护 purge 与
  Collection 退役”后续项。
- 2026-08-26 16:32 CST：clean candidate `2db8d57a` 首次完整 readiness 在 PostgreSQL
  matrix 停止。共享 quota 并发测试从 `16:31:58` 跨到 `16:32:11`，固定分钟桶按契约重置，
  因而 50 个请求在两个窗口中成功 `20 + 12` 次；这不是产品原子性失效，而是测试把跨窗口
  总成功数错误地断言为单窗口上限。
- 2026-08-26 16:33 CST：为共享 quota 并发断言增加“当前数据库分钟至少剩余 20 秒”的
  前置等待，聚焦真实 PostgreSQL 测试 `9/9` 通过；修复提交为 `c4e72614`。
- 2026-08-26 16:42 CST：基于 clean candidate `c4e72614` 的完整 readiness
  `20260826-postmerge-clean-client-lifecycle-2` 通过 `16/16`：
  - 聚焦后端/API 合同 `135/135`；
  - PostgreSQL managed principal、document lifecycle、JSONB record 与并发矩阵全部通过；
  - `mvn clean compile test-compile`、WebUI TypeScript、`218` 个 Vitest、生产构建、
    Mock Playwright、文档、禁锁、空白与新增行密钥检查全部通过；
  - 真实服务 HTTP 合同 `251` 项、真实 WebUI DOM 验收、后端重启、principal/外部身份/
    exact replay/异步 embedding 恢复全部通过。
- 2026-08-26 16:49 CST：同一 clean candidate 的真实 LLM 双实例验收
  `20260826-postmerge-clean-real-llm-1` 通过 `13/13`：
  - 完整 Maven 为 Core `3029` 项通过、`7` 项按设计跳过，Starter `44` 项通过；
  - WebUI `218` 项 Vitest、TypeScript、生产构建、alignment、Mock/真实 Playwright 通过；
  - 真实 native/OpenAI-compatible JSON/SSE、只读写拒绝、跨实例 replay、credential
    rotation/revocation 和 principal continuity 全部通过；
  - 确定性合同观察到 `5` 次真实 provider 调用，拒绝与 replay 的调用增量均为 `0`。
- 2026-08-26 17:18 CST：外部 Client 视角黑盒生命周期验收完成 `77` 项断言，证据位于
  `.verification/real-provider-lifecycle/20260826-postmerge-clean/external-client-lifecycle/`：
  - 两个 Collection、相互隔离的读写 dispatcher 和同时绑定两者的只读 query principal；
  - 真实 SiliconFlow BGE-M3 1024 维 embedding、双路 vector/hybrid Search；
  - 真实 `grok-4.5` KNOWLEDGE JSON/SSE Chat、多轮记忆、更新/删除/恢复后的回答变化；
  - stale CAS `409`、exact replay、query/dispatcher credential 轮换与旧凭据 `401`；
  - 后端与 WebUI 受控重启后的 principal、向量、检索、Chat、replay 和继续 mutation。
- 首次轮换后 SSE 请求返回 `500 No acceptable representation`，根因是验收客户端遗漏
  `Accept: text/event-stream`；修正请求头后从断点继续并完整通过。这是测试客户端错误，
  没有据此修改产品实现。
- 2026-08-26 17:44 CST：完成全仓客户项目痕迹清理。Git 跟踪代码、脚本、活动文档和历史
  文档均不包含外部项目名称或仓库名；客户派生的 fixture 已改写为
  `generic-client-record-mutation-v1`、`TENANT_PRIVATE` / `SHARED_CATALOG` 和通用记录术语。
  示例 envelope 明确只是测试客户端输入，不是服务端 RAG API 契约。
- 2026-08-26 17:44 CST：泛化后的完整 readiness
  `20260826-generic-client-cleanup` 通过 `16/16`：
  - 聚焦后端/API 合同 `135/135`，PostgreSQL managed principal `9/9`、document
    lifecycle `12/12`、JSONB record `3/3`；
  - `mvn clean compile test-compile`、WebUI TypeScript、`218` 个 Vitest、生产构建、
    核心 Mock Playwright、文档、禁锁、空白与新增行密钥检查全部通过；
  - 真实 Spring Boot/PostgreSQL/embedding stub HTTP 合同 `251` 项、真实 WebUI
    Playwright、服务重启、principal/外部身份/exact replay/持久异步 embedding 恢复通过。
- 2026-08-26 17:56 CST：获取最新 `origin/main` 后确认远端仍为 `2fb58078`，本地
  clean candidate `e1b88908` 无需合并。基于该基线的最终 readiness
  `20260826-final-clean-generic-client` 通过 `16/16`：
  - 聚焦后端/API 合同 `135/135`，PostgreSQL managed principal `9/9`、document
    lifecycle `12/12`、JSONB record `3/3` 与并发矩阵全部通过；
  - `mvn clean compile test-compile`、WebUI TypeScript、`218` 个 Vitest、生产构建、
    核心 Mock Playwright、文档、禁锁、空白与新增行密钥检查全部通过；
  - 真实服务 HTTP 合同 `251` 项、真实 WebUI DOM/网络验收、服务重启和持久化恢复全部通过。
- 2026-08-26 18:02 CST：最终真实 LLM 双实例验收
  `20260826-final-clean-real-llm` 通过 `13/13`：
  - PostgreSQL 集成矩阵、`mvn clean compile test-compile`、完整 Maven test、WebUI
    TypeScript、`218` 个 Vitest、生产构建、alignment 与核心 Mock Playwright 先通过；
  - 双实例真实服务、真实 WebUI、native/OpenAI-compatible JSON/SSE、跨实例幂等 replay、
    只读写拒绝、credential rotation/revocation 和 principal continuity 全部通过；
  - 共观察到 `5` 次真实 provider 调用；只读拒绝与幂等 replay 的 provider 增量均为 `0`，
    未输出或提交任何 `.env` 密钥。
- 2026-08-26 18:03 CST：对全部 Git 跟踪文件名和内容执行外部客户项目痕迹扫描；项目名称、
  常见分隔变体、中文名称和外部仓库名均为零命中。当前代码、脚本、活动文档与历史文档只保留
  本仓库自包含的通用 Client 能力、协议与验收表述。
- 2026-08-26 18:05 CST：扩大语义扫描后，进一步移除不必要的客户领域形状：
  - SQL Tool 示例统一为中性的 invoice 术语；
  - 领域化私有内容与展示层术语统一为“私有附件/客户端安全 DTO”；
  - 带序号的外部 Client 历史材料改名为通用的接入边界文档；
  - 测试脚本只调整证据文件名、注释和断言文案，不改变合同逻辑。
  完成后复扫项目名称、旧历史文件名和上述残留术语均为零命中。
- 2026-08-26 18:12 CST：泛化清理后的首次完整 readiness 在真实阶段开始时停止：
  前 `15` 个门槛均通过，但另一个本机工作区的服务在端口预检后抢占 `18294`。它的健康端点
  返回 `UP` 且 OpenAPI version 同为 `1.0.0`，旧脚本因此误判启动成功，随后根凭据请求返回
  `401`。确认本次后端日志为空、实际 OpenAPI title 不匹配后，将其判定为隔离端口竞态而非
  产品回归；未终止或修改该外部进程。
- 2026-08-26 18:13 CST：补强 readiness 启动身份校验：健康端点就绪后必须确认本次
  `BACKEND_PID` 仍存活，并要求运行时 OpenAPI title 精确为
  `Spring AI RAG Service API`。即使预检后端口被其他服务抢占，也会以明确错误 fail closed，
  不会继续用错误服务产生误导性验收结论。
- 2026-08-26 18:20 CST：使用新的隔离端口完整重跑
  `20260826-final-generic-doc-cleanup-r2`，readiness `16/16` 通过：
  - 聚焦后端/API 合同 `135/135`、PostgreSQL 三组集成矩阵、`mvn clean compile
    test-compile` 全部通过；
  - WebUI TypeScript、`218` 个 Vitest、生产构建、核心 Mock Playwright 通过；
  - 文档、禁锁、diff 与新增行密钥门槛通过；
  - 真实 Spring Boot 身份校验、HTTP 合同 `251` 项、服务重启恢复、数据库只读事实与真实
    WebUI DOM/网络验收全部通过；
  - 泛化后的“客户端安全 DTO”断言已在真实 HTTP 合同中实际执行并通过。

## 8. 实现收敛审查

计数器：`3`

- 轮次 1：preflight、安全边界与 release manifest 范围未发现实质问题，连续无修改
  计数 `1/3`。
- 轮次 2：HTTP query/dispatcher 合同与确定性真实 LLM 调用计数未发现实质问题，连续无修改
  计数 `2/3`。
- 轮次 3：发现中英文发布清单仍记录旧的 129 项 HTTP 合同，与 clean-tree manifest
  的 160 项不一致；已同步修复两种语言，计数重置为 `0`。
- 重审轮次 1（2026-08-26 12:47 CST）：限定检查 preflight、安全边界与 release
  manifest；未发现实质问题，连续无修改计数 `1/3`。
- 重审轮次 2（2026-08-26 12:48 CST）：限定检查 HTTP query/dispatcher 合同、拒绝后
  数据不变与真实 LLM 五次确定性调用计数；未发现实质问题，连续无修改计数 `2/3`。
- 重审轮次 3（2026-08-26 12:49 CST）：限定检查双语文档、release manifest、证据引用、
  shell 语法、Git diff 和新增行密钥；未发现实质问题，连续无修改计数达到 `3/3`。
