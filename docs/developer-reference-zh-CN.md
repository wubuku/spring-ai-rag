# 开发者参考

> [English](developer-reference.md) | [中文](developer-reference-zh-CN.md)

> **用途**：提供可复制的构建、启动、数据库、模型、WebUI、E2E 和发布验证命令。
> **维护原则**：命令必须与仓库脚本保持一致；本地 Agent 状态文件可以链接本文，但本文不依赖本地状态。

文档总入口：[index-zh-CN.md](index-zh-CN.md)。稳定项目认知：[project-context-zh-CN.md](project-context-zh-CN.md)。

## 1. 固定约定

| 项目 | 值 |
|------|----|
| Java | 21+ |
| Maven | 3.9+ |
| 服务 / 后端单独启动默认端口 | `8081` |
| `dev.sh` 后端端口 | `18082` |
| 本地 profile | `postgresql` |
| 真实 LLM E2E 端口 | `18081` |
| Embedding | SiliconFlow `BAAI/bge-m3` |
| 向量维度 | `1024` |
| Flyway | V1–V59 |

OpenAI / Embedding 的 `base-url` **不要带 `/v1`**。Spring AI 会自行追加 `/v1/chat/completions` 或 `/v1/embeddings`。

## 2. 构建与测试

```bash
mvn clean compile
mvn clean test
mvn clean package -DskipTests
```

测试那一步的 `clean` 不是可选项。不带它，Maven 会保留已从源码删除的测试类在
`target/test-classes` 里的产物，Surefire 会继续执行它们——报告出来的套件规模
以及由它推导出的每一个覆盖率数字都被虚增。
`scripts/verify-project-tests.sh` 会把源码树与报告双向对账，任一方向不匹配即失败，
所以幽灵类过不了门禁。

单模块和单测试（定向运行可用于调试，但**不满足**可见性门禁——门禁期望的是该模块的
全量套件）：

```bash
mvn test -pl spring-ai-rag-core
mvn test -pl spring-ai-rag-core -Dtest=RagDocumentControllerTest
```

覆盖率：

```bash
mvn clean test jacoco:report
open spring-ai-rag-core/target/site/jacoco/index.html
```

测试策略和更多命令见 [testing-guide-zh-CN.md](testing-guide-zh-CN.md)。

### 测试可见性

在后端测试跑完、报告已生成之后执行：

```bash
./scripts/verify-project-tests.sh
```

一个既没执行、也没声明自己被跳过的测试类，在 surefire 报告里写的是
`tests="0" skipped="0"`——和一个真正为空的类**完全相同**，于是它从运行摘要里
彻底消失。用 `@BeforeAll` 里的 `assumeTrue` 关闭一个类就会造成这种结果：
JUnit 是**中止**容器，而不是把它标记为跳过。

此前有 **21 个** PostgreSQL/Testcontainers 集成测试类处于这种状态，隐藏了约
**145 个**测试方法——其中包括 API 密钥轮换安全守卫的**唯一**覆盖。它们现在都带有
类级 `@EnabledIfSystemProperty`，因此被关闭的容器会把自己的真实用例数如实报告为
skipped；本门禁则保证今后再有类"闭嘴"就会失败。

因为"不能失败的门禁比没有门禁更糟"，脚本会先运行
`scripts/test-support/test-visibility-self-test.mjs`，它断言检查器确实**拒绝**
`tests="0" skipped="0"` 这种形态，而不只是"它跑起来了"。

#### 集成测试开关对账

同一个脚本接着把门控那些 PostgreSQL 套件的 `*.it.enabled` 开关，与本该打开它们的
脚本和文档对账：

```bash
./scripts/verify-project-tests.sh   # 六项检查连同各自的自测一起跑
```

给套件加门控是一份"总有人能把它打开"的承诺。`PdfImportPostgresIntegrationTest`
——2 个测试方法——的开关在任何脚本、任何文档里都没出现过，只在一份已归档的进度记录里，
于是没有任何东西能跑它。门禁在以下情况失败：受门控的开关在任一方向上缺少运行路径；
运行路径引用了没有测试类消费的开关；受门控的类不声明任何 `@Test`；
`verify-gated-it.sh` 清单条目指向已删除的类，或它传的开关与该类实际受控的开关对不上。
新增受门控的套件就要同时给出它的运行路径；
`scripts/test-support/integration-switch-self-test.mjs` 证明这四条检查都还能拒绝。

### 门禁清单与门禁普查

`scripts/gate-registry.mjs` 登记了本仓库每一个门禁脚本，`scripts/verify-gate-wiring.mjs`
对它做普查。登记不是形式：Batch 809 在这份清单之前量到 **21 个门禁/入口里有 13 个在
`docs/` 下查无一处**，包括 9 个 WebUI 检查里的 7 个——`check:mutation-errors` 和
`check:query-errors` 这样的东西根本没人知道它存在。

