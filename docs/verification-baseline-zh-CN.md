# 验证基线总览

> 一句话：这个仓的每个结论都由哪条门禁证明、跑在哪一层、大概多久、红了先看哪里。
> 数据来源是 [gate-registry.mjs](../scripts/gate-registry.mjs)、
> [ci.yml](../.github/workflows/ci.yml)、[nightly.yml](../.github/workflows/nightly.yml)
> 与本机实测，不是凭印象汇总。

## 1. 阅读方式与证据口径

- **实测环境**：macOS arm64，JDK 21，Node 24.21.0，npm 11，Vite 8.3.5，
  Playwright Chromium；时间为单次实际运行，含进程启动。
- **CI 会更慢**：GitHub runner 上同一命令通常是本机的 1.5–3 倍；下表用于判断
  量级，不作为 SLA。
- **耗时标注**：`实测` 是本机单次值；`未实测` 表示依赖 Docker、真实模型或
  外部密钥，本机没有复现条件，不编造数字。
- **门禁注册表**：`scripts/gate-registry.mjs` 是机器可读事实源；新增脚本必须
  同时登记、接入 CI 或说明手工原因，`verify-gate-wiring.mjs` 会校验三者一致。
- **失败产物**：CI 日志落在 `.verification/ci/`、`.verification/nightly/`；本机
  运行脚本时优先看脚本最后 40 行与它打印的诊断路径。

## 2. 层级总表

| 层 | 触发 | 入口 | 主要证明 | 本机实测 |
|----|------|------|----------|----------|
| PR 快层 | push `main`/`develop`、PR | `.github/workflows/ci.yml` | 编译、单元/契约测试、静态门禁、文档门禁、WebUI 类型/规范/单测/构建/Mock E2E | 分项见下表；Mock E2E 约 9.6 分钟 |
| 长跑层 | 每日 18:00 UTC 与手动 | `.github/workflows/nightly.yml` | gated PostgreSQL、故障模式、崩溃一致性、真实模型、soak、安全回归、容器交付、真实 WebUI | 子项见下表；故障模式最长约 8 分钟 |
| 供应链层 | push `main` 与每周一 03:00 UTC | `.github/workflows/codeql.yml` | Java/JS 静态安全扫描 | 未实测（CI 专属） |
| 手工发版 | 发版前人工执行 | `./scripts/verify-release.sh` | 产物完整性、内嵌 WebUI、清单与验签 | 未实测（需完整发版环境） |
| 本地可选 | 按需 | `scripts/verify-*.py`、`scripts/verify-*.sh` | 真实进程/浏览器/故障注入的深度验收 | 见第 6 节 |

## 3. PR 快层

