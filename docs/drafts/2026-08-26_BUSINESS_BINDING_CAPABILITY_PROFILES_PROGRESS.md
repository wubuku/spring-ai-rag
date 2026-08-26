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
- 当前阶段：实现、双语长青文档、合并后 clean-tree readiness、真实 LLM 双实例验收和
  实现收敛审查 `3/3` 均已通过；准备最终同步 `origin/main` 并完成 Git 交付。

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
- 真实 LLM 使用显式只读 principal，provider 总调用预算固定为 5。
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
| 真实 LLM 只读 principal 合同 | 已完成 | 显式 `RAG_READ`；写拒绝不计 provider；总预算 5 |
| 双语长青文档 | 已完成 | integration/testing/release/TODO 中英文同步 |
| 完整 Mock/真实 HTTP/真实 LLM 验收 | 已完成 | readiness 16/16；managed principal 13/13；真实 provider 调用精确为 5 |
| merge main、tag、push、清理 worktree | 待开始 | |

## 6. 下一步

1. 提交当前实现与验收账本。
2. 获取并合并最新 `origin/main`，记录合并后基线。
3. 按合并后代码重跑完整 readiness、真实 LLM 双实例验收与限定范围三轮审查。
4. 推送特性分支，合并并推送 `main`，创建发布 tag，确认引用一致且工作区干净。
5. 安全移除隔离特性 worktree。

## 7. 实施记录

- 2026-08-26 12:10 CST：规划/归档提交 `afb78dd0` 已推送到 `origin/main`。
- 2026-08-26 12:10 CST：从 `afb78dd0` 创建
  `feat/business-binding-capability-profiles-20260826`，worktree 为
  `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-business-binding-capability-profiles`。
- 创建时 main、origin/main 与特性基线一致；目标 tag 尚不存在，计划端口均空闲。
- 2026-08-26 12:16 CST：完成 binding preflight capability profile 实现和
  11 个负向 self-test；非法画像不会进入报告，`READ_ONLY` 不能误用于 mutation canary。
- 2026-08-26 12:17 CST：开始收敛 HTTP 合同、readiness release manifest 与真实 LLM
  只读 principal 验收；固定先完成 Mock/桩门槛，再读取主工作区 `.env` 发起最多 5 次
  provider 调用。
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

## 8. 实现收敛审查

计数器：`3`

- 轮次 1：preflight、安全边界与 release manifest 范围未发现实质问题，连续无修改
  计数 `1/3`。
- 轮次 2：HTTP query/dispatcher 合同与真实 LLM 调用预算未发现实质问题，连续无修改
  计数 `2/3`。
- 轮次 3：发现中英文发布清单仍记录旧的 129 项 HTTP 合同，与 clean-tree manifest
  的 160 项不一致；已同步修复两种语言，计数重置为 `0`。
- 重审轮次 1（2026-08-26 12:47 CST）：限定检查 preflight、安全边界与 release
  manifest；未发现实质问题，连续无修改计数 `1/3`。
- 重审轮次 2（2026-08-26 12:48 CST）：限定检查 HTTP query/dispatcher 合同、拒绝后
  数据不变与真实 LLM 五次调用预算；未发现实质问题，连续无修改计数 `2/3`。
- 重审轮次 3（2026-08-26 12:49 CST）：限定检查双语文档、release manifest、证据引用、
  shell 语法、Git diff 和新增行密钥；未发现实质问题，连续无修改计数达到 `3/3`。
