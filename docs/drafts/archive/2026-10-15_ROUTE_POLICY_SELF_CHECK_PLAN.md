# API Route Policy 自检与默认拒绝实施规划

> **状态**：已实施并完成验收，待归档；本文件是本批实施的最终规划记录。
>
> **编写日期**：2026-10-15
>
> **实施分支**：当前 `main` 工作区 / `main` 分支；不创建额外 worktree。
>
> **相关参考**：[规划、实施与验收工作流](../../delivery-workflow-zh-CN.md)、[WeKnora
> 调研与借鉴](../WEKNORA_REFERENCE_RESEARCH.md)、[项目上下文](../../project-context-zh-CN.md)。

## 1. 执行摘要

本批实施下一项高价值安全加固：把当前按 HTTP 方法粗分的 `RAG_READ/RAG_WRITE` 能力判断，
提升为**可声明、可发现、可启动自检、未声明默认拒绝**的 API route policy。目标不是重做
认证、Collection ACL 或 principal 模型，而是防止未来新增 Controller/route 时遗漏授权声明，
并把当前散落在 `ApiCapabilityFilter` 中的管理路径和只读 POST 例外变成可验证的契约。

本批默认启用新策略，不新增环境开关，不新增数据库表，不改变已有成功请求的 JSON/API body。
现有业务 Key 的允许/拒绝结果必须保持不变；唯一有意收紧的是：对于数据库业务 Key，命中
`/api/*` 或 `/v1/*` 但没有任何已注册 Spring MVC route 的请求，由原来的方法级兜底改为明确的
`403` 未声明 route。root、ADMIN、legacy static key 和未启用认证路径保持当前行为。

## 2. 当前基线与问题

### 2.1 已核对的实现事实

- `ApiCapabilityFilter` 当前以 `GET/HEAD/OPTIONS` 为读、`PUT/PATCH/DELETE` 为写，`POST` 仅由
  `READ_POST_PATHS` 静态集合降级为读；认证、能力和限流接线见
  [`ApiCapabilityFilter`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiCapabilityFilter.java)
  与 [`RagWebSecurityConfiguration`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagWebSecurityConfiguration.java)。
- 管理/身份路径（`auth`、`api-keys`、`alerts`、`integration-capabilities`）由过滤器硬编码
  豁免，具体 Controller 自己继续负责 root/ADMIN/其他管理授权。
- 当前项目已有稳定的 `ApiVersionRequestMappingHandlerMapping`，实际版本化路径会出现在
  `RequestMappingHandlerMapping#getHandlerMethods()` 中；因此可以在应用启动后对真实 Spring
  MVC 映射做自检，不需要手工复制全部 route 清单。
- `IntegrationOperationClassifier` 是有限的业务集成观测目录，不覆盖整个 HTTP API，不能直接
  作为能力过滤器的 route registry；本批不扩大它的 operation 枚举。
- 现有 `RAG_READ/RAG_WRITE`、Collection ACL、root/ADMIN bypass、OpenAI 兼容错误体和路径
  穿越 fail-closed 测试均属于兼容边界，必须保留。

### 2.2 真实缺口

1. 新增 Controller 只要忘记更新 `ApiCapabilityFilter` 的静态例外/只读 POST 集合，就可能得到
   与设计不一致的能力结果。
2. 当前没有启动期或测试期检查“受保护 API route 是否有明确 policy 声明”。
3. 未命中的 `/api/*`/`/v1/*` path 对业务 Key 仍按 HTTP 方法兜底，无法表达“未注册 route
   默认拒绝”。
4. 管理路径的豁免、数据面 method 默认和只读 POST 例外没有统一的可读模型，也没有机器可读
   的诊断报告。

## 3. 目标、非目标与冻结决策

### 3.1 目标

1. 增加 `ApiRoutePolicy` 注解，支持 Controller 类级默认策略和方法级覆盖。
2. 增加基于真实 `RequestMappingHandlerMapping` 的 `ApiRoutePolicyRegistry`，启动时构建真实
   route policy 快照并执行完整性自检。
3. 把现有 Controller 分为三类：
   - `METHOD_DEFAULT`：GET/HEAD/OPTIONS 为读，其他已知写方法为写；
   - `READ`：固定为读，用于 `POST /search`、Chat、只读模型比较和只读评估入口；
   - `MANAGEMENT`：不由业务 Key capability filter 判断，由现有管理授权继续处理。
