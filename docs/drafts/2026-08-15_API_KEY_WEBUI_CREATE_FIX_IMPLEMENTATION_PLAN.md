# API Key WebUI 创建失败与过期上限修复实施规划

> 状态：已实施；基础验证、真实端到端验收和连续三轮无修改代码检查均通过
> 完成后作为实施记录保留
>
> 日期：2026-08-15
>
> 关联进度：[API Key WebUI MVP 实施进度](2026-08-14_API_KEY_WEBUI_MVP_PROGRESS.md)
>
> 关联启动器：[后端与 WebUI 一键开发启动器实施规划](2026-08-14_DEV_LAUNCHER_IMPLEMENTATION_PLAN.md)

## 1. 目标与结论

本次修复两个已经确认的问题：

1. 使用 `./scripts/dev.sh` 启动后，root API key 可以解锁 WebUI，但浏览器点击
   “创建密钥”返回 `403 Invalid CORS request`。
2. root 管理的业务 API key 被后端和 WebUI 限制为最长 90 天，不符合当前产品要求。

冻结后的目标行为：

- `./scripts/dev.sh` 根据实际 `FRONTEND_PORT` 自动允许本次 Vite origin，默认和自定义
  端口都可以执行 WebUI 写请求。
- 启动器在宣布 ready 前使用无副作用的 root 管理写请求探针验证 CORS、root
  transport 和 Controller 校验链路。
- root 管理的业务 key 仍要求提供未来过期时间，但不再设置最大天数。
- WebUI 不再设置 `datetime-local.max`，默认建议值改为一年后，用户可选择任意未来时间。
- 轮换保留现有未来过期时间，不再把长期 key 缩短到 90 天；仅在轮换 legacy
  永不过期 key 时补入一年后的过期时间，以继续满足 root-managed key 必须过期的边界。
- 创建失败时 WebUI 显示经过后端统一异常处理后的具体原因，而不是只有笼统提示。

本次不修改数据库 schema、root key 强度、`FULL_RAG` 权限、Collection ACL、secret
shown-once、内存凭据或 legacy 非 root 模式的创建语义。

## 2. 已复现事实

测试环境：

```text
Backend: http://127.0.0.1:8081
WebUI:   http://127.0.0.1:15173/webui/unlock
Profile: postgresql
Root:    从本地 .env 加载，32 个可打印 ASCII 字符
```

已确认：

- `GET /api/v1/rag/auth/me` 经 Vite proxy 返回 `200 ENVIRONMENT_ROOT`。
- `GET /api/v1/rag/api-keys` 经 Vite proxy 返回 `200`。
- 不携带 `Origin` 的 root `POST /api/v1/rag/api-keys` 返回 `201`，随后可 `204` 吊销。
- 真实 Chromium 完成 root 解锁后，创建 POST 携带正确 `X-API-Key`，但返回 `403`。
- 同一个 POST 显式携带 `Origin: http://127.0.0.1:15173` 时返回纯文本
  `Invalid CORS request`，请求未进入 `ApiKeyController`。

根因是默认配置只允许：

```text
http://localhost:8081
http://localhost:3000
```

而 `scripts/dev.sh` 默认 Vite origin 是：

```text
http://127.0.0.1:15173
```

解锁 GET 通常不携带 `Origin`，因此旧启动器只验证 GET 会产生假阳性；浏览器 POST 会
携带 `Origin` 并被 Spring MVC CORS 拒绝。

90 天规则同时存在于：

- `ApiKeyManagementService` 创建校验与 managed rotation；
- `ApiKeys.tsx` 的最大日期和默认日期；
- 后端、前端和 Playwright 测试；
- DTO OpenAPI 描述及中英文 live 文档；
- MVP 进度和 OpenAI 兼容就绪度文档。

## 3. 实施设计

### 3.1 开发启动器动态 CORS

`scripts/dev.sh` 在 source `.env`、计算最终端口后，为后端进程显式导出：

```text
RAG_CORS_ENABLED=true
RAG_CORS_ALLOWED_ORIGINS_0=http://127.0.0.1:${FRONTEND_PORT}
```

该值只进入后端进程。Vite 仍使用 `env -i` 的最小环境，不能继承 root、数据库或模型
secret。启动器计算值优先，避免 `.env` 中旧端口使本次前后端栈错配。

使用 Spring Boot relaxed binding 的 indexed-list 环境变量覆盖
`rag.cors.allowed-origins[0]`。真实重启和浏览器测试是该绑定是否生效的最终证据；
若实际绑定不能替换列表，则改用等价的启动参数注入，但不能通过在 Vite 中删除任意
外部 `Origin` 来绕过后端 CORS 策略。

