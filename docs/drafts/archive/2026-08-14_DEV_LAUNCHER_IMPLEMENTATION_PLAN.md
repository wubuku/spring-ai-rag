# 后端与 WebUI 一键开发启动器实施规划

> 状态：已实施；2026-08-15 增补动态 Vite origin 与 root 管理写探针
>
> 日期：2026-08-14
>
> 目标入口：`./scripts/dev.sh`
>
> 2026-08-16 后续修订：一键开发栈的默认后端端口已由 `8081` 调整为 `18082`。
> 下文保留最初实施时的基线和验收记录；当前可执行命令以
> [开发者参考](../../developer-reference-zh-CN.md) 为准。
>
> 相关参考：[开发者参考](../../developer-reference-zh-CN.md)、
> [配置参考](../../configuration-zh-CN.md)、
> [API Key WebUI MVP 实施进度](2026-08-14_API_KEY_WEBUI_MVP_PROGRESS.md)

## 1. 结论

新增一个由仓库根目录执行的 `scripts/dev.sh`，同时启动：

1. 使用 `postgresql` profile 的 Spring Boot 后端；
2. 使用 Vite dev server 和 HMR 的 WebUI；
3. 浏览器可直接访问的 root API Key 解锁页。

默认地址：

```text
Backend: http://127.0.0.1:8081
WebUI:   http://127.0.0.1:15173/webui/unlock
```

前端默认端口固定为不常用的 `15173`，不再使用已被其他进程占用且冲突概率较高的
`5173`。两个端口均可通过环境变量覆盖。

后端启动前必须用 Bash 的自动导出模式加载完整 `.env`：

```bash
set -a
source "$DEV_ENV_FILE"
set +a
```

这保证 `.env` 中不只当前旧脚本显式列出的模型和 PostgreSQL 变量，而是所有合法变量都
会进入 Maven 进程及其 fork 出的 Spring Boot JVM。Vite 进程使用独立的最小环境，
不得继承后端模型、数据库或 root credential。

2026-08-15 真实浏览器回归发现：原启动器只验证 root identity GET，而默认后端 CORS
未允许 `http://127.0.0.1:15173`，导致创建 API Key 的浏览器 POST 返回
`403 Invalid CORS request`。当前实现会根据最终 `FRONTEND_PORT` 向后端注入精确 origin，
并在 ready 前发送无副作用的无效创建请求，只有收到 `400 VALIDATION_FAILED` 才继续。

## 2. 用户工作流

首次或日常启动：

```bash
./scripts/dev.sh
```

脚本完成后：

- 后端健康检查已经通过；
- Vite 页面已经可以访问；
- WebUI 的 `/api/**` 请求代理到本次启动的后端；
- 浏览器默认打开 `http://127.0.0.1:15173/webui/unlock`；
- 修改 WebUI 源码时由 Vite HMR 即时刷新。

停止和查看状态：

```bash
./scripts/dev.sh --status
./scripts/dev.sh --stop
```

覆盖端口或环境文件：

```bash
BACKEND_PORT=18082 FRONTEND_PORT=15174 ./scripts/dev.sh
DEV_ENV_FILE=/absolute/path/to/dev.env ./scripts/dev.sh
```

禁止自动打开浏览器：

```bash
RAG_DEV_OPEN_BROWSER=false ./scripts/dev.sh
```

## 3. 当前代码库基线

### 3.1 已有能力

- 后端默认端口是 `8081`，本地数据库 profile 是 `postgresql`。
- Actuator 暴露 `/actuator/health`，且该路径不要求 API Key。
- 设置有效 `RAG_ROOT_API_KEY` 后，现有安全过滤器自动进入独立服务 root 模式。
- WebUI 已有 `/webui/unlock` 页面。
- WebUI 通过 `GET /api/v1/rag/auth/me` 验证 root principal。
- WebUI credential 只保存在页面内存，不写 localStorage。
- Vite 已代理 `/api` 到后端，并支持 `/webui` basename。

因此，本任务不新增业务 API、不改变 API Key 权限模型，也不修改数据库 schema。

### 3.2 当前缺口

现有 `scripts/start-server.sh` 只启动后端，并存在以下限制：