4. `ApiCapabilityFilter` 优先使用 registry 的真实 route decision；无 registry 的直接单测保留
   兼容 classifier fallback，避免测试/Starter 最小装配被破坏。
5. 对业务 Key 的未声明数据面 route 默认返回稳定 `403`，并在普通 JSON/OpenAI 兼容入口使用
   现有错误体格式；root、ADMIN 和非数据库 principal 不改变。
6. 提供测试可调用的自检报告/异常，使新增未声明 Controller 在测试和启动期都能快速定位。
7. 同步中英文架构、REST、安全与测试文档，并补充项目文档门禁和真实启动证据。

### 3.2 非目标

- 不把 `RAG_READ/RAG_WRITE` 拆成 `retrieve/chat/ingest/manage_collections` 多个新 capability；
  细粒度能力仍保留为未来独立设计。
- 不改变 `IntegrationOperation`、usage ledger、Collection ACL、principal schema、Flyway
  版本或 WebUI API。
- 不为每个 route 新增数据库配置、动态热加载或远程 policy provider。
- 不在本批实现 OAuth/OIDC、多租户 RBAC、Parser SPI、resource handle 或 `EACH_COLLECTION`。
- 不要求运维新增配置、手工迁移、手工刷新 route cache 或重启后执行额外命令。

### 3.3 冻结决策与可逆边界

| 决策 | 默认值 | 理由 | 可逆边界 |
|---|---|---|---|
| policy 来源 | Controller 类/方法注解 + Spring MVC 实际映射 | 由代码声明，启动可验证，避免重复维护路径字符串 | 删除注解接线并恢复旧 fallback；不涉及数据 |
| 类级默认 | `METHOD_DEFAULT` | 大多数 GET/写入 POST 语义可由 HTTP method 表达 | 单个方法增加 `READ`/`MANAGEMENT` 覆盖 |
| 管理路径 | `MANAGEMENT` | 延续现有管理 Controller 自己做 root/ADMIN 授权 | 不改变现有授权服务 |
| 未声明 route | 对数据库业务 Key `403 ROUTE_NOT_DECLARED` | 安全默认拒绝，避免新增 route 漏授权 | 仅影响未注册/不存在 route；已映射 route 必须有声明 |
| registry 初始化失败 | 应用启动失败 | route policy 是安全基础设施，不能静默退回宽松模式 | 修复声明或回滚本批代码 |
| 旧单元测试直接 new filter | 使用兼容 classifier fallback | 保留最小单元测试和 starter 边界 | 生产 Spring 接线始终注入 registry |
| 新增文档/方法 | 类级默认自动覆盖，特殊读写需方法覆盖 | 默认行为安全；只读 POST 漏声明时会被写能力保护 | 增加方法注解即可修正，不改 API |

## 4. 对外行为与安全契约

### 4.1 Policy 语义

`ApiRoutePolicy` 仅描述 capability gate 的安全语义，不代替 Spring `@RequestMapping`：

```text
METHOD_DEFAULT: GET/HEAD/OPTIONS -> RAG_READ；POST/PUT/PATCH/DELETE -> RAG_WRITE
READ:           固定 RAG_READ（允许只读 POST）
MANAGEMENT:     capability filter 不判定；继续由管理 Controller/服务授权
```

生产 registry 只匹配 Spring MVC 实际声明的 HTTP method；未知 method 因此没有对应 route，
对数据库业务 Key 进入 `UNDECLARED` 默认拒绝。没有 Spring registry 的 focused unit test
fallback 保留现有 classifier 行为（包括正常路径未知 method 返回 `null`），不把 fallback
当作生产安全实现。畸形 URI 继续由 `SecurityPathExclusions` 处理为 fail closed。

### 4.2 Filter decision

registry 返回以下有限 decision：

- `READ`：数据库业务 principal 必须具备 `RAG_READ`；
- `WRITE`：必须具备 `RAG_WRITE`；
- `MANAGEMENT`：返回当前 filter 的“无需 capability 判断”，交给既有管理授权；
- `UNDECLARED`：返回 `403`，错误码为 `ROUTE_NOT_DECLARED`，不泄漏内部 handler/class 名称。

只有数据库业务 Key进入新的 route decision。root、ADMIN、legacy static key 和认证关闭路径
继续在 capability filter 中直接透传；认证失败仍由 `ApiKeyAuthFilter` 负责。

