# API Key WebUI MVP 实施进度

> 状态：Phase 0 + Phase M0、WebUI 创建/CORS/expiry 修复及日期键盘录入 follow-up
> 均已实施并通过验收；本次 follow-up 连续三轮无修改检查已完成
> 最近更新：2026-08-15
> 代码基线（本次提交前）：`main` / `1614abb`；MVP 功能提交：`ccc0e42`
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
7. 业务 Key expiry 必填、必须在未来且不设固定最长有效期。
8. root 模式拒绝 query credential，只接受 Header。
9. rotate 保留现有未来 expiry；legacy 永不过期 Key获得一年 expiry，已过期 Key不能轮换。
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
| WebUI 创建/CORS/expiry 修复 | 已完成 | 浏览器 POST 通过、长期 expiry 可创建、动态端口 CORS 通过 |
| 日期键盘录入 follow-up | 已完成 | 慢速输入 `2099` 不被 React 回写截断 |
| Secret transport | 已完成 | root 模式拒绝 query，限流使用稳定 principal ID |
| 验证硬门槛 | 已完成 | 后端/前端全部指定验证通过 |
| 本次修复实现三轮检查 | 已完成 | 连续三轮无代码修改 |

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
- `mvn test`：API `530`、documents `74`、core `2599`、starter `48`，
  合计 `3251/3251`。
- `mvn clean compile test-compile`：五模块 reactor 成功。
- 前端 ESLint、TypeScript `--noEmit`、production build、Vitest `163/163`、
  API Key MVP Mock Playwright `1/1` 均通过。
- `-Pwebui` 已重新构建内嵌静态资源；`dist/index.html`、源码静态目录和运行时入口
  bundle 一致，后端直接提供页面和入口 bundle。
- standalone 服务以 `postgresql` profile 在 `8081` 启动成功；使用
  `FRONTEND_PORT=16183` 的自定义前端端口启动时，动态 CORS 管理写探针也通过。
- 真实 Chromium 验证通过：Vite WebUI root 解锁、400 天 expiry 创建 `201`、shown-once
  raw key、业务 Key `/auth/me` 和集合读取 `200`、WebUI 吊销 `204`、吊销后业务 Key
  `401`，以及 URL/console/localStorage/sessionStorage 无 root 或业务 secret。
- 内嵌后端 WebUI 可 root 解锁，expiry 输入无最大值且默认建议约 365 天。
- 日期控件已改为浏览器原生维护值；Chromium 以每字符 250ms 输入 `2099` 后，控件值、
  React 重渲染后的值和创建请求均保留完整年份。
- 本轮创建的测试 Key 和此前遗留的精确测试记录均已清理，没有残留 `real-e2e-*` 测试
  Key。

## 6. 历史实现检查与本次修复收敛计数

MVP 主实现（2026-08-14）的连续无修改检查已经完成 `3 / 3`。本次
WebUI 创建/CORS/expiry 修复在基础验证后重新开始收敛，当前连续无修改检查：`3 / 3`。

代码审查只在验证硬门槛全部通过后开始。任一轮发现实质问题并修改代码后，计数归零，
重新执行受影响验证，再从第 1 轮开始。

| 轮次 | 时间 | 范围 | 发现问题 | 处理措施 | 结果 |
|---|---|---|---|---|---|
| 1 | 2026-08-14 | root credential、Filter 装配、管理授权、expiry/rotation、bootstrap、限流 | 无 | 无修改 | 计数 1 |
| 2 | 2026-08-14 | WebUI 内存凭据、路由、全部请求 transport、shown-once、Mock 契约、bundle | 无 | 无修改 | 计数 2 |
| 3 | 2026-08-14 | API/测试跨层契约、standalone 证据、live 文档、部署、生成产物、验收追踪 | 无 | 无修改 | 计数 3 |

上述计数只对应 MVP 主实现，不覆盖 2026-08-15 的修复。2026-08-15 修复的三轮检查
已完成，记录如下：

| 轮次 | 时间 | 范围 | 发现问题 | 处理措施 | 结果 |
|---|---|---|---|---|---|
| 1 | 2026-08-15 | 后端 expiry/rotation、root-only 授权、动态 CORS、启动器写探针 | 无 | 无修改 | 计数 1 |
| 2 | 2026-08-15 | 前端 credential transport、WebUI 表单、错误反馈、Mock E2E、内嵌 bundle | 无 | 无修改 | 计数 2 |
| 3 | 2026-08-15 | 跨层 API 契约、测试覆盖、live 文档、生成产物、完整 diff、secret scan、服务可达性 | 无 | 无修改 | 计数 3 |

