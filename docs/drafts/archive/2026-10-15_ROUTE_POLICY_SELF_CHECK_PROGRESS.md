# API Route Policy 自检实施进度

> 对应规划：[API Route Policy 自检与默认拒绝实施规划](2026-10-15_ROUTE_POLICY_SELF_CHECK_PLAN.md)
>
> 本文件是可恢复进度账本；稳定行为完成后提升到双语长青文档并归档。

## 当前状态

- 日期：2026-10-15
- 分支：`main`
- 基线：`55004e29 feat: assemble bounded knowledge retrieval context`
- 开始时工作区：干净，`main...origin/main = 0 0`
- 规划检查：`3/3`（已完成连续三轮无修改检查）
- 实现检查：`3/3`（连续三轮无修改通过）
- 当前切片：实现、全量验收、实现复核、归档和 Git 交付均已完成
- 交付结果：提交 `021ba047` 已推送到 `origin/main`，`main...origin/main = 0 0`，工作区干净。

## 冻结决策

- 不新增 Flyway migration，最高版本保持 V63。
- 不新增环境开关；新 registry 和启动自检默认启用。
- 生产 `ApiCapabilityFilter` 必须使用真实 Spring MVC registry；仅无 Spring 接线的 focused
  unit test 使用兼容 fallback。
- 未声明 `/api/*`/`/v1/*` route 对数据库业务 Key 默认 `403 ROUTE_NOT_DECLARED`。
- root、ADMIN、legacy static key、认证关闭和已有管理 Controller 授权语义不变。

## 规划检查记录

规划正文已完成连续 `3/3`：初次检查发现未知 method 语义表述问题并修正；随后发现
`RequestMappingHandlerMapping` 注入歧义/非 Web Starter 风险并修正；修正后连续三轮无修改
通过。详见 plan 第 8 节。

## 实施记录

- Slice A（已完成）：新增 `ApiRoutePolicy`、`ApiRoutePolicyDecision` 和
  `ApiRoutePolicyRegistry`；registry 从真实 Spring MVC mapping 构建快照，启动期拒绝缺失
  声明和冲突 policy。
- Slice B（已完成）：所有受保护 Controller 增加类级 policy；Search、Chat、模型比较、
  只读评估和 OpenAI Chat Completions 的只读 POST 增加方法级 `READ`，管理 Controller
  标记为 `MANAGEMENT`。
- Slice C（已完成）：`ApiCapabilityFilter` 生产接入 registry；数据库 NORMAL Key 命中
  未声明 `/api/*`/`/v1/*` route 返回 `403 ROUTE_NOT_DECLARED`，OpenAI 入口保持兼容错误体；
  无 Spring 接线的 focused unit test 保留 classifier fallback。
- Slice D（已完成）：新增 registry/filter focused 测试并同步中英文 REST、架构、配置、项目
  上下文和测试指南。
- 启动修复（已完成）：真实启动首次发现 `RagWebSecurityConfiguration` 字段注入与
  `RequestMappingHandlerMapping` 的循环依赖；改为 `ObjectProvider` 延迟解析，保留无参
  filter registration 调用兼容性。

## 验证账本

- `mvn -pl spring-ai-rag-core -am -DskipTests compile`：通过。
- focused route/security/configuration 测试（含 `RagWebSecurityConfigurationTest`）：通过。
- `mvn clean compile test-compile`：通过。
- `mvn test`：通过；共 7833 个测试，0 failures，0 errors，201 skipped。
- 最终 route-policy focused 集合：48 个测试，0 failures，0 errors。
- `./scripts/dev.sh`：PostgreSQL profile backend readiness、Actuator health 和启动输出通过；
  在当前命令执行环境中后台子进程会在命令结束时被宿主回收，因此 WebUI 的存活检查需在
  同一 launcher 生命周期内解释，不能把宿主回收误判为应用启动失败。
- 真实启动日志确认 Flyway 已验证 V1–V63、应用达到 `Started`，未出现缺少 route policy
  或冲突 policy 错误；不新增迁移。
- `./scripts/verify-project-docs.sh`、`./scripts/verify-no-pessimistic-locks.sh`、
  `git diff --check` 和 `bash -n scripts/dev.sh`：通过。
- 实现复核连续三轮无修改通过：覆盖安全兼容性、真实启动/映射、双语文档与交付边界。
