# API Key WebUI MVP 实施进度

> 状态：Phase 0 + Phase M0 已完成；验证硬门槛和连续三轮实现检查均通过
> 最近更新：2026-08-14
> 代码基线：`main` / `c2e932c`
> 实施规划：[API Key 加固独立实施规划](2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md)

## 1. 当前目标

交付“独立 RAG 服务”的最短可用版本：

```text
独立运行的 RAG API 数据面
  + RAG_ROOT_API_KEY 解锁的 Web 管理控制台
  + root 创建、列出、轮换、吊销 FULL_RAG 业务 API Key
  + 外部用户/系统仅持业务 Key 调用 RAG API
```

本阶段不建设 username/password、用户表、账号 session、OIDC/IAP、family/version、
shared quota 或多实例一致性。MVP 部署边界为单实例、TLS、受控管理网络。

## 2. 不可破坏的边界

1. `RAG_ROOT_API_KEY` 与 legacy `RAG_API_KEY` 完全分离。
2. root credential 不落库、不落日志、不进入 URL、console、localStorage 或 sessionStorage。
3. WebUI 的 root 输入是“控制台解锁”，不是传统账号登录。
4. WebUI route guard 只负责体验；后端对每个管理请求重新认证和授权。
5. API-created Key 固定为 `FULL_RAG` 数据面权限，可限制 Collection，但不能管理 Key。
6. 外部调用方不依赖 WebUI，只通过 Header 携带业务 Key。
7. 业务 Key expiry 必填、在未来且不超过 90 天。
8. root 模式拒绝 query credential，只接受 Header。
9. rotate 将 legacy 永久/超长 expiry 收敛到最长 90 天，已过期 Key不能轮换。
10. 有效 root 配置自动保护 management 和 RAG 数据面；未配置 root 时保持 legacy mode。
11. MVP 模式禁用旧空表 ADMIN 自动生成和 raw 日志输出。
12. WebUI 升级时主动清除旧 API Key localStorage 项并移除 Settings 持久化入口。
13. 不经用户批准不扩展到完整 API Key hardening 的 V25 schema、family/policy/quota 工作。
14. 不修改或回退工作区中非本任务产生的用户改动。

## 3. 规划检查计数

实质性缺陷判定：仅当问题会导致方案不可实施、形成直接越权路径、产生迁移/数据安全
风险，或使核心验收无法验证时，修改规划并把计数归零。行号、措辞、格式、非穷举文件
清单和实施中自然暴露的次要细节不触发重置。

当前连续无修改检查：`3 / 3`

| 轮次 | 时间 | 范围 | 结果 |
|---|---|---|---|
| 1 | 2026-08-14 | 认证、权限、bootstrap、expiry/rotation | 无问题，计数 1 |
| 2 | 2026-08-14 | 配置、双拓扑、DTO、前端与测试入口 | 无问题，计数 2 |
| 3 | 2026-08-14 | 验收、边界、文档门禁、secret scan | 无问题，计数 3 |

终止条件已满足，可以开始 Phase 0 + Phase M0。

基线修正记录：

- 区分 MVP-0 例外与 Milestone A production-ready 硬门槛。
- 区分 environment root 与 legacy static key。
- 将 root 启动强度门槛量化为至少 32 个 ASCII 字符并拒绝 placeholder。
- MVP 工期包含 Phase 0。
- 第 1 轮发现并修复旧 bootstrap 日志 secret 和数据面匿名绕过风险；计数归零。
- 重启第 1 轮发现并修复永久 FULL_RAG Key 与 root 模式 query secret 风险；计数归零。
- 冻结前补充旧 WebUI localStorage secret 的升级清理要求；计数保持为零。
- 正式第 1 轮发现并修复 legacy Key rotation 继承永久/超长 expiry；计数归零。

## 4. 实施阶段

| 阶段 | 状态 | 完成条件 |
|---|---|---|
| 规划三轮检查 | 已完成 | 连续三轮无规划修改 |
| Phase 0 基线 | 已完成 | 后端 109 项、前端 18 项相关测试通过 |
| 后端 root principal | 已完成 | environment root 可认证，legacy static 不能管理 |
| root-only 管理 API | 已完成 | create/list/rotate/revoke 后端强制 root |
| WebUI unlock | 已完成 | `/webui/unlock`、内存 credential、退出和 route guard |
| 外部业务 Key | 已完成 | 可调用读写数据面，管理 API 返回 403 |
| Secret transport | 已完成 | root 模式拒绝 query，限流使用稳定 principal ID |
| 验证硬门槛 | 已完成 | 后端/前端全部指定验证通过 |
| 实现三轮检查 | 已完成 | 连续三轮无代码修改 |

## 5. 验证硬门槛

进入实现代码三轮收敛检查前，必须全部通过：

### 后端

- 本任务相关、尽可能端到端的 Spring Boot 集成测试。
- `mvn clean compile test-compile`。
- 本任务相关测试和必要的全量 `mvn test`。
- standalone 服务可启动；必要时以非 Mock dev 服务配合 `curl` 验证。