| 门禁 | 拒绝什么 | 自测 | 跑在哪 |
|------|----------|------|--------|
| `verify-test-visibility.mjs` | 既没执行也没声明跳过的测试类（`tests="0" skipped="0"`） | `test-support/test-visibility-self-test.mjs` | tests 链 |
| `verify-integration-test-switches.mjs` | 门控开关与运行路径的双向缺口 | `test-support/integration-switch-self-test.mjs` | tests 链 |
| `verify-external-db-safety.mjs` | 接受调用方指定库名却直接 `flyway.clean()` 的套件 | `test-support/external-db-safety-self-test.mjs` | tests 链 |
| `verify-e2e-run-paths.mjs` | 没有任何脚本能运行的 Playwright spec | `test-support/e2e-reachability-self-test.mjs` | tests 链 |
| `verify-slo-endpoint-coverage.mjs` | 配了阈值却已不存在的端点；跨 controller 重名的 timer | `test-support/slo-endpoint-coverage-self-test.mjs` | tests 链 |
| `verify-gate-wiring.mjs` | 未登记、无自测、无人执行、CI 到不了的自动化门禁 | `test-support/gate-wiring-self-test.mjs` | tests 链 |
| `verify-test-expectations.mjs` | 方法体为空、只有注释的 `@Test`（每次运行都算通过） | `test-support/inert-test-self-test.mjs` | tests 链 |
| `verify-null-request-forwarding.mjs` | 把字面量 `null` 转发进 `HttpServletRequest` 参数位的重载——而 `ChatPrincipal.from(null)` 与 `ApiKeyCollectionAccess.isUnrestricted(null)` **双双 fail-open** | `test-support/null-request-forwarding-self-test.mjs` | tests 链 |
| `verify-no-pessimistic-locks.sh` | 生产代码里的悲观锁 / `SKIP LOCKED` / advisory lock | `test-support/pessimistic-locks-self-test.sh` | docs 链 |
| `verify-zh-translation.mjs` | 中文文档里未翻译的英文段落 | `test-support/zh-translation-self-test.mjs` | docs 链 |
| `verify-project-tests.sh` / `verify-project-docs.sh` | 上面 8 个的聚合入口 | 由各门禁承担 | 人跑 / 待接入 CI |
| `verify-gated-it.sh` | 154 个纯 DB 型集成套件 | 由开关对账承担 | **CI 已接** |
| `verify-webui-e2e-mock.sh` | 15 spec / 93 用例的前端 mock 回归 | 套件自身即自测 | 单独跑（2.6 分钟） |
| `check-alignment-policy.mjs` | 物理 `text-align`、内联 `textAlign`、全局样式表契约 | `__tests__/alignment-policy.test.mjs` | `npm run lint` |
| `check-design-system.mjs` | 越过设计 token 的硬编码值 | `__tests__/design-tokens.test.mjs` | `npm run lint` |
| `check-a11y-forms.mjs` | 没有可访问名的控件、没绑定的 label | `__tests__/a11y-forms.test.mjs` | `npm run lint` |
| `check-mutation-errors.mjs` | 写操作不报告失败 | `__tests__/mutation-errors.test.mjs` | `npm run lint` |
| `check-query-errors.mjs` | 读操作失败时看起来像"空结果" | `__tests__/query-errors.test.mjs` | `npm run lint` |
| `check-double-submit.mjs` | 请求在途时未加锁的写操作 | `__tests__/double-submit.test.mjs` | `npm run lint` |
| `check-i18n-keys.mjs` | 两个 locale 键集不对称；`t()` 引用不存在的键；`t('x') \|\| 兜底` 这种永远不会触发的守卫；**某个键两种语言都有、却没有任何源码能到达**——其中"引用"可以是模板前缀、查找表、数据数组、别名翻译函数或 i18next 复数族 | `__tests__/i18n-keys.test.mjs` | `npm run lint` |
| `check-hardcoded-copy.mjs` | 从未接入 i18n 的组件；已接入文件里的硬编码用户文案——含 JSX 表达式容器内的那部分，同时放行 ARIA/机器属性值与 `t()` 兜底文案 | `__tests__/hardcoded-copy.test.mjs` | `npm run lint` |
| `check-page-shell.mjs` | 受保护页面绕过 `PageHeader`，或渲染它时不给 `description` | `__tests__/page-shell.test.mjs` | `npm run lint` |

普查的门禁有五条硬规则：每个门禁脚本必须在册；自动化门禁必须带自测或写明为什么
不能带；自动化门禁必须有东西执行它；**CI 到不了的自动化门禁必须写明理由**；门禁必须
在文档里被提到。新增门禁而不登记，下一次跑 `verify-project-tests.sh` 就会失败。

最后一条规则不是为了凑数。Batch 809 删掉的 `check-entity-migration-sync.sh` 是本仓库
**第四个**"不能失败的门禁"：它声称核对实体字段与 Flyway 迁移，函数体里却没有任何比较，
硬编码的 11 张表里有 6 张早已改名（`rag_retrieval_log` → `rag_retrieval_logs` 等），连接的
还是项目根本不用的 `postgres` 库；而它声称保护的不变量，`application.yml` 里的
`ddl-auto: validate` 在每次启动时就已经强制了。它之所以能活这么久，是因为
`verify-project-docs.sh` 里的 "Gates can fail closed" 只检查使用 `rg`/`jq`/`yq` 的脚本，
一个 `psql` + `grep` 的门禁对它是隐形的。

CI 现状：仓库级的那两个入口（`verify-project-docs.sh` / `verify-project-tests.sh`）
**尚未接入 CI**——Batch 806 因 OAuth `workflow` scope 限制摘出，待人工应用
`/tmp/b806-ci-gates.patch`。WebUI 的 9 个检查连同它们的自测已经在 CI 里跑（`ci.yml` 的
webui job 执行 `npm run lint`）。普查门禁每次运行都会把这份缺口连同理由打印出来，
补丁落地后对应行会变成 "stale" 而报错，提示删掉过期理由——**这是有意的**：
过期的豁免正是债务基线腐烂的方式。

### 文档体系

一键检查项目文档边界、链接、双语结构、固定约定、命令、空白和敏感信息：

```bash
./scripts/verify-project-docs.sh
```

执行文档 CRUD、外部全量同步、版本恢复、一次性 PostgreSQL、reference client 和 WebUI
验收：

```bash
./scripts/verify-document-lifecycle.sh
```

针对 V42/V51 Sync Run、一次性 PostgreSQL、认证权限与持久化 item receipt 完整 HTTP
合同的专项验收：

```bash
./scripts/verify-document-sync-runs.sh
```

该门禁会创建临时受限读写/只读 principal，并验证 ACL、游标分页、终态复扫语义、
失败回执恢复、`no-store` 与敏感信息保护；证据不保存 credential、cursor、external ID
或业务 payload。

针对按调用方隔离、跨 PostgreSQL/双后端实例/进程重启恢复的 Collection 创建持久化幂等：