- 使用开发者机器的绝对路径；
- 用 `export $(grep ... | xargs)` 解析 `.env`，不能可靠支持引号、空格和 shell 合法值；
- 只把少量变量转换为 Spring Boot 命令行参数，不能证明 `.env` 全量生效；
- 不启动 Vite，WebUI 只能使用此前构建并嵌入后端的静态 bundle；
- 没有 readiness 等待、PID 所有权、日志、状态或成对停止能力；
- 不会打开浏览器。

当前 `spring-ai-rag-webui/vite.config.ts` 固定使用：

```text
port=5173
origin=http://localhost:5173/webui
proxy target=http://localhost:8081
```

这会阻止多端口开发，也不能保证前端代理和本次实际后端端口一致。

## 4. 范围

### 4.1 本次实施

1. 新增 `scripts/dev.sh`。
2. 将 Vite dev server 的端口、origin 和后端 proxy target 改为环境可配置。
3. 增加 `.dev/` 本地运行状态目录并写入 `.gitignore`。
4. 更新中英文开发者参考文档。
5. 更新文档门禁中的脚本可执行检查。
6. 执行真实的本地双进程启动验证。

### 4.2 不在本次范围

- 不替换生产部署流程或 Docker/Kubernetes 入口。
- 不改变 `scripts/start-real-e2e-server.sh` 的真实 LLM E2E 语义。
- 不增加传统用户名/密码登录。
- 不修改 root API Key、数据库 API Key 或 Collection ACL 的业务实现。
- 不把 root credential 写入 `.env`、状态文件、URL、日志或浏览器持久存储。
- 不修改生产 WebUI bundle 的发布路径。
- 不自动停止任何无法证明由本启动器创建的进程。

## 5. 冻结的设计决策

### 5.1 默认值

| 项目 | 默认值 |
|------|--------|
| 后端端口 | `8081` |
| 前端端口 | `15173` |
| 环境文件 | 仓库根目录 `.env` |
| Spring profile | `postgresql` |
| WebUI 入口 | `/webui/unlock` |
| 浏览器打开 | 默认开启，可显式关闭 |
| 状态目录 | 仓库根目录 `.dev/` |

### 5.2 配置变量

| 变量 | 用途 |
|------|------|
| `BACKEND_PORT` | Spring Boot 监听端口 |
| `FRONTEND_PORT` | Vite 监听端口 |
| `DEV_ENV_FILE` | 要 source 的环境文件 |
| `RAG_DEV_OPEN_BROWSER` | `false` 时不打开浏览器 |
| `SPRING_PROFILES_ACTIVE` | 显式设置时保留；否则使用 `postgresql` |
| `RAG_ROOT_API_KEY` | 显式设置时作为 root credential |
| `VITE_DEV_PORT` | 由启动器传给 Vite |
| `VITE_DEV_PROXY_TARGET` | 由启动器传给 Vite |
| `VITE_DEV_ORIGIN` | 由启动器传给 Vite |

端口和 Vite 变量由启动器依据实际值统一计算，避免 `.env` 中不相关的值造成前后端
错配。调用命令显式提供的 `RAG_ROOT_API_KEY` 和 `SPRING_PROFILES_ACTIVE` 应优先于
`.env`；其他应用变量按 `.env` source 后的结果传给后端。

### 5.3 `.env` 加载语义

启动器只接受 Bash 可 source 的 `.env`。加载前检查文件存在，并用：

```bash
bash -n "$DEV_ENV_FILE"
```

做语法检查。随后：

```bash
set -a
source "$DEV_ENV_FILE"
set +a
```

`set -a` 是必要条件：即使 `.env` 使用普通 `NAME=value` 而不是
`export NAME=value`，变量也会导出给 Maven 和 JVM。

启动器不得：

- 用 `grep | xargs` 重新解析 shell；
- 把变量值拼入 Maven 命令行；
- 输出 `.env` 内容；
- 将环境快照写入 `.dev/`。

`.env` 只用于后端。前端依赖检查必须在 source 前完成；Vite 启动时使用 `env -i`
构造最小环境，只传递 Node/npm 运行必需的 `PATH`、`HOME`、`USER`、locale、临时目录
以及启动器计算出的三个 `VITE_DEV_*` 变量。不得把模型 API Key、数据库密码或
`RAG_ROOT_API_KEY` 传给 Vite 进程。