### 3.2 启动器写路径探针

现有 `verify_root_identity_through_proxy` 只验证 GET。新增无副作用探针：

```text
POST /api/v1/rag/api-keys
Origin: 当前 FRONTEND_ORIGIN
X-API-Key: 当前 root
Content-Type: application/json
Body: {}
```

预期结果是 Controller/Bean Validation 返回 `400 VALIDATION_FAILED`。以下结果均视为
启动失败：

- `403 Invalid CORS request`：动态 origin 未生效；
- `401`：root transport 或后端 root 配置不一致；
- `2xx`：请求校验边界被意外放松；
- `5xx`：管理写链路不可用。

探针不得创建数据库记录，不得输出 root 或响应中的敏感字段。

### 3.3 后端过期语义

`generateManagedKey` 保留：

- `expiresAt` 必填；
- `expiresAt` 必须晚于服务端当前时间。

删除：

- `MAX_MANAGED_EXPIRY_DAYS = 90`；
- “超过 90 天”校验。

`rotateManagedKey`：

- 已禁用或已过期 key 继续拒绝轮换，且拒绝前不禁用旧 key。
- 具有未来 expiry 的 key 原样保留 expiry，包括超过 90 天的值。
- legacy `expiresAt == null` 的 key 使用 `now + 365 days`，避免生成不符合
  root-managed 必须过期规则的新 key。

这不是永久 key 支持；如果未来要允许 `expiresAt == null`，应作为独立契约变更处理。

### 3.4 WebUI 过期控件与错误反馈

创建弹窗：

- 保留 `min=当前时间+5分钟` 和 `required`。
- 删除 `max`。
- 默认值由 90 天后调整为 365 天后。
- 中英文提示改为“必填且必须在未来；默认建议一年”。

Axios 错误转换同时读取 RFC 7807 风格的 `detail` 和项目现有 `message` 字段。创建
mutation 在通用本地化提示后附加安全的后端错误文本，便于区分校验、授权和网络问题。

前端生产构建完成后，必须使用仓库既有 Maven `webui` profile 把 `dist` 同步到
`spring-ai-rag-core/src/main/resources/static/webui/`。不能只验证 Vite dev server，
否则独立服务直接访问后端 `/webui/**` 时仍会运行旧 bundle。

### 3.5 文档与示例环境

同步更新：

- `docs/rest-api*.md`
- `docs/configuration*.md`
- `docs/project-context*.md`
- `docs/openai-compatibility-readiness*.md`
- `docs/developer-reference*.md`
- `docs/drafts/2026-08-14_API_KEY_WEBUI_MVP_PROGRESS.md`
- `docs/drafts/2026-08-14_DEV_LAUNCHER_IMPLEMENTATION_PLAN.md`
- `docs/drafts/2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md`

文档需要明确：

- expiry 必填且必须在未来，但无 90 天上限；
- 长期 key 轮换保留原 expiry；
- `dev.sh` 自动配置实际 Vite origin 并验证管理写路径。
- 完整 hardening 可以使用告警、轮换策略或可配置默认 TTL，但不能把 90 天重新定义为
  所有 API key 的硬性最大 TTL。

当前工作区已有用户对 `.env.example` 的未提交修改。本次只在不丢失原意的前提下修正：

- 生成命令必须产生至少 32 个字符；
- 示例不能把会被后端拒绝的占位值作为可直接复制的有效配置；
- 不写入任何真实 key。

## 4. 测试与验收

### 4.1 后端

定向测试至少覆盖：

- root 创建超过 90 天的 key 成功；
- 过去时间仍被拒绝；
- managed rotation 保留超过 90 天的 expiry；
- legacy 永不过期 key 轮换获得约一年后的 expiry；
- 带允许 Vite origin 的 root POST 穿过 CORS 并到达 Controller；
- 不允许的 origin 仍被拒绝。

硬门槛：

```bash
mvn clean compile test-compile
mvn test
```

### 4.2 前端

Vitest 至少覆盖：

- expiry 必填、有默认值、有 `min`、没有 `max`；
- 选择超过 90 天的时间会原样提交；
- 创建失败显示后端原因。

Mock Playwright 更新为验证长期 expiry 可提交，同时保留 root 解锁、shown-once、轮换、
吊销、退出和 secret 不持久化断言。

硬门槛：

```bash
npm run lint
npm run test -- --run
npm run build
npx tsc -p tsconfig.json --noEmit
npx playwright test e2e/api-key-mvp.spec.ts
cd ..
mvn -pl spring-ai-rag-core -Pwebui generate-resources -DskipTests
```

如实际 package script 不同，以 `package.json` 中现有命令为准。

同步后必须确认：