### 4.3 兼容性

- 已映射、已声明的 route 对现有业务 Key 的成功/403 结果保持原语义：只读 route 仍读能力即可，
  写 route 仍需读写能力。
- `/v1/chat/completions` 标为 `READ`；未知 `/v1/*` route 对业务 Key 默认拒绝，OpenAI 错误体
  使用现有 `insufficient_permissions` 结构。
- `/api/v1/rag/auth/**`、`/api/v1/rag/api-keys/**`、`/api/v1/rag/alerts/**`、
  `/api/v1/rag/integration-capabilities` 标为 `MANAGEMENT`，不改变其已有独立授权。
- 不新增响应字段，不改成功状态码，不需要迁移历史数据。

## 5. 文件级实施顺序

### Slice A：安全模型与 registry

1. 新增 `ApiRoutePolicy` 注解、`ApiRoutePolicyDecision`/内部 route entry 模型。
2. 新增 `ApiRoutePolicyRegistry`：
   - 从 `RequestMappingHandlerMapping#getHandlerMethods()` 读取真实最终路径、HTTP method、
     `HandlerMethod` 类/方法注解；
   - 只检查 `/api/*`、`/v1/*`，忽略 actuator、swagger、error 等非数据面映射；
   - 拒绝同一 method/path 产生冲突 policy；
   - 暴露 `validate()`、`decision(method,path)` 和只读诊断摘要；
   - `SmartInitializingSingleton` 完成后构建不可变快照。
3. 将所有当前受保护 Controller 补上类级声明；为现有只读 POST 方法补方法级 `READ`，管理
   Controller 补 `MANAGEMENT`。不改变 `@RequestMapping`。

### Slice B：过滤器接线与默认拒绝

1. 修改 `RagWebSecurityConfiguration`，仅在 Servlet Web 应用中以限定 bean 名
   `requestMappingHandlerMapping` 创建 registry；不按父类型盲注入，避免 Actuator 等其他
   `RequestMappingHandlerMapping` 子类产生歧义。Filter registration 通过
   `ObjectProvider<ApiRoutePolicyRegistry>` 接收 registry，非 Web/最小测试装配继续使用 fallback。
2. 修改 `ApiCapabilityFilter`：生产接线使用 registry；无 registry 仅用于 focused unit tests 的
   legacy classifier fallback。
3. 增加 `ROUTE_NOT_DECLARED` 的普通 JSON/OpenAI 错误分支，保持 secret-safe 和稳定字段。
4. 保留既有路径穿越、管理豁免、read-only POST、ADMIN bypass 和 unknown method 测试。

### Slice C：测试与门禁

1. Registry 单测：类级默认、方法覆盖、管理豁免、路径归一、重叠冲突、未声明 handler 失败。
2. Filter 单测：registry decision、未声明默认拒绝、OpenAI 错误体、fallback 兼容。
3. 真实 Spring MVC mapping self-check：加载真实 Controller mapping，确认所有 `/api/*`/`/v1/*`
   route 都有 policy，且关键 read/write/management route decision 与现状一致。
4. 现有 API key HTTP/集成回归：read-only read=200、write=403、ADMIN/root bypass、unknown
   data-plane route=403、OpenAI unknown route 错误体。

### Slice D：文档、启动与交付

1. 更新 `architecture.md`/`architecture-zh-CN.md`、`rest-api.md`/`rest-api-zh-CN.md`、
   `testing-guide.md`/`testing-guide-zh-CN.md`、`project-context*`。
2. 更新本 progress 账本，记录规划 3/3、实现门禁、实现 3/3 和 Git 状态。
3. 执行 Maven、PostgreSQL/HTTP 相关测试、`scripts/dev.sh`、真实 `.env` smoke（若环境可用）、
   文档门禁、禁悲观锁和 `git diff --check`。
4. 归档 plan/progress 到 `docs/drafts/archive/`，同步归档 README，commit、push、确认工作区干净。

## 6. 验收矩阵