后端进程启动并继承完整环境后，启动器必须立即取消应用变量的 export 属性，只重新导出
后续外部工具必需的无敏感白名单，例如 `PATH`、`HOME`、`USER`、locale 和临时目录。
root credential 保存在未导出的 shell 变量中。这样后续 readiness curl、`tail`、剪贴板
和浏览器命令不会继承 `.env` 中的模型、数据库或 root secret。

### 5.4 root credential

启动器按以下顺序决定 root credential：

1. 调用环境显式提供的 `RAG_ROOT_API_KEY`；
2. `.env` 中提供的 `RAG_ROOT_API_KEY`；
3. 若两者都没有，生成只对本次后端进程有效的高熵临时 root key。

临时 root key 使用 `openssl rand -hex 32` 等价方式生成，满足后端至少 32 个可打印
ASCII 字符的校验。生成值只存在于启动器和后端进程环境：

- 不写文件；
- 不写日志；
- 不放 URL；
- 不自动注入浏览器 storage；
- macOS 有 `pbcopy` 时复制到剪贴板；
- 其他平台有可用剪贴板工具时可复制；
- 无剪贴板工具时只在当前交互终端显示一次，并给出明确安全提示。

如果 root key 来自调用环境或 `.env`，脚本只说明已使用已配置的 root，不打印其值，也
不主动复制已配置 secret。

临时 root key 随后端停止而失效；这适合本地开发，不替代部署环境中的持久 secret 管理。

### 5.5 Vite 动态配置

`vite.config.ts` 改为从 Node 进程环境读取：

```text
VITE_DEV_PORT
VITE_DEV_PROXY_TARGET
VITE_DEV_ORIGIN
```

没有启动器时，直接在 WebUI 目录运行 `npm run dev` 仍使用：

```text
port=15173
proxy target=http://127.0.0.1:8081
origin=http://127.0.0.1:15173/webui
```

端口解析必须验证为 `1..65535` 的整数；非法配置在 Vite 启动前失败，不能静默回退到
另一个端口。Vite 使用 strict port，禁止在端口冲突时自行切换，确保打印 URL、代理
origin 和实际监听一致。

### 5.6 本地状态

`.dev/` 只保存非 secret 的运行信息：

```text
.dev/backend.pid
.dev/frontend.pid
.dev/backend.log
.dev/frontend.log
.dev/state.env
```

`state.env` 只允许保存：

```text
BACKEND_PORT
FRONTEND_PORT
BACKEND_URL
FRONTEND_URL
```

目录创建时使用受限权限；整个 `.dev/` 加入 `.gitignore`。任何 credential、环境变量
快照或 `.env` 内容都不得进入状态文件。

### 5.7 进程所有权和端口冲突

脚本只通过自己记录的 PID 管理进程，不执行按端口无条件 `kill -9`。

重启前：

1. 检查目标后端和前端端口；
2. 如果端口空闲，继续；
3. 如果监听者属于当前 `.dev/*.pid` 所记录进程的子树，允许后续停止并重启；
4. 如果不能证明监听者属于本启动器，立即失败，打印 PID 和端口覆盖示例；
5. 在两个目标端口都通过检查前，不停止现有的已管理开发栈。

读取 PID 文件后至少验证：

- PID 是正整数且仍存活；
- 进程工作目录与仓库根目录或 WebUI 目录匹配；
- 命令类型与 Maven/Spring Boot 或 Node/Vite 匹配。

停止时先收集子进程树，发送 `TERM` 并等待；超时后只对该已验证进程树发送 `KILL`。
陈旧或 PID 已复用的文件应移除，但不得杀死不匹配进程。

### 5.8 启动顺序和失败清理

固定顺序：

1. 解析 `start`、`--status`、`--stop`。
2. 检查 Bash、Java 21+、Maven、Node/npm、curl、lsof。
3. 检查 `.env` 存在且语法有效，并在尚未加载后端 secret 时检查或安装前端依赖。
4. 检查两个目标端口和已有进程所有权。
5. 只有前置检查全部通过后，停止当前启动器已有的后端和前端。
6. 加载 `.env` 并准备 root credential。
7. 启动后端，写 PID 和日志。
8. 取消后端应用变量的 export 属性，仅恢复启动器无敏感白名单。
9. 等待 `/actuator/health/readiness` 返回 HTTP 200。
10. 以最小白名单环境启动 Vite，写 PID 和日志。
11. 等待 `/webui/unlock` 返回 HTTP 200。
12. 验证 Vite client 资源可访问。
13. 验证带 root credential 的 `/api/v1/rag/auth/me` 经前端 proxy 返回 root identity。
14. 打印 URL、PID、日志路径和停止命令。
15. 按配置打开浏览器。