- `spring-ai-rag-webui/dist/index.html` 与
  `spring-ai-rag-core/src/main/resources/static/webui/index.html` 引用同一个入口 bundle；
- 新 bundle 不再包含“最长 90 天”的 API Key 文案；
- 后端直接提供的 `/webui/api-keys` 可加载，不只验证 Vite 页面。

### 4.3 真实端到端

重新启动：

```bash
./scripts/dev.sh --stop
RAG_DEV_OPEN_BROWSER=false ./scripts/dev.sh
```

真实 Chromium 必须完成：

1. 打开 `/webui/api-keys` 并被送到 `/webui/unlock`。
2. 使用 `.env` root 解锁并返回 API Keys 页面。
3. 创建一个过期时间超过 90 天的 key，响应为 `201`。
4. 页面显示一次性 raw key。
5. 使用新业务 key 调用一个不触发真实 LLM 成本的数据面端点，确认不是 `401/403`。
6. root 吊销测试 key，之后业务 key 再调用返回 `401`。
7. 测试创建的 key 和其他临时数据全部清理。
8. 浏览器 console、URL、localStorage、sessionStorage 不出现 root 或业务 secret。
9. 对后端内嵌 WebUI 至少验证页面和新入口 bundle 可访问；生产构建内容与 Vite
   源码行为一致。

同时验证自定义前端端口，至少通过启动器的写路径探针；若成本可接受，再执行一次浏览器
创建流程。

### 4.4 已完成验收记录（2026-08-15）

本次代码已经完成以下硬门槛和真实运行验证：

- `mvn test`：API `530`、documents `74`、core `2599`、starter `48`，合计
  `3251/3251`，全部通过。
- `mvn clean compile test-compile`：五模块 reactor 成功。
- 前端 Vitest `161/161`、ESLint、TypeScript `--noEmit`、Vite production build、
  API Key MVP Mock Playwright `1/1` 均通过。
- `-Pwebui` 已重建内嵌资源；Vite `dist/index.html` 和后端静态
  `index.html` 引用同一入口 bundle，后端直接访问该 bundle 返回 `200`。
- 真实 Chromium 通过：受保护路由跳转、root 解锁、超过 90 天的 400 天 expiry 创建
  返回 `201`、raw key shown-once、业务 key `/auth/me` 返回 `200` 且只有
  `RAG_READ/RAG_WRITE`、集合读取返回 `200`、WebUI 吊销返回 `204`、吊销后业务 key
  返回 `401`、退出后 root/业务 secret 不出现在 URL、console 或浏览器存储。
- 内嵌后端 WebUI 通过同样的 root 解锁检查，expiry 输入无 `max`，默认建议值为
  365 天。
- 使用 `FRONTEND_PORT=16183` 启动时，启动器管理写路径探针通过；该探针带实际
  `Origin`，收到预期的 `400 VALIDATION_FAILED`，未创建数据库记录。
- 本轮创建的测试 API Key 及此前遗留的精确测试记录已从 PostgreSQL 清理，按名称
  检查没有残留 `real-e2e-*` 测试 key。

## 5. 风险与回滚

| 风险 | 控制 |
|---|---|
| 动态 CORS 误放开生产 | 只由 `scripts/dev.sh` 注入精确 loopback origin；不修改生产 profile |
| 自定义端口仍失败 | origin 从最终 `FRONTEND_PORT` 计算，并由启动器写探针阻断假 ready |
| 长期 key 扩大泄露窗口 | 保留强制 expiry、root-only 管理、吊销和 TLS/受控管理网络边界 |
| rotation 意外延长已过期 key | 继续在禁用旧 key 前拒绝 expired/disabled |
| 错误详情泄露内部信息 | 只展示 `GlobalExceptionHandler` 已处理的响应文本 |
| 用户未提交 `.env.example` 内容丢失 | 基于现有 diff增量修正，最终用 `git diff` 回看 |

回滚时可分别恢复启动器 CORS 注入、expiry 契约和 WebUI 控件；不涉及数据库迁移或数据
回填。

## 6. 连续检查规则

规划检查仅把会导致不可实施、越权、数据安全风险或核心验收无法验证的问题视为
实质性缺陷。发现实质问题后立即修订并把计数归零；行号、措辞、格式和实施时自然暴露的
细节不触发归零。

规划检查已在实施前连续三轮无修改通过。本次实现已通过基础验证，代码收敛检查
已连续三轮无修改通过，计数：`3 / 3`。

本次已审查启动器 CORS/ready 探针、expiry 语义、WebUI 表单与错误反馈、测试覆盖、
内嵌 bundle 和文档一致性。三轮均未发现实质问题，也未修改代码；本规划现转为已完成
的实施记录。
