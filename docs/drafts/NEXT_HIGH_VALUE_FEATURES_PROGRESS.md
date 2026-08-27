# 受管 API Principal 到期预警实施进度

> 对应规划：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)
> 工作区：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
> 当前分支：`main`
> 基线：`2291c60f`（2026-08-27）

## 当前状态

- 阶段：规划审查 `3/3` 通过，准备提交规划 checkpoint 后进入实施
- 规划审查计数：`3/3`
- 实现审查：按用户最新要求不执行重复三轮代码 review，以完整自动化验收和运行时证据收敛
- worktree：只使用主工作区；未创建额外 worktree，未使用 stash

## 已完成探索

- 核对外部采用方需求中 operation capability、最小权限 principal、provisioning 幂等、
  capability discovery、Sync Run receipt、operation observability 和 `429 Retry-After`
  均已交付，避免重复规划。
- 比较 async purge、token/cost hard limit、OAuth/OIDC、`EACH_COLLECTION` 和 credential
  expiry alerting，选择当前生产风险最明确且通用性最高的到期预警闭环。
- 核对 V7/V17 Alerts schema、AlertService、Email/DingTalk 通知、silence、Alerts WebUI。
- 核对 V48-V55 principal/credential/rotation 生命周期以及 create/update/revoke 事务边界。
- 核对现有 embedding job 的 after-commit Spring Event + Scheduled fallback 项目模式。
- 确认 Alerts WebUI 当前错误读取 `triggeredAt`，后端实际契约为 `firedAt`。

## 已冻结决策

- PostgreSQL 仍是唯一权威状态；Spring Event 只负责事务提交后的低延迟提示。
- Scheduled 默认每小时一次，只处理时间跨阈值和漏事件恢复，不做秒级轮询。
- 同一 principal 只保留一条 active expiry managed alert；阶段升级复用同一行。
- partial unique index + 条件更新/CAS 协调多实例，不使用悲观锁或外部消息代理。
- 通知使用独立 state/notified version claim；同一阶段重复事件不重复发送。
- 告警仅保存 stable principal ID、role、expiry、phase、剩余秒数和 policy version。
- WebUI 不自行计算阈值，避免浏览器时间和服务端配置漂移。

## 规划审查日志

### 第 1 轮：需求闭环与管理面安全

- 时间：2026-08-27
- 范围：目标、外部通知、API 可发现性、权限、低敏投影和兼容默认。
- 发现：
  - Email/DingTalk 默认 `alertTypes` 不包含新类型，默认启用渠道后仍不会外发到期通知；
  - `/alerts/**` 当前没有 operator 角色校验，数据库 `NORMAL` principal 可按通用 capability
    读取或修改全局告警；加入 principal ID 后会扩大管理面信息泄漏。
- 处理：
  - 规划要求默认通知 allow-list 增加 `API_PRINCIPAL_EXPIRY`，显式部署配置不被覆盖；
  - 全部 Alerts 路由统一收紧为 environment root、数据库 ADMIN、legacy static，以及
    auth-disabled 的直接 loopback；NORMAL 在 repository 读取前返回通用 `403`。
- 结果：已修改规划，计数重置为 `0`，重新开始连续三轮检查。

### 第 2 轮：schema、并发与异步通知可行性

- 时间：2026-08-27
- 范围：V57、JPA/JDBC 并发、事件消费者、fallback 扫描、公平性和通知执行合同。
- 发现：
  - `NotificationService` 当前在 primitive `boolean` 返回方法上使用 `@Async`，不符合
    Spring Async 代理合同，新增类型即使进入 allow-list 也可能无法发送；
  - managed alert 若用 raw JDBC 更新但不推进 `RagAlert.version`，会与 JPA 人工 resolve
    形成丢失更新；
  - 固定按 expiry 排序并截断的扫描会让前一批长期 active principal 永久占据 limit，
    后续 principal 在大规模场景下可能永远不被对账。
- 处理：
  - 把通知接口冻结为 `CompletableFuture<Boolean>`，保持有界异步和 best-effort 语义；
  - 所有 managed update 使用 `id + version + status` CAS，并同步推进 JPA version；
  - V57 为 principal 增加内部 `expiry_alert_checked_at`，扫描按最久未检查优先并在成功后
    推进，实现跨轮次公平覆盖。
- 结果：已修改规划，计数重置为 `0`，重新开始连续三轮检查。

## 连续无修改审查

### 第 1 轮：需求闭环与自包含性

- 时间：2026-08-27
- 范围：目标、默认值、管理面安全、低敏数据边界、兼容策略和非目标。
- 发现问题：无实质问题。
- 处理措施：无修改。
- 结果：连续无修改计数 `1/3`。

### 第 2 轮：schema、事务与多实例收敛

- 时间：2026-08-27
- 范围：V57、JPA/JDBC version 协作、after-commit 事件、通知 claim、候选轮转和有界重试。
- 发现问题：无实质问题。
- 处理措施：无修改。
- 结果：连续无修改计数 `2/3`。

### 第 3 轮：实施、验收与交付

- 时间：2026-08-27
- 范围：实施切片、一次性测试矩阵、PostgreSQL/HTTP/WebUI 硬门槛、真实 provider 回归、
  origin/main 同步、完整复验和回滚边界。
- 发现问题：无实质问题。
- 处理措施：无修改。
- 结果：连续无修改计数 `3/3`，规划可进入实施。

## 下一步

1. 执行文档、链接、锁策略、diff 与密钥门禁；
2. 提交并推送 `main` 的规划 checkpoint；
3. 在当前工作区创建专用特性分支并开始实施。