| 门禁/入口 | 证明的命题 | 实测 | 依赖 | 红了先看 |
|-----------|------------|------|------|----------|
| `mvn test` | 全模块单元与契约测试通过；OpenAPI 形状与 WebUI 路由契约不漂移 | 111.2s | JDK 21；不需要数据库（契约测试显式关闭 Flyway） | `.verification/ci/mvn-test.log`、`target/surefire-reports/` |
| `mvn -f demos/demo-basic-rag/pom.xml test` | demo 的配置绑定不遮蔽 core 配置，环境变量不会静默失效 | 2.6s | JDK 21 | demo 的 `application.yml` 与 `RagProperties` 绑定 |
| `./scripts/verify-project-tests.sh` | 76 项仓库门禁通过（可见性、接线、静态契约、WebUI 规范、自测） | 45.6s | Node、`rg`、Maven surefire 报告 | 脚本输出的第一个 `FAIL`；第 4 节逐项 |
| `./scripts/verify-project-docs.sh` | 17 项文档门禁通过（链接、双语结构、翻译、代码真相、脚本登记、密钥、空白） | 4.2s | Node、`rg`、git | 第 4 节里的 `verify-*` 文档项与失败文件名 |
| Flyway 迁移清单 | 迁移文件名格式、版本连续且无重复 | <1s | shell、`find` | `spring-ai-rag-core/src/main/resources/db/migration/` |
| OpenAPI 破坏性变更 | 启动 demo 取真实 `/v3/api-docs`，新增可选字段不算破坏 | 未实测 | JDK 21、PostgreSQL、demo jar | `/tmp/openapi-app.log`、`target/openapi-baseline` 差异 |
| `npx tsc -b` | WebUI 类型检查通过 | 未单测 | Node、依赖安装 | `tsc` 报错文件与行号 |
| `npm run lint` | ESLint、水平对齐、设计 token、共享基元等规范通过 | 28.3s | Node、`npm ci` | 对应 `spring-ai-rag-webui/scripts/check-*.mjs` |
| `npm run test:run` | WebUI 单元测试全部通过 | 13.8s | Node、jsdom | Vitest 失败用例与组件 diff |
| `npm run test:coverage` | 覆盖率阈值不下降 | 未单测 | Node | `coverage/` 与阈值配置 |
| `npm run build` | 生产构建成功，静态产物可生成 | 2.5s | Node、Vite 8 | 构建日志与 `dist/` |
| 静态产物与提交一致 | 重建结果与 `spring-ai-rag-core/src/main/resources/static/webui/` 逐字节一致 | 2.5s 重建 + 比较 | Node、Vite 8 | 先看 `diff -r` 列出的文件，再确认是否漏提交重建产物 |
| `npm run check:performance-budget` | 构建产物体积预算不超限 | 18.2s | Node、`dist/` | 报告里的超预算 chunk |
| `npm run test:e2e:mock` | 312 条 Mock E2E 通过（Chromium/Firefox/WebKit，含响应式） | 579.4s | Playwright 浏览器、无后端 | `playwright-report/`、失败 trace |
| `verify-visual-regression.sh` | 视觉回归基线不被意外改变 | 未实测 | Docker、Playwright | 视觉 diff 图与基线提交记录 |

## 4. 静态门禁明细（`verify-project-tests.sh` 内）

下表 56 个自动化门禁（注册表共 57 个；`verify-container-image.sh` 需要真实 Docker 镜像、没有自测，列在第 5 节）全部带自测；耗时为本机单次实测，单位秒。

### 4.1 后端与仓库契约