## 7. 当前交付状态

Phase 0 + Phase M0 已完成并提交。MVP 功能由 `ccc0e42` 实施，后续
`3fa88cf` 增加全栈开发启动器；本次修复目前处于提交前工作区。后端、前端、
standalone HTTP、内嵌 WebUI 产物、文档门禁和本次修复的三轮实现检查均已通过。

当前没有待实施的 MVP-0 功能。后续仍未实施、且不应与 MVP-0 混称为“已完成”的工作包括：

- V25+ family/version schema 与 plaintext secret contract；
- 稳定 principal、细粒度 policy、共享 quota 和多实例吊销一致性；
- 完整 lifecycle audit、最后 ADMIN 保护和公网管理面加固；
- OpenAI Chat Completions `/v1` 兼容接口本身。

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
- root 签发 Key固定 `NORMAL/FULL_RAG`，expiry 必填且必须在未来；rotate 保留长期未来
  expiry，为 legacy 永不过期 Key补一年 expiry，并拒绝 expired/disabled Key。
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
- API Key 创建固定展示 `FULL_RAG`，expiry 必填、默认建议一年且不设置最大值；
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
- 记录了 `/auth/me`、root-only Key管理、`FULL_RAG`、必填未来 expiry、Header
  transport、WebUI 内存解锁和单实例/TLS/受控管理网络边界。
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

### 2026-08-15：WebUI 创建失败复现与修复

- 使用 `./scripts/dev.sh` 的真实 Vite 页面复现：root 解锁成功，但创建 POST 返回
  `403 Invalid CORS request`；同一请求不带 `Origin` 时后端返回 `201`。
- 确认浏览器 POST 携带正确 root header，根因是后端 CORS allow-list 未包含实际
  `http://127.0.0.1:15173` Vite origin。
- 启动器改为按最终 `FRONTEND_PORT` 注入精确 CORS origin，并增加无副作用的 root
  管理 POST 探针，避免只验证 GET 的假 ready。
- 移除业务 Key 90 天硬上限：expiry 仍必填且必须在未来；WebUI 默认建议一年且无
  `max`；轮换保留长期未来 expiry。
- 独立修复规划见
  [API Key WebUI 创建失败与过期上限修复实施规划](2026-08-15_API_KEY_WEBUI_CREATE_FIX_IMPLEMENTATION_PLAN.md)。

### 2026-08-15：修复后的真实验收

- `mvn test` 全 reactor 通过：API `530`、documents `74`、core `2599`、
  starter `48`，合计 `3251/3251`；`mvn clean compile test-compile` 通过。
- 真实 Chromium 通过 root 解锁、长期 expiry 创建 `201`、shown-once、业务 Key
  数据面调用、吊销和吊销后 `401`；浏览器 URL、console 和存储未发现 secret。
- 内嵌后端 WebUI 与 Vite bundle 入口一致，页面可解锁，expiry 无 `max` 且默认 365 天。
- 自定义 `FRONTEND_PORT=16183` 启动时动态 CORS 管理写探针通过。
- 仅删除本轮和此前遗留的精确测试 API Key 记录，数据库无 `real-e2e-*` 残留。

### 2026-08-15：日期键盘录入 follow-up

- 复现并定位 `datetime-local` 受控输入的时序风险：浏览器分段年份尚未完整时，React
  可能把中间空值写回 DOM，导致慢速输入 `2099` 被截断。
- expiry 改为 `defaultValue` 驱动的非受控原生字段；提交时通过 `FormData` 读取当前
  最终值，不再逐键同步日期字符串到 React state。
- 保留 `required`、动态 `min`、默认一年和无固定 `max`；空日期不会发起创建请求。
- Vitest `163/163`、ESLint、TypeScript、production build、Chromium Mock E2E `1/1`
  和 Maven 内嵌 WebUI 同步均通过。
- 实际 Vite 页面以每字符 250ms 输入 `2099`，中间值按 `0002 -> 0020 -> 0209 -> 2099`
  演进；React 重渲染后仍保持完整年份。
- 基础验证后连续三轮固定范围检查均未发现问题、未修改代码，计数 `3/3`。