启动阶段设置清理 trap。任一步失败，都停止本次已经启动的受管进程，保留日志用于排查，
并返回非零退出码。全部 ready 后取消失败清理 trap，使两个 dev server 在脚本退出后继续
运行。

### 5.9 readiness 与超时

后端 readiness：

```text
GET http://127.0.0.1:${BACKEND_PORT}/actuator/health/readiness
期望：HTTP 200
```

仓库的 readiness group 包含 `readinessState` 和 `db`。启动器不使用聚合全部可选组件的
`/actuator/health` 作为启动硬门槛，避免尚未调用或暂时不可用的 LLM 附属健康项造成
本地开发假失败；随后经前端 proxy 的 root identity 请求继续验证应用 API 已可服务。

前端 readiness：

```text
GET http://127.0.0.1:${FRONTEND_PORT}/webui/unlock
期望：HTTP 200
```

HMR 证明：

```text
GET Vite 页面引用的 @vite/client 资源
期望：HTTP 200
```

代理和 root 证明：

```text
GET http://127.0.0.1:${FRONTEND_PORT}/api/v1/rag/auth/me
X-API-Key: <本次 root credential>
期望：HTTP 200，principalType=ENVIRONMENT_ROOT，
      capabilities 包含 API_KEY_MANAGE
```

管理写路径证明：

```text
POST http://127.0.0.1:${FRONTEND_PORT}/api/v1/rag/api-keys
Origin: http://127.0.0.1:${FRONTEND_PORT}
X-API-Key: <本次 root credential>
Body: {}
期望：HTTP 400，error=VALIDATION_FAILED
```

该探针不会创建数据库记录。`403 Invalid CORS request`、`401`、`2xx` 或 `5xx` 都会使
启动器失败并清理受管进程。

实现时通过 stdin header 文件语义传给 curl，例如 `curl --header @-`，不能把完整 root
值拼进 curl 命令行参数。剪贴板同样只从 stdin 读取。日志 secret 扫描使用 stdin pattern，
不能把待查 root 值作为 `grep` 命令行参数。

等待循环同时检查父进程是否提前退出。超时或进程退出时打印对应日志末尾，但输出必须
经过基本 secret 防护，且不能主动拼接 root key。

## 6. 文件级实施方案

### 6.1 `scripts/dev.sh`

新增可执行 Bash 脚本，包含以下小型函数：

- 参数和端口校验；
- Java 版本校验；
- 环境文件检查与加载；
- root credential 准备；
- 后端启动后的 export 清理；
- Vite 最小环境构造；
- 进程树收集和受管停止；
- 端口 listener 所有权检查；
- HTTP readiness 等待；
- 前端依赖检查；
- 状态打印；
- 浏览器打开；
- 失败清理。

脚本应从自身位置计算仓库根目录，不依赖当前工作目录或开发者绝对路径。

### 6.2 `spring-ai-rag-webui/vite.config.ts`

增加环境读取与端口校验，替换固定 `5173`、固定 origin 和固定 proxy target。保留：

- `base: '/webui/'`；
- React plugin；
- 当前 `/webui` SPA dev middleware；
- `/api` proxy。

### 6.3 `.gitignore`

加入：

```text
.dev/
```

### 6.4 `docs/developer-reference-zh-CN.md`

在“启动与健康检查”中：

- 将 `./scripts/dev.sh` 作为前后端开发的推荐入口；
- 记录默认 WebUI 端口 `15173`；
- 记录 `--status`、`--stop` 和端口覆盖；
- 明确 `.env` 由启动器完整导出；
- 将 `start-server.sh` 标注为后端单独启动入口；
- 把手动 `.env` 示例改为 `set -a/source/set +a`。

在 WebUI 章节说明直接 `npm run dev` 的默认端口也为 `15173`。

### 6.5 `docs/developer-reference.md`

与中文版保持结构和事实等价。

### 6.6 `scripts/verify-project-docs.sh`