```bash
./scripts/verify-collection-provisioning.sh
```

该门禁覆盖 V52 迁移与约束、精确 replay、key 语义复用冲突、owner 隔离、restricted
ACL、并发首次创建、软删除后的当前状态、恰好一次创建审计、账本故障关闭和不含 secret
的数据库事实。只复跑一次性双实例 HTTP 阶段可设置
`COLLECTION_PROVISIONING_VERIFY_PHASE=http`。

针对 V56 Collection 内容清理、永久 key tombstone、引用级联和 WebUI preview/apply：

```bash
./scripts/verify-collection-purge.sh
```

默认使用 Testcontainers 运行 5 个真实 PostgreSQL 场景；也可用
`COLLECTION_PURGE_IT_JDBC_URL`、`COLLECTION_PURGE_IT_USERNAME`、
`COLLECTION_PURGE_IT_PASSWORD` 和
`COLLECTION_PURGE_IT_CLEAN_CONFIRM=YES` 指向调用方提供的一次性数据库。脚本同时执行
聚焦后端、Maven clean 编译门槛、完整 WebUI、无截图 Collection Mock Playwright、
禁锁、文档、脚本语法和空白检查，证据写入
`.verification/collection-purge/<run-id>/`。

针对模型调用级持久用量账本及按 principal 隔离的聚合 API：

```bash
LLM_USAGE_LEDGER_VERIFY_RUN_ID=usage-ledger-gate \
./scripts/verify-llm-usage-ledger.sh
```

该门禁执行归因/recorder/API 聚焦测试，把一次性 PostgreSQL 从空库迁移到 V53，执行
完整 Maven 与 WebUI 门槛、禁悲观锁和项目文档规则，并执行不使用截图的 Metrics Mock
Playwright。证据写入 `.verification/llm-usage-ledger/<run-id>/`，不保存密钥或业务
内容。该脚本不调用真实 provider；专项门禁通过后，使用[测试指南](testing-guide-zh-CN.md)
中的真实 LLM 生命周期流程，在隔离服务和一次性数据库上验收。

针对 V43 本地关键词/向量派生解耦边界：

```bash
KEYWORD_VECTOR_VERIFY_RUN_ID=full-gate-4 \
KEYWORD_VECTOR_PLAYWRIGHT_PORT=4191 \
./scripts/verify-keyword-vector-decoupling.sh
```

该门禁要求真实 PostgreSQL 生命周期/全文集成测试、
`mvn clean compile test-compile`，以及 WebUI TypeScript、Vitest、production build、
alignment 和无截图 Mock Playwright 检查。

## 3. 启动与健康检查

前后端一键开发入口：

```bash
./scripts/dev.sh
```

在启动 Maven 之前，脚本会完整检查嵌入配置契约：
`RAG_EMBEDDING_API_KEY`、`RAG_EMBEDDING_BASE_URL`、`RAG_EMBEDDING_MODEL` 和
`RAG_EMBEDDING_DIMENSIONS`。默认行为是快速失败；缺少或格式错误的配置会在创建后端、
前端进程之前直接报告。只有明确设置 `RAG_EMBEDDING_STARTUP_CHECK=warn` 才会进入诊断
启动模式；该模式会输出醒目警告，但绝不会注入 mock 模型或占位凭据。

配置检查通过后，脚本才会完整导出仓库根目录 `.env` 给 Maven / Spring Boot，并为后端
放行本次精确的 Vite origin；只有 root 管理 POST 探针通过后才报告 ready。默认启动：

```text
Backend: http://127.0.0.1:18082
WebUI:   http://127.0.0.1:15173/webui/unlock
```

如果 `.env` 或调用环境未设置 `RAG_ROOT_API_KEY`，脚本会为当前后端进程生成临时 root
credential；macOS 默认复制到剪贴板，不写入文件或日志。状态、停止和端口覆盖：

```bash
./scripts/dev.sh --status
./scripts/dev.sh --stop
./scripts/dev.sh --force-kill
BACKEND_PORT=19082 FRONTEND_PORT=15174 ./scripts/dev.sh
RAG_DEV_OPEN_BROWSER=false ./scripts/dev.sh
```

默认启动遇到非本启动器管理的端口监听时会保守失败，不会误杀进程。仅在明确确认目标
`BACKEND_PORT` / `FRONTEND_PORT` 上的旧进程可以终止时使用 `--force-kill`；该参数只终止
这两个端口的监听进程及其子进程，先发送 `TERM`，超时后才发送 `KILL`，随后继续正常启动。

启动器绝不自动执行 Flyway repair。若启动时检测到迁移 checksum 不一致，它会直接输出
相关根因；正确处理方式是恢复已经执行过的迁移，并把后续变化放入新的迁移，而不是改写
schema 历史。

只启动后端：

```bash
bash scripts/start-server.sh
```

手动启动：

```bash
set -a
source .env
set +a
export SPRING_PROFILES_ACTIVE=postgresql
mvn spring-boot:run -pl spring-ai-rag-core -DskipTests
```

后端单独运行 `8081` 时的端口清理和健康检查：

```bash
lsof -ti :8081 | xargs kill -9 2>/dev/null
curl -fsS http://127.0.0.1:8081/actuator/health
```

Swagger：`http://127.0.0.1:8081/swagger-ui.html`

使用前后端一键启动器时，改用 `18082`：

```bash
curl -fsS http://127.0.0.1:18082/actuator/health
```

## 4. 数据库

- PostgreSQL 默认连接信息以 `.env` 为准。
- 必需扩展：`vector`。
- 推荐扩展：`pg_trgm`；`pg_jieba` 可选。
- 迁移目录：`spring-ai-rag-core/src/main/resources/db/migration/`。

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

优先使用已安装扩展的 Docker PostgreSQL。扩展说明见 [postgresql-extensions.md](postgresql-extensions.md)。

## 5. 模型配置

### Embedding