| 层次 | 必须验证 | 证据 |
|---|---|---|
| Java 单测 | 注解解析、registry 冲突/缺失、路径归一、filter decision 和错误体 | 新增 `ApiRoutePolicy*Test` + 既有 filter tests |
| Spring MVC | 真实 Controller mapping 全量 policy 自检 | `ApiRoutePolicyApplicationMappingTest` 或等价真实上下文测试 |
| HTTP/安全 | 只读/读写/ADMIN/root/未声明 route/OpenAI 错误体 | 现有 API key HTTP/集成套件扩展 |
| Flyway | 无 schema 变化，确认 V63 仍为最新 | 空库迁移/版本断言 |
| 启动 | `scripts/dev.sh` 后端/WebUI 启动、health/readiness、代理 | 启动日志和 curl/脚本输出 |
| 前端 | 未改 WebUI/DTO，仍执行 typecheck、测试、build；Mock Playwright 标记 N/A 或最小回归 | `npm run test:run`、`npm run build` |
| 真实模型 | route policy 不改变模型调用；真实 LLM 可做已有 smoke，非本批阻断主证据 | `real-llm-e2e-smoke.sh` |
| 静态门禁 | 文档、禁锁、diff | `verify-project-docs.sh`、`verify-no-pessimistic-locks.sh`、`git diff --check` |

## 7. 风险、回滚与完成定义

### 7.1 主要风险

- 自定义版本化 mapping 生成的最终路径与 registry 读取不一致：以真实 Spring mapping 测试先
  钉住；不能用源码字符串扫描替代。
- Starter 被引入到非 Servlet 应用，或应用同时存在多个 MVC/Actuator mapping bean：registry
  必须受 `@ConditionalOnWebApplication(type = SERVLET)` 保护，并用标准 mapping bean 的限定名
  注入；不能让安全加固破坏非 Web Starter 启动。
- 某些 Controller 使用多个 `@PostMapping` 或条件 mapping：registry 必须按最终
  `RequestMappingInfo` 展开，冲突则启动失败。
- management route 若误标为数据面，会把现有独立授权改变为 capability gate；必须由真实
  mapping 矩阵验证。
- filter 直接单测没有 Spring registry：保留明确 fallback，且生产 bean wiring 测试必须确认
  registry 非空，避免 fallback 意外成为生产路径。

### 7.2 回滚

无 schema 和数据迁移；代码回滚到上一提交即可恢复旧 classifier。若只需临时恢复某一只读
POST，可增加方法级 policy 声明，不需要运维改配置或手工 SQL。

### 7.3 完成定义

- 规划连续 `3/3`，实现连续 `3/3`，实质修复均按规则重置计数并重新收敛；
- 所有真实数据面 Spring MVC route 有 policy，未声明 route 对业务 Key 默认拒绝；
- 已有授权成功/拒绝矩阵和 OpenAI 错误体回归通过；
- `mvn clean compile test-compile`、相关 PostgreSQL/HTTP、`scripts/dev.sh`、文档/禁锁/diff
  门禁通过；
- 双语长青文档同步，plan/progress 归档；
- commit、push 后 `main...origin/main = 0 0` 且工作区干净。

## 8. 规划检查账本

规划开始时计数为 `0`。仅当一轮完整检查未发现实质问题且未修改本文时计数加一；发现会影响
正确性、兼容性、安全、成本或可实施性的实质问题，立即修正文档并归零。

### 第 1 轮：通过

- 时间：2026-10-15
- 范围：当前代码事实、route 覆盖范围、注解/registry 设计、默认拒绝兼容性。
- 发现：初次检查发现“未知 HTTP method 在已声明 route 上要求写能力”的表述不符合现有
  fallback 行为。
- 处理：将契约改为生产 registry 不匹配即 `UNDECLARED` 拒绝；无 registry 的 focused
  unit test fallback 保留旧 classifier 行为，并将规划计数归零后重跑。
- 结果：修正后的第 1 轮无问题通过。

### 第 2 轮：通过

- 时间：2026-10-15
- 范围：Spring MVC 实际 mapping、Filter 接线、异常响应、测试可行性、无迁移边界。
- 发现：初次检查发现按父类型注入 `RequestMappingHandlerMapping` 可能与 Actuator 子类
  产生歧义，且非 Web Starter 不应强制创建 registry。
- 处理：增加标准 bean 名限定、Servlet Web 条件和 `ObjectProvider` fallback；计数归零后
  完整重跑三轮。
- 结果：修正后的第 2 轮无问题通过。

### 第 3 轮：通过

- 时间：2026-10-15
- 范围：实施顺序、回滚、启动/回归门禁、双语文档与 Git 交付。
- 发现：无实质问题。
- 处理：无。
- 结果：连续三轮无修改通过，规划检查计数 `3/3`，允许进入实施。