### 前端

- TypeScript 类型检查。
- production build。
- 核心 Mock Playwright：root 解锁、创建 Key、shown-once、列表、轮换、吊销、退出、
  业务 Key拒绝管理。

只有后端和前端分别验证通过后，才判断是否需要启动非 Mock 前后端联调。

当前结果：

- `mvn clean compile test-compile`：五模块 reactor 成功。
- 后端定向套件：core `130/130`、starter `34/34`，合计 `164/164`。
- `mvn test`：API `530`、documents `74`、core `2598`、starter `48`，
  合计 `3250/3250`。
- 前端 ESLint、Playwright TypeScript 检查、production build、Vitest
  `159/159`、Mock Playwright `38/38` 均通过。
- `-Pwebui` 已重新构建并同步 40 个内嵌静态资源；`dist`、源码静态目录和运行时
  `target/classes` 的入口 bundle 一致。
- standalone 服务以 `postgresql` profile 在隔离端口启动成功，PostgreSQL、Flyway
  V1-V24、MiniMax chat bean 和 BGE-M3 embedding bean 初始化正常。
- 真实 HTTP 验证通过：内嵌 `/webui/unlock`、匿名 `401`、root identity、创建/list、
  业务 Key读写、管理面 `403`、rotate、revoke、query credential `401`、
  create/rotate `no-store`；临时 Key和文档记录已清理。

## 6. 实现检查计数

当前连续无修改检查：`3 / 3`

代码审查只在验证硬门槛全部通过后开始。任一轮发现实质问题并修改代码后，计数归零，
重新执行受影响验证，再从第 1 轮开始。

| 轮次 | 时间 | 范围 | 发现问题 | 处理措施 | 结果 |
|---|---|---|---|---|---|
| 1 | 2026-08-14 | root credential、Filter 装配、管理授权、expiry/rotation、bootstrap、限流 | 无 | 无修改 | 计数 1 |
| 2 | 2026-08-14 | WebUI 内存凭据、路由、全部请求 transport、shown-once、Mock 契约、bundle | 无 | 无修改 | 计数 2 |
| 3 | 2026-08-14 | API/测试跨层契约、standalone 证据、live 文档、部署、生成产物、验收追踪 | 无 | 无修改 | 计数 3 |

终止条件已满足。三轮期间 tracked diff 保持不变，冻结指纹为
`26651bbb7c8cf022150a0e77feee79d39f7c56c4349a4a7df6aa5e6561af5529`。

## 7. 下一步

执行最终全量 diff 回看、secret scan、文档门禁、内嵌 WebUI 产物一致性和
`git diff --check`；全部通过后提交并推送 `main`，确认远端一致且工作区干净。

## 8. 实施日志

### 2026-08-14：上下文恢复与实施入口复核

- 确认工作树只有规划文档和本进度文档变更，生产代码尚未修改。
- 确认当前代码基线为 `main` / `c2e932c`。
- 确认规划审查已连续三轮无修改，用户已批准开始实施。
- 重新读取项目文档 Skill、MVP 阶段与验收标准。
- 定位当前真实代码包路径为 `com.springairag.core` / `com.springairag.starter`；
  规划中的概念类名有效，实施以源码实际路径为准。
- 下一检查点：完成后端 root resolver、共享 Filter 装配和 root-only 管理 API 后，
  先运行后端定向测试，再进入 WebUI 实施。

### 2026-08-14：后端 MVP 实现完成

- 新增 environment root 派生凭据：至少 32 个可打印 ASCII 字符、拒绝 placeholder、
  constant-time 比较，配置绑定后清除 `RagProperties` 中的 root 明文。
- 新增 core/starter 共用认证装配；认证先于限流，standalone core 不再漏装 Filter。
- root 配置自动保护 `/api/**`，支持 Bearer / `X-API-Key`，拒绝 query credential、
  冲突 Header 和 legacy static fallback。
- 新增 `/api/v1/rag/auth/me`；root 获得 `API_KEY_MANAGE`，业务 Key只有 RAG 读写能力。
- root 模式下 create/list/rotate/revoke 全部 root-only；业务 Key管理请求返回 403。
- root 签发 Key固定 `NORMAL/FULL_RAG`，expiry 必填且最长 90 天；rotate 收敛
  permanent/overlong expiry，拒绝 expired/disabled Key。
- raw secret 提升为 256 bit，public key ID 提升为 128 bit；create/rotate 使用
  `Cache-Control: no-store`。
- root 模式禁用 legacy 空表 ADMIN bootstrap；限流只保存稳定 principal ID。
- 全新编译后的定向测试：core `130/130`、starter `34/34`，合计 `164/164` 通过。

### 2026-08-14：WebUI 实施入口复核