```text
Provider: SiliconFlow
Model: BAAI/bge-m3
Dimensions: 1024
Base URL: https://api.siliconflow.cn
```

### Chat Provider

| Provider | 配置入口 |
|----------|----------|
| OpenAI-compatible | `spring.ai.openai.*` |
| Anthropic | `spring.ai.anthropic.*` |
| MiniMax | `spring.ai.minimax.*` |

Provider 默认通过 `LLM_PROVIDER` / `app.llm.provider` 选择。多模型实例和外部配置见 [multi-model-external-config-zh-CN.md](multi-model-external-config-zh-CN.md)。

真实密钥只放 `.env`，不要写入命令历史、Markdown 或 Git。

## 6. WebUI

```bash
cd spring-ai-rag-webui
npm ci
npm run tokens:check
npm run lint
npm run test:run
npm run test:design-system
npm run build
```

### 6.1 设计 token 与设计系统门禁

设计 token 只有一个 canonical source：`design-tokens/tokens.json`。`tokens.css` 和
`tokens.generated.ts` 都是生成产物，**不要手改**：

```bash
npm run tokens:build          # 从 tokens.json 重新生成产物
npm run tokens:check          # 只比对不写盘，CI 用这个（产物过期即失败）
npm run check:design-system   # 设计债务门禁
npm run check:a11y-forms      # 表单可访问性门禁
npm run test:design-system    # 生成器与门禁的 focused 测试
```

- `check:design-system` 扫描 CSS/TS/TSX/SVG，拦截 10 类违规：未定义 `var(--*)`、
  数值 z-index、字面颜色（含 CSS 命名色）、宽泛的 `transition` 简写、非零 `letter-spacing`、
  无理由 `!important`、跨页 `*.module.css` import、兼容 alias 调用，
  `emoji-glyph`（拿 emoji/dingbat 当界面图标），
  以及 `css-syntax`（样式表解析不过）。
- `emoji-glyph` 覆盖象形符号区，以及浏览器仍会独立渲染的 dingbat 区段（箭头、
  尖角、几何图形、对勾/叉号、星号）和充当关闭按钮的 `×`。
  **规则运行前先掩码注释、但保留字符串字面量**，所以文档里可以用 `→` 讲数据流，
  而表达式里选出来的字形仍会被抓到。制表符族（box drawing）和中文标点是排版字符、
  不是图标，不在范围内。优先用可 tree-shake 的 `lucide-react` 组件；确实无法避免时，
  走和其他规则一样的行内 `design-token-allow` 窄例外。
- 存量债务记录在 `design-tokens/design-debt-baseline.json`，指纹为
  `file|kind|value`。**新增违规、计数增加、基线过期三种情况都会失败**，所以债务
  只能单调减少。基线文件**不存在**表示"没有债务"；**读不了或格式坏了**属于报错——
  读不动自己账本的检查器，没资格被信任去执行账本。
- `css-syntax` 是唯一**不可豁免**的一类：`design-token-allow` 对它无效。
  解析不过的样式表不是风格偏好问题。注意 `npm run build` 不再是唯一能发现它的地方——
  Vitest 会 stub 掉 CSS module，所以一个多余的 `}` 过去能同时通过 typecheck、lint
  和全部测试。
- 确有必要的窄例外用同行或上一行注释 `/* design-token-allow: <具体理由> */`；
  理由过短会被单独判为 `weak-allow-reason` 失败。不要用批量豁免换绿。