| 脚本 | 证明的命题 | 实测 | 红了先看 |
|------|------------|------|----------|
| `verify-architecture-boundaries.mjs` | 模块依赖方向与分层边界未被破坏 | 0.52 | 报出的跨层 import |
| `verify-capability-protocol-pinning.mjs` | capability 协议常量在客户端/服务端一致 | 0.21 | 漂移的常量名 |
| `verify-controller-constructor-count.mjs` | 控制器构造器注入数量在可测试上限内 | 0.28 | 超限控制器 |
| `verify-dependency-versions.mjs` | 第三方版本只在根 `pom.xml` 声明，模块无手写版本 | 2.79 | 报出的模块与版本 |
| `verify-docs-code-truth.mjs` | 文档中的迁移范围、端口、配置与代码一致 | 0.40 | 文档与代码不一致项 |
| `verify-e2e-run-paths.mjs` | E2E 脚本路径与真实入口存在 | 0.23 | 缺失路径 |
| `verify-embedding-state-generation.mjs` | embedding 状态机代次字段一致 | 0.23 | 状态/代次不匹配 |
| `verify-endpoint-behavior-matrix.mjs` | 端点行为矩阵覆盖全部路由 | 0.23 | 未覆盖路由 |
| `verify-env-example-consumers.mjs` | `.env.example` 每个变量都有消费者 | 2.30 | 无消费者变量 |
| `verify-error-code-catalog.mjs` | 错误码目录完整、无重复、无未使用 | 0.30 | 目录与枚举差异 |
| `verify-error-response-contract.mjs` | 错误响应 envelope 与契约一致 | 0.29 | 违反 envelope 的控制器 |
| `verify-external-db-safety.mjs` | 外部数据库安全开关默认值正确 | 0.37 | 不安全的默认值 |
| `verify-false-optional-wiring.mjs` | 标为可选的 Bean 实际已接线 | 0.35 | 假可选 Bean |
| `verify-flyway-version-pinning.mjs` | 迁移版本连续、不重复、不改写已发布脚本 | 0.24 | 版本号与文件 |
| `verify-gate-entry-points.mjs` | 门禁入口脚本可达且可执行 | 0.19 | 缺失入口 |
| `verify-gate-wiring.mjs` | 注册表、文档、CI 三方接线一致 | 0.32 | `undocumented-gate`/`orphan-gate`/`stale-ci-reason` |
| `verify-helm-env-consumers.mjs` | Helm 注入的每个环境变量都有应用消费者 | 2.76 | 无消费者变量 |
| `verify-helm-helper-liveness.mjs` | Helm helper 模板被引用且可渲染 | 0.20 | 未引用 helper |
| `verify-integration-test-switches.mjs` | 集成测试开关默认关闭且可显式开启 | 0.40 | 开关默认值 |
| `verify-interrupt-handling.mjs` | 线程中断被正确传播，不被吞掉 | 0.51 | 吞中断的代码位置 |
| `verify-json-assertions.mjs` | 测试中的 JSON 断言非空泛 | 0.22 | 空泛断言文件 |
| `verify-lifecycle-truth-source-wiring.mjs` | 生命周期真相源单点接线 | 0.44 | 多真相源路径 |
| `verify-list-endpoint-pagination.mjs` | 列表端点声明分页与稳定排序 | 0.19 | 缺失契约的端点 |
| `verify-null-request-forwarding.mjs` | null 请求字段透传不被静默丢弃 | 6.45 | 丢弃字段的控制器/DTO |
| `verify-playwright-suite-selection.mjs` | Playwright suite 选择与命名一致 | 0.22 | suite 映射 |
| `verify-port-probe-authority.mjs` | 端口探测只有一个权威来源 | 0.23 | 重复探测逻辑 |
| `verify-slo-endpoint-coverage.mjs` | SLO 覆盖全部对外端点 | 0.37 | 未覆盖端点 |
| `verify-surefire-method-selection.mjs` | surefire 方法选择语法有效 | 0.20 | 无效选择表达式 |
| `verify-tautological-assertions.mjs` | 无同义反复、永真断言 | 0.53 | 永真断言位置 |
| `verify-test-expectations.mjs` | 测试期望与覆盖基线不丢失 | 1.36 | 期望数量下降项 |
| `verify-test-visibility.mjs` | 每个测试类可见且被执行，无孤立测试 | 1.07 | 未执行的测试类 |
| `verify-tracked-env-files.mjs` | 只跟踪安全 env 文件，密钥不入库 | 0.28 | 被跟踪的敏感文件 |
| `verify-write-endpoint-idempotency.mjs` | 写端点声明并实现幂等契约 | 0.32 | 缺失幂等的端点 |
| `verify-zh-translation.mjs` | 中文文档无未翻译英文散文，双语标题结构一致 | 0.19 | 报出的文档与行号 |
| `verify-client-consumer-contract.sh` | 外部 consumer 契约样例可编译通过 | 0.41 | 契约样例差异 |
| `verify-no-pessimistic-locks.sh` | 无显式悲观锁、`SKIP LOCKED`、 advisory lock | 0.19 | 报出的 SQL/代码位置 |

### 4.2 WebUI 规范