- 确认当前 Axios client 从 localStorage 读取 Key，Settings 仍提供持久化入口。
- 确认聊天 streaming、文档上传、会话导出和文件预览存在绕过 Axios 的直接请求；
  这些入口都必须改为从同一内存 credential store 读取并通过 Header 发送。
- 确认现有路由全部直接挂载在 Layout 下，需新增 `/webui/unlock`、auth context、
  protected route 和显式退出。
- 确认现有 Playwright 用例均直接打开受保护页面，需统一先完成 Mock root 解锁。
- 已冻结前端实施顺序：认证基础设施与路由 -> 全请求链路 -> API Key 管理体验 ->
  Vitest/Mock Playwright -> 正式文档与硬门禁。

### 2026-08-14：WebUI MVP 主流程完成

- 新增 `/webui/unlock`、内存 credential store/auth context、受保护路由和显式退出；
  页面刷新后必须重新解锁。
- Axios、聊天 streaming、文档上传、会话导出、文件预览/原文件和客户端错误上报均通过
  Header 携带内存 credential，不再把 secret 放入 URL。
- 启动时清除旧 `rag-api-key` / `rag-api-key-role` localStorage 项；Settings 已移除
  API Key 持久化入口。
- API Key 创建固定展示 `FULL_RAG`，expiry 必填、默认建议且上限为 90 天；
  create/rotate raw key 关闭弹窗后清空组件状态。
- 修复 Vite `/webui/api-keys` 深链接被 `/api` proxy 误捕获的问题。
- 定向 Vitest `31/31`、ESLint、生产构建、Playwright E2E TypeScript 检查通过。
- 核心 Mock Playwright `1/1` 通过，覆盖业务 Key拒绝解锁、root 解锁、创建/仅显示一次、
  列表、轮换、吊销、退出和 URL/storage/console secret 检查。

### 2026-08-14：前端全量验证通过

- Vitest 全量 `159/159` 通过。
- TypeScript production build 与 Vite production build 通过。
- ESLint 通过；Playwright 测试文件独立 TypeScript 检查通过。
- Mock Playwright 全量 `38/38` 通过。
- 全量回归中修复了 Vite `/webui/api-keys` 深链接、Unlock 原目标恢复、
  lazy route 等待和 Alerts 子路径 Mock 穿透问题。
- 下一检查点：同步中英文 live 文档，然后执行前后端完整硬门禁与 standalone 启动验证。

### 2026-08-14：正式文档同步完成

- 已同步 `configuration*`、`rest-api*`、`project-context*` 和 `DEPLOYMENT.md`。
- 文档明确区分 root MVP 模式与未配置 root 时的 legacy 兼容模式。
- 记录了 `/auth/me`、root-only Key管理、`FULL_RAG`、90 天 expiry、Header transport、
  WebUI 内存解锁和单实例/TLS/受控管理网络边界。
- 修复英文 REST 文档中 API Key章节误嵌入聊天导出代码块的问题。
- `./scripts/verify-project-docs.sh` 通过全部 `10/10` 检查，`git diff --check` 通过。
- 下一检查点：执行 Maven 编译/测试硬门禁和 standalone HTTP 启动验证。

### 2026-08-14：后端与 standalone 硬门槛通过

- 后端定向测试在 clean reactor 中再次通过：core `130/130`、starter `34/34`。
- `mvn test` 全 reactor 通过：API `530`、documents `74`、core `2598`、
  starter `48`，合计 `3250/3250`。
- 发现前端 `dist` 已更新但受跟踪的后端 `static/webui` 仍是旧 bundle；这会导致
  standalone 发布物缺少 root 解锁功能。已使用既有 `-Pwebui` profile 重建并同步
  40 个静态资源，且确认源码静态目录、`dist` 与运行时入口一致。
- 使用随机临时 root credential，在隔离端口以 `postgresql` profile 启动服务；
  数据库健康、Flyway V1-V24、chat/embedding bean 均正常。
- 真实 HTTP 生命周期验证了 WebUI 深链接、匿名拒绝、root identity、创建/list、
  业务 Key读写、业务 Key管理 `403`、rotate/revoke、query credential 拒绝和
  `Cache-Control: no-store`。
- 服务已停止，临时 root 文件、业务 Key数据库记录和临时文档均已清理。
- 下一检查点：复核最终基础门禁后，开始连续三轮无修改实现检查。

### 2026-08-14：连续三轮实现检查完成

- 第 1 轮检查后端 root credential、Filter 装配、管理授权、expiry/rotation、
  bootstrap 和限流，未发现实质问题。
- 第 2 轮检查 WebUI 内存凭据、解锁/退出/路由守卫、所有请求 transport、
  shown-once secret、Mock Playwright 契约和 bundle 一致性，未发现实质问题。
- 第 3 轮检查后端 API/测试契约、standalone HTTP 证据、配置/部署/live 文档、
  生成产物和验收追踪，未发现实质问题。
- 三轮均未修改代码，连续无修改计数达到 `3/3`。