- `check:design-tokens` 保留为 `check:design-system` 的兼容入口。
- `check:a11y-forms` 扫描 `src/` 下每个 `.tsx`，拦截三类违规：没有可访问名称的表单
  控件、什么都不标的 `<label>`、以及键盘够不到的 `onClick`。placeholder 不是名称；
  光有 `role` 也不够——还必须声明 `tabIndex` 并处理按键。它没有债务基线，因为写下它
  时针对的每一条违规都能修。详见
  [webui-design-language-zh-CN.md](webui-design-language-zh-CN.md#5-表单可访问性)。
- `lint` 串联 ESLint、`check:alignment`、`check:design-system` 与 `check:a11y-forms`。
  `test:coverage` 另有按 `vitest.config.ts` thresholds 的全局覆盖率下限。

### 6.2 运行与联调

开发模式：

```bash
npm run dev
```

直接运行时默认监听 `http://127.0.0.1:15173/webui/`，并把 `/api` 代理到
`http://127.0.0.1:8081`。日常前后端联调优先从仓库根目录运行 `./scripts/dev.sh`，
由启动器统一端口和代理目标。

生产 bundle 由发布流程复制到：

```text
spring-ai-rag-core/src/main/resources/static/webui/
```

### 输入法（IME）安全交互

所有会搜索、提交、保存、改写 URL、启动 debounce 请求，或在 Enter 时触发其他动作的
WebUI 输入框，都必须把输入法（IME）组合过程视为中间态。这一规则适用于中文、日文、
韩文及其他输入法，不是 Chat 或 Documents 页面的局部特例。

共享的 `ImeSafeForm` 边界会阻止组合期间的意外表单提交；`Chat`、`Documents`、`Files`
`Search`、集合范围选择器和 `Embeddings` 过滤器则直接保护各自会触发动作的输入框。实现同时识别标准的
`nativeEvent.isComposing` 与兼容性较差但仍会出现的 `keyCode === 229` 信号。
`compositionend` 后保留最终输入值，用户下一次普通 Enter 才执行预期动作。

新增或修改会触发动作的输入框时：

1. 在输入框本地维护 `compositionstart`/`compositionend` 状态，或复用
   `useImeComposition`。
2. Enter 事件发现任一 IME 信号有效时，直接返回，不得提交、搜索、保存或导航。
3. 如果输入框通过 change 事件提交、改写 URL 或启动 debounce 请求，应延迟到
   `compositionend`，并只用最终值处理一次。
4. 为“组合期间被拦截”和“组合结束后正常动作”分别补 DOM 交互测试。截图不作为验收证据。

## 7. E2E

### 常规 HTTP E2E

```bash
bash scripts/start-server.sh
BASE_URL=http://127.0.0.1:8081 bash scripts/e2e-test.sh
```

### WebUI Playwright

```bash
cd spring-ai-rag-webui
npm run build
npx vite preview --host 127.0.0.1 --port 4173
BASE_URL=http://127.0.0.1:4173 npx playwright test
```

### 真实 LLM

```bash
./scripts/start-real-e2e-server.sh
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/real-llm-e2e-smoke.sh
```

该流程会执行 provider preflight、创建唯一文档、embedding、search、ask 和 stream。
如果配置了 `RAG_ROOT_API_KEY`，必须通过 `RAG_API_KEY` 或等价的 `X-API-Key` 传给
数据面请求；脚本也会自动从 `.env` 读取 root key。四个 `RAG_EMBEDDING_*` 配置项
均为必需项；退役的 `RAG_EMBEDDING_URL` 和 `SILICONFLOW_*` 不会被自动映射。
如果 `.env` 设置了 `MODELS_CONFIG_FILE`，要注意外部文件会完整覆盖 YAML 模型注册表。
若要执行不使用外部注册表的直接 provider smoke，可显式指定不存在的文件：

```bash
MODELS_CONFIG_FILE=/tmp/spring-ai-rag-no-external-models.json \
RAG_EMBEDDING_BASE_URL=https://api.siliconflow.cn \
SERVER_PORT=18181 \
./scripts/start-real-e2e-server.sh
```

Collection 受保护清理的真实 provider 生命周期使用
`scripts/real-collection-purge-e2e-smoke.sh`。它要求运行中的隔离服务和一次性数据库，
并验证事件优先嵌入、真实检索/Chat、purge/replay、退役拒绝与 tombstone；完整命令和
证据安全边界见 [测试指南](testing-guide-zh-CN.md#collection-受保护清理与退役验收门禁)。

本轮 Chat turn 幂等性验收使用独立的 PLAIN smoke，不要求 Embedding provider：

```bash
BASE_URL=http://127.0.0.1:18081 \
./scripts/real-llm-chat-idempotency-smoke.sh
```

该脚本强制使用 OpenAI-compatible Chat provider，验证原生 JSON/SSE 首次请求、相同
key 重放、key 冲突、turn 状态查询，并通过
`/actuator/metrics/rag.chat.provider.calls` 的前后计数证明重放没有再次调用 provider。
服务启动时仍应使用隔离 PostgreSQL 和独占端口；不要把 API key 写入命令行历史或文档。

### Chat 对话能力一键验证

该门禁包含 Maven clean 输出，必须串行执行：

```bash
./scripts/verify-chat-capability.sh
```

脚本会验证 `KNOWLEDGE`、`AGENT`、`PLAIN` 三种模式，Spring AI Tool Calling 边界，
principal 隔离的 Memory/历史，V32 会话 lease、V46 持久化摘要 CAS、V47 Chat turn 幂等重放、V48 stable managed principal 与共享 quota、有界执行 metadata、
结构化 SSE，WebUI 模式/能力/来源展示，以及 Chat 导出来源快照；同时执行
`NextHighValueFeaturesPostgresIntegrationTest` 矩阵和独立的领域扩展、只读 SQL
工具 demo 测试。每一步都会记录到
`.verification/chat-capability/<run-id>/summary.md`。

PostgreSQL/Testcontainers 默认配置：

```bash
TESTCONTAINERS_API_VERSION=1.40 \
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-chat-capability.sh
```

Docker 不可用时，脚本会把 PostgreSQL 门禁记录为 `SKIP`，不会伪称通过。显式使用
`--skip-postgres` 也必须在 summary 中保留。Docker API `1.32` 与 daemon 最低 `1.40`
不匹配的已知问题见 [china-network-guide-zh-CN.md](china-network-guide-zh-CN.md)。

Chat Mock Playwright 使用严格绑定且可覆盖的 Vite preview 端口：

```bash
CHAT_PLAYWRIGHT_PORT=4199 ./scripts/verify-chat-capability.sh
```

浏览器门禁只使用 DOM、网络、URL 和测试断言；截图不作为正确性证据。真实 Provider 调用
必须显式开启：

```bash
./scripts/verify-chat-capability.sh --with-real-llm
```

启用后，脚本会创建一次性 PostgreSQL 数据库，在隔离端口启动
`scripts/dev.sh`（默认后端 `18083`、WebUI `15175`），执行真实 WebUI
`chat-real.spec.ts` 和 provider smoke，最后清理服务、临时环境文件和数据库。
`.env` 或调用环境必须提供 `RAG_ROOT_API_KEY`；需要时可用
`CHAT_REAL_BACKEND_PORT`、`CHAT_REAL_FRONTEND_PORT` 覆盖端口。未开启时，
真实 LLM 步骤会明确记录为 `SKIP`。

### OpenAI 兼容一键验证

```bash
./scripts/verify-openai-compatibility.sh
```

该脚本验证 model alias、请求级 Collection scope/ACL、完整 text-only messages、
非流式 OpenAI JSON、兼容错误信封、SSE chunk 顺序和 `[DONE]`，并执行相关
`test-compile`、Shell 语法和空白门禁。日志写入
`.verification/openai-compatibility/<run-id>/`。兼容 Controller 默认关闭；运行服务时
需要显式设置 `RAG_OPENAI_COMPATIBILITY_ENABLED=true`。

该专项门禁主要是 MockMvc 与单元级合同测试，不启动隔离的真实 Spring Boot HTTP 服务，
也不调用官方 OpenAI SDK。它不能单独证明 Starter-only consumer 自动注册 `/v1`，或证明
第三方 Agent/IDE 的默认参数全部受支持。当前证据边界和 P0 改进项见
[OpenAI 兼容就绪度](openai-compatibility-readiness-zh-CN.md#44-当前验证证据与证据缺口)。

### 持久化 Embedding Jobs 一键验证

```bash
./scripts/verify-embedding-jobs.sh
```

脚本覆盖 service、worker、HTTP API、V33 migration、活动任务 coalesce 与双 worker
原子条件 claim。默认自动启动隔离的 `pgvector/pgvector:pg16` 容器；已有数据库时可用
`EMBEDDING_JOBS_IT_JDBC_URL`、`EMBEDDING_JOBS_IT_USERNAME` 和
`EMBEDDING_JOBS_IT_PASSWORD` 覆盖。验证日志位于
`.verification/embedding-jobs/<run-id>/`。

<a id="document-lifecycle-verification"></a>

### 文档生命周期一键验证

```bash
./scripts/verify-document-lifecycle.sh
```

该命令验证本地文档 create/PATCH/disable/restore/permanent-delete、外部 TEXT/JSON
`collectionKey + sourceNamespace + externalId`、revision CAS、完整快照、正文变化后的
generation-aware 重嵌入、非文本更新不重嵌入、WebUI CRUD 和 reference client。

脚本优先从当前 shell 或 `.env` 的 `POSTGRES_*` 创建一次性数据库，避免 Testcontainers
与新 Docker daemon 的协议协商问题；也可显式提供
`DOCUMENT_LIFECYCLE_IT_JDBC_URL`、`DOCUMENT_LIFECYCLE_IT_USERNAME` 和
`DOCUMENT_LIFECYCLE_IT_PASSWORD`。不得指向开发库或生产库。证据保存在
`.verification/document-data-plane/<run-id>/`。

### 文档迁移与派生完整性一键验证

```bash
./scripts/verify-document-relocation.sh
./scripts/verify-derivation-integrity.sh
```

两个专项门禁都执行禁悲观锁检查、聚焦 HTTP 测试、一次性 PostgreSQL 集成测试、
`mvn clean compile test-compile`、WebUI typecheck/Vitest/生产构建/alignment、无截图 Mock
Playwright、双语文档门禁和空白检查。证据分别写入
`.verification/relocation/<run-id>/` 与
`.verification/derivation-integrity/<run-id>/`。

默认使用 Testcontainers；也可通过 `NEXT_HIGH_VALUE_IT_JDBC_URL`、
`NEXT_HIGH_VALUE_IT_USERNAME`、`NEXT_HIGH_VALUE_IT_PASSWORD` 指向调用方创建的专用
一次性数据库，并必须显式设置 `NEXT_HIGH_VALUE_IT_CLEAN_CONFIRM=YES`。该测试会清空
数据库，绝不能指向开发库或生产库。可用 `NEXT_HIGH_VALUE_PLAYWRIGHT_PORT` 指定 Mock
Playwright 的 Vite preview 起始端口。

### 受管 API Principal 一键验证

```bash
MANAGED_API_REAL_ENV_FILE=.env \
MANAGED_API_REAL_LLM_PROVIDER=minimax \
./scripts/verify-managed-api-principals.sh --with-real-llm
```

该门槛在 Mock 与构建检查通过后，使用两个后端（默认 `18181`、`18182`）、一个 Vite
前端（默认 `15181`）和一次性 PostgreSQL 执行真实全栈及有界真实 LLM 验收。V55 矩阵
验证跨实例 provisioning 幂等、运行时能力发现、NORMAL principal 的只读/读写能力、
策略 CAS、staged prepare/replay/complete/cancel/deadline/family revoke、overlap 共享
quota，以及写请求在限流前返回 `403`。真实 LLM 模式要求即时数据访问和 staged
complete/cancel/revoke 生命周期共 9 次成功 provider 调用，同时 replay 和拒绝请求不能增加
provider counter。端口冲突时可
分别覆盖 `MANAGED_API_BACKEND_A_PORT`、`MANAGED_API_BACKEND_B_PORT` 和
`MANAGED_API_FRONTEND_PORT`。`MANAGED_API_REAL_LLM_PROVIDER` 支持 `openai`、
`minimax` 和 `anthropic`，脚本只校验并装载所选 provider 的配置；证据位于
`.verification/managed-api-principals/<run-id>/`。

### 受管 API Principal 到期告警一键验证

```bash
# 聚焦后端、V1-V59 PostgreSQL 与前端 Mock 门槛
API_KEY_EXPIRY_ALERT_VERIFY_PHASE=focused \
./scripts/verify-api-key-expiry-alerts.sh

# 加上 Maven clean、禁锁、文档、shell、diff 与新增行密钥扫描
./scripts/verify-api-key-expiry-alerts.sh
```

脚本覆盖到期配置校验、事务 after-commit Spring Event、异步代理合同、create/update/revoke
生命周期、operator-only Alerts API、通知渠道、V57 多实例 dedupe/CAS、阶段升级、自动解决、
公平 fallback scan、WebUI `firedAt` 与无截图 Alerts Mock Playwright。默认使用
`pgvector/pgvector:pg16` Testcontainers；也可通过
`API_PRINCIPAL_EXPIRY_ALERT_IT_JDBC_URL` 等变量指向明确允许清空的一次性数据库。证据写入
`.verification/api-key-expiry-alerts/<run-id>/`。

### 告警通知 Durable Outbox 一键验证

```bash
./scripts/verify-alert-notification-delivery.sh

MANAGED_API_REAL_ENV_FILE=.env \
MANAGED_API_REAL_LLM_PROVIDER=openai \
./scripts/verify-managed-api-principals.sh \
  --with-real-llm \
  --with-durable-notifications
```

第一条命令使用隔离 PostgreSQL、真实本地 HTTP provider、双后端实例和真实 WebUI，覆盖
Event 首投、transient retry、单 attempt 单 HTTP 调用、进程退出/过期 lease 恢复、低敏
receipt 和无截图 DOM/network Playwright。第二条在全部本地门槛通过后再实际调用 `.env`
中的 Chat/Embedding 服务，并让受管 principal 的 WARNING/CRITICAL 告警经过 V58 durable
delivery。证据分别位于 `.verification/alert-notification-delivery/<run-id>/` 和
`.verification/managed-api-principals/<run-id>/`。

<a id="业务服务接入就绪一键验证"></a>

### 业务服务接入就绪一键验证

```bash
./scripts/verify-business-client-readiness.sh
```

该门槛从 focused API/core 测试开始，串行创建一次性 PostgreSQL 集成测试数据库，执行
`mvn clean compile test-compile`、WebUI typecheck/Vitest/生产构建、核心 Mock
Playwright、文档/禁锁/密钥/diff 检查，最后启动一次性 PostgreSQL、确定性 embedding
stub、真实 Spring Boot 和真实 Vite 前端，运行通用业务 credential HTTP 合同与真实 API
Key Playwright。

只复跑真实服务阶段：

```bash
BUSINESS_CLIENT_VERIFY_PHASE=real \
./scripts/verify-business-client-readiness.sh
```

最终候选 commit 要求 Git tree 干净：

```bash
BUSINESS_CLIENT_REQUIRE_CLEAN_GIT=true \
./scripts/verify-business-client-readiness.sh
```

默认端口为后端 `18084`、embedding stub `18085`、Mock 前端 `15184`、真实前端 `15185`；
可分别用 `BUSINESS_CLIENT_BACKEND_PORT`、`BUSINESS_CLIENT_EMBEDDING_PORT`、
`BUSINESS_CLIENT_MOCK_FRONTEND_PORT`、`BUSINESS_CLIENT_REAL_FRONTEND_PORT` 覆盖。
PostgreSQL 镜像可用 `BUSINESS_CLIENT_POSTGRES_IMAGE` 覆盖。证据写入
`.verification/business-client-readiness/<run-id>/`，private credential 文件、容器、
端口和进程由退出 trap 清理。真实 HTTP 合同包含只读/canary binding preflight、运行时
限制强制、按 principal/Collection 隔离的 operation observability、重启持久化，以及
provider `503` 后的 Record 保留语义。`release-manifest.json` 锁定完整 Git SHA、初始
tree state、项目/OpenAPI 版本、API base path、最新 Flyway migration、passed steps、
PostgreSQL image、HTTP 检查数、已验证 credential 画像，以及实测 JSON batch
item/payload 上限与 operation-observability 状态；未到达的运行时事实为 JSON `null`，
不记录 credential、URL、payload、external ID 或 private path。

也可以对已经运行的实例单独执行已部署 binding runner：

```bash
./scripts/business-client-binding-preflight.sh
```

它默认只读。`RAG_BINDING_*` 输入见[业务服务接入指南](business-client-integration-zh-CN.md)；
`RAG_BINDING_MIN_JSON_BATCH_ITEMS`、
`RAG_BINDING_MIN_JSON_BATCH_PAYLOAD_BYTES` 与
`RAG_BINDING_REQUIRE_OPERATION_OBSERVABILITY` 可以增加 fail-closed 的运行时要求。
Mutation 模式必须使用专用 canary Collection，并且机器报告不包含 credential、URL、
Collection key、external ID 或 payload。

本门禁验证真实 Spring AI embedding HTTP 路径，但本能力不改变 Chat，因此不调用 Chat
LLM。接入契约和部署 binding 见
[业务服务接入指南](business-client-integration-zh-CN.md)。

### 检索诊断 / metadata 过滤 / 嵌入运营 / 受管质量

```bash
./scripts/verify-retrieval-diagnostics.sh
./scripts/verify-retrieval-filters.sh
./scripts/verify-embedding-operations.sh
./scripts/verify-managed-quality.sh
./scripts/verify-no-pessimistic-locks.sh
# 或一次跑完 A–D：
./scripts/verify-next-high-value-features.sh
```

这些脚本分别覆盖 V35 诊断、V36 metadata `@>` 下推、V37 embedding 运营分页/readiness，
V38 受管 suite 与 citation 校验，以及 V39 后的数据访问并发规则。禁锁脚本静态拒绝
`FOR UPDATE`、`SKIP LOCKED`、JPA `PESSIMISTIC_*` 和 PostgreSQL advisory lock；
其余脚本默认启动隔离 PostgreSQL，可用对应 `*_IT_JDBC_URL` 覆盖。

### JSONB 结构化记录一键验证

运行 JSONB 实现及其 API、数据库、WebUI、文档和空白检查的可重复门禁：

```bash
./scripts/verify-jsonb-records.sh
```

只有在浏览器依赖不可用时才使用 `--skip-playwright`，并在验证记录中明确记载跳过。
脚本默认自动启动隔离 PostgreSQL，绕开 Testcontainers 1.20.4 与新 Docker daemon API
协商不兼容；也可通过 `JSONB_IT_JDBC_URL`、`JSONB_IT_USERNAME`、
`JSONB_IT_PASSWORD` 复用调用者提供的隔离数据库。镜像可用
`TESTCONTAINERS_PG_IMAGE` 覆盖。日志和 Markdown 汇总写入
`.verification/jsonb-verification/<run-id>/`。
Mock Playwright preview 使用 `JSONB_PLAYWRIGHT_PORT`（默认 `4174`），并启用严格端口绑定，
不会复用无关进程。如果端口已被占用，请指定空闲端口，例如：

```bash
JSONB_PLAYWRIGHT_PORT=4199 ./scripts/verify-jsonb-records.sh
```

该门禁必须串行执行：其中的 `mvn clean` 不能与使用相同模块 `target/` 目录的其他 Maven
测试进程并发运行。

### JSONB 真实 HTTP E2E

在已经启动的 PostgreSQL profile 服务上执行 JSON structured-record 的真实 HTTP 链路：

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/jsonb-records-e2e.sh
```

脚本会验证 JSON record upsert、collection-scoped search、detail、payload-only 更新、
`retrievalText` 更新、clone/export/import，以及使用 root 创建临时受限 API Key 后的
允许/拒绝范围。`embed=true` 会调用真实 embedding provider；不会调用 Chat LLM。
需要跳过 ACL 时必须显式使用 `--skip-acl`，并把该事实记录在验证结果中。脚本不会打印
API Key 或完整 payload，临时响应写入被忽略的 `.verification/jsonb-e2e/` 后清理。

<a id="external-document-synchronization-http-e2e"></a>

### 外部文档同步真实 HTTP E2E

在已经启动的 PostgreSQL profile 服务上运行普通外部文档同步流程：

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/external-documents-e2e.sh
```

脚本会验证 `embed=false` 创建、精确重放、带
`expectedSourceRevision` 的更新、CAS 冲突、同 revision 冲突、批量 upsert、按外部身份
查询、tombstone 删除与重放，以及使用不同后续 `sourceRevision` 恢复。默认还会验证内容变化后的成功重新
embedding。只有 embedding provider 确实不可用时才设置
`EXTERNAL_DOCUMENT_E2E_EMBED=false`；此时脚本会明确记录 embedding 检查被跳过，不会
伪报 embedding 完成。日志写入被忽略的 `.verification/external-documents-e2e/`，脚本不会
输出 API Key 或完整文档内容。

## 8. Goldenset 与发布门禁

检索 goldenset：

```bash
BASE_URL=http://127.0.0.1:8081 ./scripts/run-retrieval-goldenset.sh
```

版本化真实检索回归：

```bash
BASE_URL=http://127.0.0.1:18081 ./scripts/verify-quality-regression.sh
```

rerank 文档多样化专项验收会聚合后端测试、PostgreSQL/pgvector、WebUI 门禁、隔离
`dev.sh`、真实 Search/Playwright、goldenset、版本化回归和真实 LLM：

```bash
./scripts/verify-rerank-document-diversity.sh
```

runner 会拒绝覆盖已有 `.dev` 栈，默认使用隔离端口 `18083`/`15175`，创建一次性
PostgreSQL 数据库（优先本机，失败时回退 Docker），生成的 root key 只保存在 shell，
证据写入 `.verification/rerank-document-diversity/`。真实 provider baseline 通过后，
runner 会在同一测试库和夹具上依次用 cap=`0`、cap=`2` 重启服务，默认各采集 20 个 Search
和 5 个 Chat 固定样本。它通过 trace ID 只读关联 `rag_retrieval_logs`，把 retrieval/rerank
p95、HTTP 响应 payload 和最终文档覆盖写入 `runtime-comparison.json` /
`runtime-comparison.md`；这些墙钟与 payload 数据是观测证据，不是易波动的阈值门禁。

关联到的数据库结果数表示 latest retrieval outcome 数量。Search 要求它与最终 HTTP
结果数相等；KNOWLEDGE Chat 的 HTTP sources 经过 advisor 的 query join、rerank 和
prompt budget 后处理，因此运行时产物单独记录两者关系，不强制把不同阶段的数量视为相同。

Chat 样本只对明确的瞬时 HTTP `429/502/503/504` 做有界重试，正整数上限由
`RERANK_DIVERSITY_CHAT_MAX_ATTEMPTS` 控制（默认 `2`）。每次重试都会输出日志；Search
和不可重试失败仍立即失败。

数据集和提交的 baseline 位于 `testdata/regression/`。runner 使用稳定
`collectionKey + sourceNamespace(default) + externalId` 身份创建 fixture，检查 Hit Rate、MRR、Recall@K、nDCG、
minimum、相对 baseline 回退、Collection decoy 泄漏和 JSONB 明确空结果，并把 JSON
artifact 与 Markdown 汇总写入 `.verification/quality-regression/<run-id>/`。未显式设置
`RAG_API_KEY` 时会安全读取 `.env` 的 `RAG_API_KEY` / `RAG_ROOT_API_KEY`，不会输出密钥。
可用 `./scripts/run-retrieval-regression.sh --self-test` 在不启动服务时检查 runner 对当前
`READY` 及兼容 `COMPLETED/CACHED` embedding 成功态的判定。

发布级一键验证：

```bash
./scripts/verify-release.sh
./scripts/verify-release.sh --with-quality-regression
./scripts/verify-release.sh --with-local-runtime
```

`--with-quality-regression` 对已经启动的 `BASE_URL` 追加版本化回归；
`--with-local-runtime` 默认包含 HTTP E2E、goldenset、质量回归和真实 LLM smoke。

日志和汇总写入 `target/release-verification/<run-id>/`。门禁详情见 [release-checklist-zh-CN.md](release-checklist-zh-CN.md)。

## 9. Docker 与境内网络

境内优先：

```bash
./scripts/docker-build-local.sh
```

Dockerfile 基础镜像保持可覆盖，不硬编码区域源。DaoCloud、阿里云 Maven、npm、Playwright 和 Git 代理经验见 [china-network-guide-zh-CN.md](china-network-guide-zh-CN.md)。

## 10. 关键路径

| 路径 | 用途 |
|------|------|
| `spring-ai-rag-api/` | DTO、SPI |
| `spring-ai-rag-core/` | 核心实现和可运行应用 |
| `spring-ai-rag-starter/` | 自动配置 |
| `spring-ai-rag-documents/` | 文档处理 |
| `spring-ai-rag-webui/` | React 管理台 |
| `scripts/` | 启动、E2E、goldenset、文档与发布验证 |
| `docker/` | Dockerfile 和 Compose |
| `k8s/` | Helm Chart |

## 11. 排障入口

- 通用排障：[troubleshooting-zh-CN.md](troubleshooting-zh-CN.md)
- 配置参考：[configuration-zh-CN.md](configuration-zh-CN.md)
- 境内网络：[china-network-guide-zh-CN.md](china-network-guide-zh-CN.md)
- Claude Code + grok：[claude-grok-proxy-zh-CN.md](claude-grok-proxy-zh-CN.md)