| 脚本 | 证明的命题 | 实测 | 红了先看 |
|------|------------|------|----------|
| `check-a11y-forms.mjs` | 表单 label 与 aria 关联完整 | 0.30 | 缺失 label 的控件 |
| `check-alignment-policy.mjs` | 正文默认 `text-align: start`，居中仅限明确语义 | 0.19 | 违规选择器 |
| `check-clipboard-calls.mjs` | 复制行为统一走共享封装 | 0.20 | 直接调用剪贴板 |
| `check-decorative-graphics.mjs` | 装饰图形标记 `aria-hidden` | 0.20 | 未标记图形 |
| `check-design-system.mjs` | 设计 token、图标、共享基元规则成立 | 0.27 | 违规类别与文件 |
| `check-destructive-confirm.mjs` | 破坏性操作有二次确认 | 0.21 | 缺失确认的入口 |
| `check-double-submit.mjs` | 提交按钮防重复提交 | 0.21 | 未加锁的提交 |
| `check-hardcoded-copy.mjs` | 无硬编码用户文案 | 2.57 | 硬编码字符串 |
| `check-heading-levels.mjs` | 标题层级不跳级 | 0.20 | 跳级页面 |
| `check-i18n-keys.mjs` | i18n key 完整、无缺失 | 0.24 | 缺失 key |
| `check-mutation-errors.mjs` | 变更操作错误处理统一 | 0.21 | 未处理错误 |
| `check-number-formatting.mjs` | 数字格式化统一 | 0.19 | 直接格式化 |
| `check-page-shell.mjs` | 页面 shell 结构统一 | 0.20 | 偏离 shell 的页面 |
| `check-performance-budget.mjs` | 构建产物体积预算通过 | 9.17 | 超预算 chunk |
| `check-query-errors.mjs` | 查询错误处理统一 | 0.26 | 未处理错误 |
| `check-reduced-motion.mjs` | 尊重 `prefers-reduced-motion` | 0.18 | 未降级动画 |
| `check-single-api-base.mjs` | 只有一个 API base 来源 | 0.20 | 硬编码 base |
| `check-text-truncation.mjs` | 文本截断策略统一 | 0.20 | 直接截断 |
| `check-time-formatting.mjs` | 时间格式化统一且显式时区 | 0.31 | 直接 `toLocaleString` |
| `check-timing-constants.mjs` | 计时常量集中管理 | 0.21 | 散落魔数 |

## 5. 长跑层（nightly）

| 入口 | 证明的命题 | 实测 | 依赖 | 红了先看 |
|------|------------|------|------|----------|
| `verify-gated-it.sh` | 31 套 gated PostgreSQL 集成测试通过（真实迁移、事务、租约） | 未实测 | Docker/Testcontainers、JDK 21 | `.verification/nightly/gated-it.log` |
| `verify-upstream-recovery-e2e.py` | 上游重试、`Retry-After`、恢复后可用 | 83.4s | 隔离 PostgreSQL、mock provider | 三阶段失败点与恢复断言 |
| `verify-embedding-failure-e2e.py` | embedding 失败后恢复为 `SYNC` 且可检索 | 490.0s | 隔离 PostgreSQL、mock provider | 失败状态与恢复轮询 |
| `verify-crash-consistency-e2e.py` | 真实 `SIGKILL` 后数据一致、无半写 | 未实测 | 隔离 PostgreSQL、demo jar | 崩溃点与恢复后 SQL 断言 |
| `verify-endpoint-behavior-e2e.py` | 运行时端点行为矩阵与契约一致 | 未实测 | 隔离 PostgreSQL、mock provider | 矩阵差异行 |
| `real-llm-e2e-smoke.sh` + 真实模型检查 | 真实 BGE-M3/LLM、goldenset 与检索回归 | 未实测 | 真实模型密钥、外部网络 | 模型调用日志与 goldenset 差异 |
| 有界 soak | 持续负载下无泄漏、无锁竞争、延迟有界 | 未实测 | 隔离 PostgreSQL、demo jar | soak 摘要与内存/延迟曲线 |
| `verify-security-boundary.py` | 受限 key 不能跨 Collection/principal，路径穿越被拒 | 14.0s | 隔离 PostgreSQL、mock provider | 具体越权断言 |
| `verify-security-upgrade-path.py` | V70→V71 升级路径 fail-closed、归属回填正确 | 34.7s | 隔离 PostgreSQL、V70 数据构造 | 升级前后可见性断言 |
| `verify-security-deployment-posture.py` | 部署态安全开关默认值正确 | 32.2s | 隔离 PostgreSQL、demo jar | 不安全的默认值 |
| `verify-log-secrecy-e2e.py` | 敏感 canary 不进日志/指标/错误体/导出；actuator 诊断端点关闭 | 24.0s | 隔离 PostgreSQL、mock provider | 报告里泄漏的 canary 名称与被扫描位置 |
| `verify-container-image.sh` | 镜像可构建、可启动、健康检查通过 | 未实测 | Docker、Buildx | 构建日志与容器健康输出 |
| 真实 WebUI specs | 真实后端 + 真实模型下页面流程可用 | 未实测 | 开发栈、真实模型、Playwright | Playwright trace 与后端日志 |
| CodeQL | Java/JS 静态安全扫描无新增高置信告警 | 未实测 | GitHub Actions | CodeQL 告警位置 |