把 `scripts/dev.sh` 加入“文档中列出的脚本必须存在且可执行”的检查列表。现有
shell syntax 检查会自动覆盖新增脚本。

## 7. 实施步骤

### Phase 1：启动器骨架

1. 增加 `.dev/` ignore。
2. 新增参数、路径、默认值和 prerequisite 检查。
3. 确保环境文件与前端依赖预检先于任何受管进程停止。
4. 实现状态文件、PID 校验、受管停止和端口 fail-closed。
5. 实现 `--status`、`--stop`。

### Phase 2：后端启动

1. 在 source `.env` 前完成前端依赖检查。
2. 加载完整 `.env`。
3. 处理 profile、端口和 root credential。
4. 后台启动 Maven/Spring Boot。
5. 清除启动器后续子进程对后端 secret 的继承。
6. 等待 Actuator readiness。
7. 失败时清理受管进程并保留日志。

### Phase 3：前端启动

1. 改造 Vite 动态端口、origin、proxy target。
2. 使用不含后端 secret 的最小白名单环境启动 Vite。
3. 使用 strict port 启动 Vite。
4. 等待 unlock 页面和 Vite client。
5. 经 Vite proxy 验证 root identity。

### Phase 4：体验与文档

1. 输出实际 URL、日志、PID 和停止命令。
2. 对临时 root key执行安全的剪贴板/一次性终端提示。
3. 打开默认浏览器。
4. 同步中英文开发者参考和文档门禁。

## 8. 测试与验收

### 8.1 静态检查

```bash
bash -n scripts/dev.sh
./scripts/verify-project-docs.sh
git diff --check
```

### 8.2 后端硬门槛

```bash
mvn clean compile test-compile
```

本任务不改变 Java 业务代码，但必须证明新增开发入口使用的后端仍可编译和测试编译。

### 8.3 前端硬门槛

```bash
cd spring-ai-rag-webui
npm run lint
npm run test:run
npm run build
```

生产构建验证动态 Vite 配置没有破坏 TypeScript 或 bundle。

### 8.4 Mock Playwright 硬门槛

在 WebUI 目录使用不常用的独立预览端口运行全量 mock suite：

```bash
npx vite preview --host 127.0.0.1 --port 14173 --strictPort
BASE_URL=http://127.0.0.1:14173 npx playwright test
```

实际执行时预览服务放到后台，等待 HTTP ready 后再运行 Playwright，并用 trap 或明确
PID 在测试结束后停止。所有现有 E2E spec 都通过 `api-mocks.ts` 或局部 `page.route`
拦截业务 API；这项门槛验证浏览器路由、root 解锁和页面交互，不依赖真实后端。

### 8.5 真实双进程集成验证

先确保本任务默认端口没有非受管 listener，然后：

```bash
RAG_DEV_OPEN_BROWSER=false ./scripts/dev.sh
./scripts/dev.sh --status
```

验证：

```bash
curl --noproxy '*' -fsS http://127.0.0.1:8081/actuator/health/readiness
curl --noproxy '*' -fsS http://127.0.0.1:15173/webui/unlock
curl --noproxy '*' -fsS http://127.0.0.1:15173/webui/@vite/client
```

root identity 请求使用本次启动器进程内的 credential，由启动器自身 readiness 完成，
不得把 secret 写进测试日志或 shell history。

还要确认：

- 后端日志证明实际 profile 为 `postgresql`；
- `.env` 中一个不被旧 `start-server.sh` 显式拼接的安全变量确实进入后端启动环境，验证
  时只检查变量存在性，不输出值；
- Vite 页面源码引用 dev client，而不是后端静态 bundle；
- 前端 `/api` proxy 到本次 `BACKEND_PORT`；
- 后端 CORS allow-list 使用本次精确 `FRONTEND_PORT`，管理 POST 探针通过；
- `.dev/backend.log` 和 `.dev/frontend.log` 不包含 root credential；
- `./scripts/dev.sh --stop` 后两个受管进程和端口都释放；
- 再次 `--status` 正确报告 stopped；
- 端口被非受管进程占用时，脚本拒绝启动且不终止该进程；
- 覆盖 `BACKEND_PORT` 和 `FRONTEND_PORT` 时，页面和 proxy 仍指向实际端口。

### 8.6 前端浏览器检查

使用 Playwright 或浏览器自动化打开：

