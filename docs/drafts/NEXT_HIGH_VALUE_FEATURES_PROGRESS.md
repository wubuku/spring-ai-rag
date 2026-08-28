# 告警通知 Durable Outbox 与供应商投递回执实施进度

> 对应规划：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)
> 工作区：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
> 当前分支：`main`
> 规划基线：`main@a078babf`

## 当前状态

- 阶段：规划已收敛，准备建立实施前检查点
- 规划审查计数：`3/3`
- 实施：未开始
- worktree：只使用主工作区；未创建额外 worktree，未使用 stash

## 已完成探索

- 上一轮受管 API principal 到期预警 plan/progress 已归档为
  `2026-08-27_MANAGED_API_PRINCIPAL_EXPIRY_ALERTS_*`，迁移链接和双语归档索引已修复。
- 已核对更新后的典型外部接入需求：operation capability、职责分离 principal、
  principal/Collection provisioning 幂等、能力发现、Sync Run receipt、operation
  observability 与 Event-driven ASYNC embedding 均已交付。
- 已比较 durable notification outbox、token/cost hard limit、异步 Collection purge、
  OAuth/OIDC 与 `EACH_COLLECTION`，选择当前最明确的生产可靠性缺口。
- 已核对普通 `AlertServiceImpl`、managed expiry alert、Email/DingTalk provider、
  `taskExecutor`、embedding lease worker、Alert operator authorization、WebUI 和 V57 schema。

## 已冻结决策

- PostgreSQL 保存告警和 delivery 权威状态；Spring Event 只负责事务提交后的低延迟唤醒。
- Scheduled 默认一分钟，只恢复漏事件、重启和过期 lease，不做高频全表轮询。
- provider 调用在数据库事务外，并由独立有界 worker 执行。
- delivery 使用唯一约束、lease token 和 CAS，多实例不使用悲观锁。
- 语义为 at-least-once；稳定 delivery ID 用于解释极少量不可避免的重复。
- durable rollout 默认关闭，全部 V58 binary 就绪后再启用，避免混合版本双发。
- operator receipt 不返回 payload、lease、endpoint、recipient、secret 或错误正文。
- WebUI 增加 receipt tab；验收只使用 DOM/ARIA/network/JSON，不使用截图。

## 规划审查日志

### 初始第 1 轮：生命周期、身份与数据安全

- 时间：2026-08-27
- 范围：V58 主键/外键、payload 生成、retention、provider 兼容与 worker 有界性。
- 发现：
  - `BIGSERIAL` 在 insert 前不可稳定进入同一 payload，无法满足接收方使用固定 delivery ID
    排查重复的目标；
  - `alert_id ... ON DELETE CASCADE` 会让未来 alert retention 提前删除仍处于 FAILED、等待
    operator 处理的 receipt；
  - 原稿直接复制 message/metrics，没有阻止手工告警把敏感字段再次写入 outbox/provider；
  - “固定线程池”若直接使用默认 unbounded queue，不满足本项目有界异步要求。
- 处理：
  - 改为应用生成 UUID 主键，row 与 payload 共享同一 ID；
  - delivery 保存非级联的历史 alert ID，使用独立 retention；
  - 增加递归敏感字段掩码、深度/条目/字符串/总 bytes 上限和最小 payload 降级；
  - 冻结显式有界队列和拒绝后由 Scheduled 恢复的语义；
  - 明确 durable disabled 时 direct dispatcher 继续保留既有三次短重试。
- 结果：规划已修改，连续无修改计数重置为 `0/3`。

### 初始第 2 轮：HTTP 合同、provider 可用性与验收可复现性

- 时间：2026-08-27
- 范围：manual fire 输入、provider route、人工 retry 和真实两实例验收。
- 发现：
  - `FireAlertRequest` 允许空 message，type/name/severity 上限又大于 V7 列，可能在
    controller validation 后仍以数据库异常返回 500；
  - “配置决定是否建 row”和“当前进程能否发送”没有区分，JavaMailSender 缺失会被静默当成
    没有 route；
  - 原真实验收把 transient retry 和杀死 lease owner 混成一个顺序，难以稳定同时证明两个
    独立恢复合同；
  - Email 没有必要为本轮新增真实 SMTP 基础设施依赖。
- 处理：
  - 冻结 manual fire 与 V7 一致的必填/长度 400 合同；
  - 分离 `isRoutedFor` 与 `isCurrentlyAvailable`，配置错误形成可见 FAILED receipt；
  - 把真实 HTTP transport 拆成 transient retry 与 crash/lease recovery 两个场景；
  - Email 使用 JavaMailSender contract double，真实网络证据由 DingTalk-compatible local
    HTTP stub 承担。
- 结果：规划已修改，连续无修改计数保持 `0/3`。

### 连续审查第 2 轮：lease 终态与 managed stale fence