## 6. 手工发版与本地可选

| 脚本 | 证明的命题 | 依赖 |
|------|------------|------|
| `verify-release.sh` | 1.0 发版门禁：产物、内嵌 WebUI、资源存在性、Git 可跟踪性 | 完整构建环境 |
| `verify-clean-clone.sh` | 干净克隆可构建、可测试，不依赖本地残留 | 网络、Maven |
| `verify-baseline.sh` | 基线能力集合与默认配置不漂移 | 本地服务 |
| `verify-api-compatibility.sh` | 对外接口兼容性 | 服务进程 |
| `verify-business-client-readiness.sh` | 外部业务客户端接入就绪 | 服务进程、示例客户端 |
| `verify-client-api-e2e.sh` | 客户端 API 端到端 | 服务进程 |
| `verify-client-contract-governance.sh` | 客户端契约治理流程 | 样例与契约 |
| `verify-client-sdk-quickstart.sh` | SDK 快速开始可运行 | 服务进程、SDK |
| `verify-openai-compatibility.sh` | OpenAI 兼容端点行为 | 服务进程 |
| `verify-openai-sdk-e2e.sh` | OpenAI SDK 端到端 | 服务进程、SDK |
| `verify-chat-capability.sh` | 聊天能力开关与路由 | 服务进程 |
| `verify-chunking-quality.sh` | 分块质量阈值 | 隔离数据库 |
| `verify-collection-provisioning.sh` | Collection 创建/版本/别名 | 隔离数据库 |
| `verify-collection-coverage.sh` | Collection 覆盖与归属 | 隔离数据库 |
| `verify-collection-purge.sh` | 清理语义与幂等 | 隔离数据库 |
| `verify-derivation-integrity.sh` | 派生数据完整性 | 隔离数据库 |
| `verify-document-lifecycle.sh` | 文档生命周期状态机 | 隔离数据库 |
| `verify-document-relocation.sh` | 文档迁移/重定位 | 隔离数据库 |
| `verify-document-sync-runs.sh` | 外部文档幂等同步 | 隔离数据库 |
| `verify-embedding-jobs.sh` | embedding 任务队列与重试 | 隔离数据库 |
| `verify-embedding-operations.sh` | embedding 运维操作 | 隔离数据库 |
| `verify-jsonb-records.sh` | JSONB 记录读写一致 | 隔离数据库 |
| `verify-keyword-vector-decoupling.sh` | 关键词与向量检索解耦 | 隔离数据库 |
| `verify-llm-usage-ledger.sh` | LLM 用量账本准确 | 服务进程 |
| `verify-managed-api-principals.sh` | 受管 API principal 生命周期 | 隔离数据库 |
| `verify-managed-quality.sh` | 受管质量评估流程 | 隔离数据库 |
| `verify-retrieval-diagnostics.sh` | 检索诊断字段与输出 | 隔离数据库 |
| `verify-retrieval-filters.sh` | 检索过滤条件正确 | 隔离数据库 |
| `verify-rerank-document-diversity.sh` | rerank 文档多样性 | 隔离数据库、rerank fixture |
| `verify-quality-regression.sh` | 质量回归阈值 | goldenset |
| `verify-recovery-readiness.sh` | 备份/恢复就绪 | 备份产物 |
| `verify-api-key-expiry-alerts.sh` | API key 过期告警 | 告警投递配置 |
| `verify-alert-notification-delivery.sh` | 告警投递链路 | 告警投递配置 |
| `verify-flyway-schema-paths.sh` | Flyway schema 路径正确 | 隔离数据库 |
| `verify-openapi-breaking-change.mjs` | OpenAPI 基线差异 | 服务进程 |
| `verify-next-high-value-feature.sh` | 下一特性清单完整性 | 仓库状态 |
| `verify-next-high-value-features.sh` | 多特性清单完整性 | 仓库状态 |

### 6.1 本机可复现的深度验收（本轮新增/近期新增）

