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
- 当前阶段：规划连续三轮无修改审查达到 `3/3`，准备提交并推送规划后创建隔离
  特性 worktree。

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
| preflight 能力画像与 self-test | 待开始 | |
| HTTP query/dispatcher 合同 | 待开始 | |
| V49 readiness/release manifest | 待开始 | |
| 真实 LLM 只读 principal 合同 | 待开始 | |
| 双语长青文档 | 待开始 | |
| 完整 Mock/真实 HTTP/真实 LLM 验收 | 待开始 | |
| merge main、tag、push、清理 worktree | 待开始 | |

## 6. 下一步

1. 运行最终文档、链接、密钥和 whitespace 门禁。
2. 在 main commit/push 规划与归档。
3. 从最新 main 创建专用分支/worktree 实施。