- 时间：2026-08-27
- 范围：V58 状态机、expired lease、managed phase 升级/解除与人工 retry。
- 发现：
  - `IN_PROGRESS` 在最后一次 attempt 后崩溃时，`attempt_count >= max_attempts`，既不能再次
    claim，也没有 finalize，可能永久卡住；
  - 普通告警和 managed condition 都可能使用 `notificationVersion=1`，仅凭 version 不能决定
    是否需要 stale source fence；
  - FAILED managed receipt 若在阶段升级/解除后人工 retry，可能重新发送已经过期的 WARNING。
- 处理：
  - fallback 增加有界 terminal lease recovery CAS，把已耗尽且 lease 过期的 row 收敛为 FAILED；
  - delivery 增加内部 `managed_condition` 标记；
  - worker 和 manual retry 对 managed row 重新检查 source alert ACTIVE/state version，
    stale/missing 时转 SUPERSEDED；
  - ordinary immutable payload 继续独立于 alert retention，可在 source row 删除后重试。
- 结果：规划已修改，连续无修改计数从 `1/3` 重置为 `0/3`。

### 归零后补充：人工重试的累计 attempt 审计

- 时间：2026-08-27
- 范围：FAILED receipt 的人工 retry 与历史 attempt 证据。
- 发现：清零 `attempt_count` 会丢失此前自动尝试次数，receipt 无法回答总共调用过 provider
  多少次。
- 处理：
  - `attempt_count` 改为永不回退的累计值；
  - `attempt_budget` 保存累计允许上限，初始为单周期 max-attempts；
  - 人工 retry 把 budget 扩展为 `attempt_count + current max-attempts`，不删除历史计数。
- 结果：规划已修改，连续无修改计数保持 `0/3`。

### 连续审查第 2 轮重跑：provider timeout 与 lease 可验证性

- 时间：2026-08-27
- 范围：事务外 provider 调用、lease 时长、DingTalk HTTP 与 Email SMTP 阻塞边界。
- 发现：
  - 规划要求 lease 大于 provider timeout，却没有提供统一可校验的 timeout 配置；
  - JavaMail 默认 socket timeout 可能无限，合法调用可能越过 lease 并占满有界 worker。
- 处理：
  - 新增 30 秒 `provider-attempt-timeout`，lease 至少为其两倍；
  - DingTalk connect/read 和 stock JavaMailSenderImpl connect/read/write 均受该上限控制；
  - durable Email route 遇到无法验证超时的自定义 sender 时启动失败，direct compatibility
    mode 保持原行为。
- 结果：规划已修改，连续无修改计数从 `1/3` 重置为 `0/3`。

### 连续审查第 3 轮：真实模型门禁与 durable 路径

- 时间：2026-08-27
- 范围：API/WebUI、真实 HTTP transport、现有 managed-principal 真实 LLM/Embedding runner。
- 发现：原规划只列出现有 `--with-real-llm` 命令；该 runner 默认关闭 durable notifications
  且没有通知 stub，不能证明真实 provider 生命周期中的到期告警实际经过 V58。
- 处理：
  - 增加 `verify-alert-notification-delivery.sh` 专项门禁；
  - 为 managed-principal runner 规划 `--with-durable-notifications`，自动启动隔离 stub、
    注入双实例配置并记录模式；
  - 真实模型门禁同时断言每个 managed phase 的唯一 receipt、supersede、最终送达、
    Event 早于 fallback 和 payload 低敏性。
- 结果：规划已修改，连续无修改计数从 `2/3` 重置为 `0/3`。

### 归零后补充：通知运行模式的可发现性

- 时间：2026-08-27
- 范围：operator 查询空结果的解释能力。
- 发现：只返回 receipt items 时，空页无法区分全局通知关闭、direct compatibility mode、
  durable mode 无 provider 和真正没有 delivery。
- 处理：
  - query envelope 增加低敏 `notificationsEnabled`、`durableDeliveryEnabled` 和固定枚举
    `configuredProviders`；
  - WebUI 与 Mock Playwright 覆盖 disabled/direct/no-provider/durable 四种状态。
- 结果：规划已修改，连续无修改计数保持 `0/3`。

## 连续三轮无修改审查结果

- 第 1 轮：需求闭环、自包含上下文、默认关闭的滚动升级兼容、at-least-once 边界和
  非目标一致，无实质问题。
- 第 2 轮：V58 约束、事务原子性、事件唤醒、lease/CAS、累计尝试预算、managed stale
  fence 和低敏 payload 一致，无实质问题。
- 第 3 轮：Operator API、WebUI、真实 HTTP transport、真实 LLM/Embedding 回归和发布
  顺序均可执行，无实质问题。
- 结果：连续 `3/3` 轮未修改规划正文，允许进入实施。

## 下一步

1. 运行文档/链接/diff/密钥门禁；
2. 提交并推送规划 checkpoint；
3. 直接在 main 工作区进入 V58 实施。