| 脚本 | 证明的命题 | 实测 | 依赖 |
|------|------------|------|------|
| `verify-timezone-consistency-e2e.py` | 三时区下存储/返回/解析/cron/TTL/日志一致 | 36.5s（22 项） | 隔离 PostgreSQL、mock provider |
| `verify-collection-export-roundtrip-e2e.py` | 导出→重建后文档/chunk/向量/检索一致 | 12.5s（7 项） | 隔离 PostgreSQL、mock provider |
| `verify-browser-security-headers-e2e.py` | 安全头、CSP 不阻断功能、注入被拒 | 19.5s | 隔离 PostgreSQL、Chromium、mock provider |
| `verify-webui-credential-security-e2e.py` | API Key 只驻留内存；刷新/登出/多标签/注入脚本读不到 | 22.0s | 隔离 PostgreSQL、Chromium、mock provider |
| `verify-upload-boundaries-e2e.py` | 413/503、预读拒绝、并发与中断清理 | 未实测 | 隔离 PostgreSQL、mock provider |
| `verify-resource-saturation-e2e.py` | 池耗尽/慢查询/DB 不可达的 503/504 与恢复 | 未实测 | 隔离 PostgreSQL、mock provider |
| `verify-concurrency-idempotency.py` | 并发写幂等与租约语义 | 未实测 | 隔离 PostgreSQL、mock provider |

## 7. WebUI 静态产物：入库决策与可复现性

### 7.1 结论

静态产物**继续入库**，并新增 CI 校验保证“重建结果 = 提交结果”。理由是宿主的
jar 要能直接跑，使用者不应被迫安装 Node；在锁定依赖下产物逐字节可复现，因此
diff 噪声只在升级构建工具链时出现，而不是每次构建都出现。

### 7.2 实测证据（本机）

- 同一 commit、同一 Node 24.21.0 连续两次构建：`sha256` 清单完全一致。
- Node 22.15.1 与 Node 24.21.0 构建：清单完全一致。
- 不同绝对路径（仓库内 vs `/tmp`）构建：清单完全一致。
- `TZ=Asia/Shanghai`、`LANG=zh_CN.UTF-8` 构建：清单完全一致。
- 只有文件 mtime 变化；Git 只比较内容，因此不会产生 diff。
- 未发现构建时间戳、绝对路径、并行顺序或 locale 注入；Vite 配置没有
  `define` 时间、没有 sourcemap、chunk 名为内容 hash。

### 7.3 口径与边界

- 可复现性承诺以 `package-lock.json` 锁定版本为前提；升级 Vite/Rolldown 或
  Node 大版本后产物可能整体变化，这是预期变更，必须重新生成并提交。
- 不承诺跨操作系统一致（本机只验证了 macOS arm64）；CI 的校验步骤在 Linux
  runner 上执行，若未来出现平台差异，以 CI 的 `diff -r` 结果为准。
- 不提交 `dist/`，只提交 `spring-ai-rag-core/src/main/resources/static/webui/`。

### 7.4 本地与 CI 校验

```bash
cd spring-ai-rag-webui
npm ci
npm run build
diff -r dist ../spring-ai-rag-core/src/main/resources/static/webui
```

CI 在 WebUI job 的 `Committed static build matches a fresh build` 步骤执行同样的
重建与目录比较；任何未提交的重建结果都会让 PR 变红。

## 8. 红了先看哪里

| 症状 | 优先看 |
|------|--------|
| PR 后端门禁失败 | `.verification/ci/repo-test-gates.log` 的第一条 `FAIL`，再按第 4 节定位脚本 |
| PR 文档门禁失败 | 报出的文件名与 `verify-zh-translation`/`verify-docs-code-truth` 的具体差异 |
| WebUI 规范失败 | 对应 `check-*.mjs` 的违规类别与文件行号 |
| Mock E2E 失败 | `playwright-report/` 的 trace 与截图；先确认是否只是 flaky |
| nightly gated 失败 | gated IT 日志中的迁移版本、事务或租约断言 |
| 安全回归失败 | `security-boundary`/`upgrade-path`/`posture` 的具体越权或默认值断言 |
| 静态产物门禁失败 | `diff -r` 列出的文件；确认是否改前端后忘记重建入库 |
| 真实模型 nightly 失败 | 密钥、网络、模型配额与 goldenset 差异 |