```text
http://127.0.0.1:15173/webui/unlock
```

至少确认：

- 页面正常渲染；
- 控制台没有启动配置错误；
- 输入有效 root key 后可以进入管理台；
- 前端 API 请求发送到 `15173` 的 Vite server 并由其代理到后端。
- 创建 API Key 等写请求不会被后端 CORS 拒绝。

本任务没有修改 WebUI 业务交互，现有 Mock Playwright 仍按项目门禁执行；真实启动器
检查补充证明 dev server、后端和 proxy 的组合可用。

## 9. 三轮实现检查范围

基本集成验证全部通过后，执行连续三轮固定范围检查。仅发现会导致无法启动、误杀进程、
secret 泄漏、代理错配、核心验收不可验证或文档命令不可执行的问题时修改并将计数归零。

### 检查 1：生命周期与失败路径

- 启动、重启、status、stop；
- PID 陈旧和 PID 复用；
- 后端失败、前端失败、超时；
- 中断时清理；
- 非受管端口 listener 保护。

### 检查 2：配置与安全

- `.env` 全量导出；
- Vite 进程不继承 `.env` 后端 secret；
- readiness、剪贴板和浏览器辅助进程不继承 `.env` 后端 secret；
- 显式 root/profile 优先级；
- root 生成质量和最小长度；
- secret 不进入参数、文件、URL、日志，root header 只经 stdin 传给 curl；
- 动态端口、origin 和 proxy 一致。

### 检查 3：跨平台与文档

- macOS 默认 Bash 兼容性；
- Java、Maven、Node、npm、curl、lsof 缺失提示；
- 浏览器和剪贴板工具降级；
- 中英文命令一致；
- 文档门禁、shell syntax 和 diff whitespace。

## 10. 风险与控制

| 风险 | 控制 |
|------|------|
| `.env` 有 shell 语法错误 | 启动前 `bash -n`，失败时不启动任何进程 |
| `.env` 变量未传给 JVM | `set -a/source/set +a`，不使用 `grep/xargs` |
| 后端 secret 被 Vite 继承 | source 前完成依赖安装；用 `env -i` 白名单启动 Vite |
| 后端 secret 被辅助进程继承 | 后端启动后取消应用变量 export，仅恢复无敏感白名单 |
| `5173` 冲突 | 默认改为 `15173`，strict port |
| 覆盖其他项目进程 | 端口 fail-closed，只停止已验证 PID 子树 |
| 前端代理到错误后端 | 启动器计算并注入 proxy target，root identity 经 proxy 验证 |
| root key 泄漏 | 不落盘、不进 URL/参数/日志；curl/剪贴板使用 stdin；仅临时 key 使用剪贴板或一次性终端降级 |
| 后端成功而前端失败 | 启动 trap 成对清理 |
| PID 文件陈旧或复用 | 校验存活、cwd、命令类型，校验失败不发送 signal |
| Vite 自动换端口导致 URL 错 | `--strictPort` |
| 直接运行 `npm run dev` 行为退化 | Vite 自带 `15173`/`8081` 默认值 |

## 11. 回滚

本任务没有 schema 或数据迁移。回滚只需：

1. 停止启动器创建的进程；
2. 删除 `scripts/dev.sh`；
3. 恢复 `vite.config.ts` 的旧 dev server 配置；
4. 移除 `.dev/` ignore 和对应文档；
5. 删除本地未跟踪 `.dev/`。

生产部署、数据库和 API Key 数据不受影响。

## 12. 完成定义

只有以下全部满足才算完成：

- `./scripts/dev.sh` 从任意工作目录均可启动后端和 Vite；
- 后端完整加载 `.env` 并默认使用 `postgresql`；
- WebUI 默认运行在 `15173`，HMR 可用；
- WebUI proxy 指向本次实际后端端口；
- root unlock 经真实 proxy 验证成功；
- 不停止任何非受管进程；
- secret 不写入 Git、本地状态、URL 或日志；
- `--status`、`--stop` 可用；
- 后端编译/测试编译、前端 lint/Vitest/build、Mock Playwright 和真实双进程验证通过；
- 连续三轮实现检查无修改；
- 中英文开发者参考同步；
- 文档门禁和 `git diff --check` 通过；
- 完整 diff 回看后提交并推送，最终工作区干净。
