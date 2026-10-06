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
| Flyway | V1–V60 |

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
./scripts/verify-project-tests.sh   # 跑完整的测试侧门禁链；它会打印实际跑了多少条
```

给套件加门控是一份"总有人能把它打开"的承诺。`PdfImportPostgresIntegrationTest`
——2 个测试方法——的开关在任何脚本、任何文档里都没出现过，只在一份已归档的进度记录里，
于是没有任何东西能跑它。门禁在以下情况失败：受门控的开关在任一方向上缺少运行路径；
运行路径引用了没有测试类消费的开关；受门控的类不声明任何 `@Test`；
`verify-gated-it.sh` 清单条目指向已删除的类，或它传的开关与该类实际受控的开关对不上；
以及**没有任何聚合 runner 会跑的门控套件**。最后一条是前四条表达不了的问题：
本仓库每个门控开关都有运行路径，而 23 个套件里仍有 20 个没有一条能跑完它们的命令，
门禁却是绿的。新增受门控的套件就要同时给出它的运行路径**和**它的 `ALL_SUITES` 条目；
`scripts/test-support/integration-switch-self-test.mjs` 证明这五条检查都还能拒绝。

### 门禁清单与门禁普查

`scripts/gate-registry.mjs` 登记了本仓库每一个门禁脚本，`scripts/verify-gate-wiring.mjs`
对它做普查。登记不是形式：Batch 809 在这份清单之前量到 **21 个门禁/入口里有 13 个在
`docs/` 下查无一处**，包括 9 个 WebUI 检查里的 7 个——`check:mutation-errors` 和
`check:query-errors` 这样的东西根本没人知道它存在。

| 门禁 | 拒绝什么 | 自测 | 跑在哪 |
|------|----------|------|--------|
| `verify-flyway-version-pinning.mjs` | 选中 Flyway 最新版本、却拿它去和一个写死的整数比对的验证脚本。`verify-alert-notification-delivery.sh` 把 `58` 写进了期望事实串，而 V59 早就发布了——于是一条本来正确了一个月的验收开始失败，失败信息里没有任何读者能据以行动的事实；而它打印的 `database_facts migration=…` 那行，**把过期数字当成数据库说的报了出来**。**两个兄弟脚本早就在从 `db/migration` 算它了**（`verify-collection-provisioning.sh`、`verify-managed-api-principals.sh`），所以正确写法本来就存在，只是这一个脚本没有采用——这才让这条规则可以零容忍、不需要任何豁免表。在注释里或文件名里提到版本号不算违规，只有**拿它做断言**才算。自测喂给规则的正是**它为之而写的那份脚本修复前的文本**，因为一个从没被展示过自己要抓的实例的扫描器，什么也不能证明 | `test-support/flyway-version-pinning-self-test.mjs` | tests 链 |
| `verify-capability-protocol-pinning.mjs` | 拿能力协议版本去和一个写死的数字串比对的脚本。服务端只在 `IntegrationCapabilityCatalog.CONTRACT_VERSION` 一处定义它，而 6 个 shell 脚本各自抄了一份，**其中 2 份已经漂移到服务端根本不发布的值**：`business-client-contract-e2e.sh` 要求 `1.0`，可它检查的那份报告的**生产者**（`business-client-binding-preflight.sh`）恰恰拒绝在别的值下写出成功报告——于是这条断言**对任何报告都不可能成立**（失败报告是 `null == "1.0"`，成功报告是 `"1.1" == "1.0"`），`verify-business-client-readiness.sh` 因此在本工作树**从未跑完过**；2026-10-06 的第一次重跑就停在它上面，17 步里前 16 步是绿的。`verify-document-sync-runs.sh` 读同一个端点、要求同一个 `"1.0"`，上次运行是 2026-08-20。**漂移的副本不是一个过期的数字**——改对那两处只会留下 5 份正确副本继续漂，而 6 个脚本之间没有任何共同执行会发现。所以字面量现在住在 `scripts/lib/business-client-capability.sh`，由 `verify-capability-protocol-pinning.mjs` 保证它是唯一出现的地方。**这条规则没有 allowlist**：与它共用字段名的信封协议 `generic-client-record-mutation-v1`（是名字不是版本号）、定义该值的共享库、故意制造不匹配的 `test-support` 夹具、以及照抄了坏行并加以解释的注释——四者都因为**它们是什么**而不是**它们在哪**被排除 | `test-support/capability-protocol-pinning-self-test.mjs` | tests 链 |
| `verify-port-probe-authority.mjs` | 用**绑定一个临时套接字**来判断端口是否空闲、且没有 source `scripts/lib/port-probe.sh` 的脚本。这和它要探的那个服务问的**不是同一个问题**，而且在这台机器上两个答案直接打架：`lsof` 看到 `python3` 监听在 `*:4173`（IPv6 通配），`node` 往 `127.0.0.1:4173` bind **却成功**，于是 `verify-release.sh` 把「4173 是空的」这个结论直接交给 `vite preview --strictPort`，服务死在**它刚刚认证为空闲的那个端口**上——而这个脚本在本工作树从未跑完过。另有 6 个脚本带着同一个探针、同一个潜在的洞。本仓库里**已经有 7 个脚本**在用权威问法 `lsof -nP -iTCP:<port> -sTCP:LISTEN`，那就是这个库现在持有的形状。规则找的是**被 bind 的套接字**，不是 `node` 这个词，所以那些用 node 解析 JSON、写证据文件的脚本不受影响 | `test-support/port-probe-authority-self-test.mjs` | tests 链 |
| `verify-playwright-suite-selection.mjs` | 既不点 spec 也不给 `--config` 的裸 `npx playwright test`。`playwright.config.ts` **没有** `testIgnore`，两个 preview 配置才有 `testIgnore: ['**/*-real.spec.ts']`——所以**命令的形状**决定这次跑会不会吃进那 5 个需要真实后端和凭据的 spec。`verify-jsonb-records.sh` 和 `verify-release.sh` 各自起一个 `vite preview`（背后没有后端的静态服务），然后跑了裸调用：**93 passed、5 failed**，而且两个脚本在本工作树**从未跑完过**，所以「这一步红在一个没人能据以行动的点上」这件事一直没人记录。19 处调用里 17 处点了 spec，不归这道门禁管——**故意**点名 `alerts-real.spec.ts` 正是 alerts 验收的全部意义 | `test-support/playwright-suite-selection-self-test.mjs` | tests 链 |
| `verify-surefire-method-selection.mjs` | 按**方法名**选择 surefire 测试、却不先确认这些方法还存在的脚本。surefire 把 `-Dtest=Class#a+b` 读作「`a` 和 `b` 里有的都跑」，而本仓库有 20 个脚本传 `-Dsurefire.failIfNoSpecifiedTests=false`——对**整类**选择来说这是对的（类不存在就不会有报告，而每个脚本都已经在查报告），但方法名不一样，**它是静默的**。`verify-next-high-value-feature.sh` 的**两个分支**都点名了一个已被改名的方法（`migrationsCreate…` → `latestMigrationsCreate…`）：自 2026-08-21 起的第一次运行只跑了 7 个里的 6 个，而全仓库唯一察觉到这件事的是那个写死在名单旁边的计数，它把**6 条全过、0 失败 0 错误 0 跳过**的一次运行报成「must run 7 tests」，既没点名缺哪个方法，也没提改名这件事。检查放在 `scripts/lib/surefire-method-selection.sh`（理由同 894 把报告读取搬进去），门禁管的是**接线**——「这个脚本有没有校验方法名」读脚本读不出来，「它有没有 source 那个库」读得出来。规则要求引号开头（或 `-Dtest=` 之后）紧跟`标识符#`，于是真实树上的 6 处 shell 参数展开（`${version#1.}`、`${suite##*:}` 等）**因为 `#` 在那里是运算符而不是分隔符**被排除，而不是因为它们在别的文件里 | `test-support/surefire-method-selection-self-test.mjs` | tests 链 |
| `verify-embedding-state-generation.mjs` | 生产代码里 INSERT `rag_document_embedding_state` 时**漏掉** `request_generation`、或把这一列赋成裸字面量的写法。`DerivationIntegrityRepository` 要求 `vector_generation > 0` 才承认向量新鲜，所以不写这一列的行会吃列默认值 0，于是一个嵌入正确的文档——状态 `COMPLETED`、hash 对得上、chunker 对得上、向量在场——被归成 `CORRUPT`，对外是 `embeddingStatus=FAILED` 且 `error=null`。实测本地库 82 行里有 69 行如此，`run-retrieval-regression.sh` 在第 3 个夹具上以 `status=FAILED error=None` 中止。规则读 Java 时剥注释但保留字符串字面量，因为 SQL 是用 `+` 跨行拼的：表名与列清单之间隔着闭引号、换行、加号和开引号，只允许空白的写法在本仓库**一条都匹配不上**，而那和「树是干净的」长得一模一样。迁移那一半要求必须有迁移声明 `CHECK (request_generation > 0)`——写入方正确和 schema 无法反对是两件不同的事，只有后者是永久的。测试树**刻意不在范围内**：那里有 2 个夹具故意省略（一个用来复现 V42 之前的数据形状，一个用来断言新默认值），而 V60 之后默认值已经是完整性仓库读得懂的 1 | `test-support/embedding-state-generation-self-test.mjs` | tests 链 |
| `verify-test-visibility.mjs` | 既没执行也没声明跳过的测试类（`tests="0" skipped="0"`）。判据是**双向**的：每个匹配 surefire 四种默认 include（`Test*` / `*Test` / `*Tests` / `*TestCase`——Batch 885 补上了缺失的 `Test*`）的非抽象测试源都必须产出报告，每份报告也必须能对回某个源文件，所以"源码已删、`TEST-*.xml` 还在"的类无法虚增总数。**没有声明任何 JUnit 测试方法的源不进入清单**，因为 surefire 根本不给它出报告。**一次运行只覆盖一个模块**：源码根目录是从报告目录反推的，聚合入口把门禁指向 `spring-ai-rag-core`；成功信息会点明本次覆盖的是哪个模块。用例数本身是**数 `<testcase>` 元素得来的，不是读 `tests=` 属性**（Batch 893）。surefire 写这个属性时，`@Nested` 内部类贡献的用例还没并进去，所以它可能比自己汇总的子元素还小：四个模块 1006 份报告实测有 3 份不一致——`RagCollectionServiceTest` 差 7（声明 17、实际 24 条，**全部来自嵌套类**），`DocumentMapperTest` 差 1，`GeneralRagAutoConfigurationBeanTest` 差 1——属性求和 8320，而真实跑了 8329 条。**打印这个数的正是这道门禁**，所以它把自己这棵树少报了 9 条。`skipped` / `failures` / `errors` 仍读属性，因为 1006 份报告里没有一份在这三项上不一致；**没有实测支撑就改计数器，本身就是另一类缺陷** | `test-support/test-visibility-self-test.mjs` | tests 链 |
| `verify-integration-test-switches.mjs` | 门控开关与运行路径的双向缺口；Batch 912 起还包括**只有单个 feature 自己的脚本能跑**的门控套件——这是前四条表达不了的方向：本仓库每个门控开关都有运行路径，23 个套件里仍有 20 个没有一条能跑完它们的命令，而门禁是绿的 | `test-support/integration-switch-self-test.mjs` | tests 链 |
| `verify-external-db-safety.mjs` | 接受调用方指定库名却直接 `flyway.clean()` 的套件 | `test-support/external-db-safety-self-test.mjs` | tests 链 |
| `verify-e2e-run-paths.mjs` | 没有任何脚本能运行的 Playwright spec | `test-support/e2e-reachability-self-test.mjs` | tests 链 |
| `verify-slo-endpoint-coverage.mjs` | 配了阈值却已不存在的端点；跨 controller 重名的 timer | `test-support/slo-endpoint-coverage-self-test.mjs` | tests 链 |
| `verify-gate-wiring.mjs` | 未登记、无自测、无人执行、CI 到不了的自动化门禁；Batch 900 起还包括**理由里指向不存在路径**的 standing gap。这一条是别处都抓不到的形状：registry 本来就校验它那些机器可读的路径字段，而 standing gap 是这个仓库里**只有一个人能填的洞**，所以死指针不会报错，它只是意味着永远没人能填。当时 16 条理由全都指向 `/tmp/b806-ci-gates.patch`，而那个文件已经没了。token 必须含斜杠才算路径，所以裸脚本名不会被拿去跟猜出来的目录比；还必须含点或以斜杠结尾，所以 `text/plain` 和 `2026/10/05` 是散文。两条限制都由自测钉住，本仓库自己的理由可解析也钉住了。**Batch 913 起**还包括：`entrypoint` 提交时**没有可执行位**。entrypoint 是唯一一种人会当路径直接敲的类别，所以也正是唯一一种"模式位决定文档里那条命令能不能用"的类别——而 `verify-webui-e2e-mock.sh` 提交为 100644，它的三个同级都是 100755，且这道门禁对它检查的其它每一项都是正确的。`kind: 'gate'` 与 `kind: 'manual'` 故意豁免：每个自动化门禁都是以 `node scripts/verify-*.mjs` 调用的（**不写死数量**——散文里的计数没有任何东西会重算，Batch 924 加门禁之前它就已经差 1 了），它们是 100644 正因为如此；而 `scripts/lib/` 下两个**被 source 的**库文件也带 shebang 且是 100644，所以"有没有 shebang"会是错误的判据 | `test-support/gate-wiring-self-test.mjs` | tests 链 |
| `scripts/lib/python-assertion-check.mjs` | `verify-json-assertions.mjs` 的 Python 那一半——在 Batch 899 之前这条规则只读 shell。Batch 898 早已在 Python 里找到同一个缺陷，而且是在唯一职责就是抓回归的那道门禁里；它被发现只是因为有人读了一遍那个脚本，这正是"只读一种语言"的实际含义。报出这样一行：falsy 兜底喂给了一个另一边同样 falsy 的比较——这是"读不到"和"本来就对"给出同一个答案的唯一形状。加门禁之前先普查：97 个源 / 4147 行 / 88 处 falsy 兜底，**危险窄形状 0 处**——所以它是门禁而不是基线。`not in (field or "")` 故意不算：它是 fail closed | `test-support/json-assertions-self-test.mjs` | tests 链 |
| `verify-gate-entry-points.mjs` | 自己动手判断"我是不是入口"的门禁脚本。本来有 22 个在各自判断、四种写法，其中 **18 个在被符号链接路径点名时输出 0 字节、退出 0**——ESM loader 算 `import.meta.url` 时会解析符号链接，而 `process.argv[1]` 保留调用方原样写的路径，两边对不上，`main()` 根本不执行。`resolve()` 帮不上忙：它把路径变绝对，**不跟随链接**，这正是第二种写法搞错的地方。registry 那条"每个门禁都要有自测"是靠 **import** 模块来兑现的，所以它结构上看不见一个根本没跑起来的门禁。同时要求 helper 的两份拷贝逐字节一致——webui 包引不到仓库那份，而没人比对的镜像就是等着发生的第二份实现。**限制说明**：完全没有守卫的门禁（`verify-json-assertions.mjs` 就是）能过这道门禁，那是另一个问题。**影响面，说明是因为容易被夸大**：Node 会把 `process.cwd()` 解析成物理路径，所以在符号链接目录下用相对路径调用一直是安全的，聚合链从未中招；真正的暴露是用穿过符号链接的路径点名脚本 | `test-support/gate-entry-points-self-test.mjs` | tests 链 |
| `scripts/lib/report-destruction-check.mjs` | 判断一个跑 Maven `clean` 的 `scripts/*.sh` 会不会把 surefire 报告一起带走，以及它有没有说明。`verify-test-visibility.mjs` 拿 `target/surefire-reports` 和测试源码树双向对账，所以门禁链的正确性取决于最后碰过 `target/` 的东西留下了什么。加规则之前先普查：**12 个脚本跑 `mvn clean`，其中 5 个会补一次不限范围的 `mvn test` 把报告放回来，7 个不会而且一个字都没说**——跑完其中一个再跑门禁链的人会看到 `No surefire reports at …`，而那句话读起来像是他自己弄坏了什么。两条出路都合法，所以规则两条都认，7 个脚本各自在自己的头注释里加了一行，而不是在 registry 里养一份已知违规名单。**声明判据刻意做成两段式**：提到 `scripts/lib/surefire-report.sh` 的是三个脚本在讲共享一个报告**读取器**，它们当中任何一个只要把那句挪到头注释里就能蒙混过关而什么也没改。goal token 的形状也各自改写了一遍：注释里列出 `mvn clean` 不算调用；`mvn clean test` 是一条既删又补的命令，所以两个判断不能写成 `else if`；而 shell 里写的是 `mvn test;` 和 `{ mvn test; }`，只认空白结尾的 goal 会正好漏掉"让脚本自愈"的那一种形状 | `test-support/report-destruction-self-test.mjs` | tests 链 |
| `scripts/lib/java-source.mjs` | 四道门禁剥 Java 注释的**唯一**写法。它们本来有四份、三种行为，其中两份是不认识字符串字面量的正则，于是字符串里的 URL 被当成注释。实测（未改任何代码）：**这棵树上 316 个字符串字面量被那个写法摧毁**，且 9 个文件里字符串中的 `/*` 会让匹配一路吃到下一个 `*/`——其中一个文件从 369 行被削到 251 行。**看不见三分之一文件的门禁，不是在读那个文件。** 扫描是逐字符跟踪引号状态的；并且保留长度和每一个换行，因为 `verify-error-code-catalog` 靠数行号把发现映射回原文件。自测最后一条用例会在任何门禁又开始自己写一份时失败 | `test-support/java-source-self-test.mjs` | tests 链 |
| `scripts/lib/is-main-module.mjs` | "这是不是正在被运行的程序"的**唯一**写法，也是自测用行为证明的那个修复：两边都先归约成真实路径，所以调用方写的路径里任何位置有符号链接都还是能匹配上。它存在是因为本来就有 4 个文件写对了，而**正是这份重复造出了另外 18 个** | `test-support/gate-entry-points-self-test.mjs` | tests 链 |
| `scripts/lib/reason-pointer-check.mjs` | 从 `noCiReason` 里提出路径形状的 token，好让 `verify-gate-wiring.mjs` 逐个拿去磁盘上核对。单独拆出来是因为"什么算路径"本身是一个判断——第一版把 registry 里每个路径形状的 token 都解析了一遍，报出 6 个不存在的，其中 4 个是正则常量、1 个是 Batch 809 故意删掉的脚本——而这个判断值得自己拥有用例，而不是塞在一个 350 行的检查器里 | `test-support/gate-wiring-self-test.mjs` | tests 链 |
| `verify-test-expectations.mjs` | 断言为空的 `@Test`，覆盖测试变成空转的所有形态。**方法体为空**（或只有注释）每次运行都记为**通过**，虚增通过数却不证明任何东西。**`private` 或 `static`** 则 JUnit 5 根本不运行，而且这是更安静的一种：它在报告里既不记通过也不记跳过，**就是不出现**，构建照常变绿而用例数悄悄变少（Batch 887）。第二条判据从声明**正上方逐行**取注解，而不是从"两个声明之间的窗口"取——那个窗口以上一个声明的**参数表**为界，于是私有 helper 会继承它前面那条测试的 `@Test`（真实树实测 743 个这样的 helper）。两条判据的可靠性都取决于底下的解析器，而它错过两次。一是 body 提取会跳过字符串字面量、**却不跳过注释**：注释里 `caller's` 的撇号被当成字符串定界符，扫描一路找不着的配对引号就跑了出去，于是**跨 14 个文件的 18 个真实 `@Test` 方法连 body 都没取到**——两条判据都看不见它们，里面要是有空 body 的就会一路绿灯（Batch 888）。二是它把 `new ClientFixture(...)`、以及**每一条控制流语句**都当成方法声明——`if` 块没有返回类型可吃，落在名字位置上的就是 Java 关键字，**12150 个"声明"里有 579 个是语句或匿名类**（Batch 890），每一个都贡献了一段被当成方法体的块。现在先跳注释再谈引号；名字是语句关键字、或者返回类型槽写着 `return`/`new`（匿名类的写法）的，都不算声明。**拒绝是刻意做窄的**：匹配起点不动，因为一动就会漂移空 body 判据依赖的 gap 窗口——887 已经在那条路上弄坏过一次。还有文本块：三个连续引号既是文本块、也是普通扫描会一次吃两个的字符串定界符，于是载荷被当成代码，**`MultiModelConfigLoaderTest` 里有 9 个方法体在自己的 JSON 样例中间就被截断**，`EvaluationSuiteDefinitionCaseValidationTest` 里另有 2 个文本块括号根本不配平。886 当初判定"无害"，那个推理本身没错——body 只喂给"是否为空"，而读长的 body 只会更容易判成非空——**但数据是错的，关于一个错值被拿去做什么的结论，不构成把它留着的理由**。现在文本块是一个整体，而且识别排在普通字符串之前，块里的括号留在数据侧。已知漏报：折行的注解若闭合行之前括号数未配平则认不出，方向是只漏不误报 | `test-support/inert-test-self-test.mjs` | tests 链 |
| `verify-null-request-forwarding.mjs` | 把 `null` 转发进 `HttpServletRequest` 参数位的重载——**要么是字面量，要么是一个被声明为"可能为 null 的 `HttpServletRequest`"的局部变量**；而 `ChatPrincipal.from(null)` 与 `ApiKeyCollectionAccess.isUnrestricted(null)` **双双 fail-open**。Batch 883 修了扫描器三处漏检：带 `throws` 的签名、缩进深于四空格的声明（内部类）、以及**行注释里的 glob 把块注释规则带进真实代码**（`// … assets/**` 曾让 `WebUiConfig` 的整个 `webuiCatchAll` 方法对门禁隐身）。**已知抓不到**：Java 文本块（`"""`）内部不单独建模，方向是只漏不误报 | `test-support/null-request-forwarding-self-test.mjs` | tests 链 |
| `verify-false-optional-wiring.mjs` | 用 `@Autowired(required = false)` 注入、又被 `if (x == null)` 守卫的协作者，而它对应的 bean 是**无条件**的 `@Service`/`@Component`——也就是那条被守卫的分支在运行中的应用里根本走不到——除非字段上写了 `// optional-claim: <理由>`。扫描面是 `*Controller.java` + `*Service.java`（Batch 829 起）；"会抛"与"会跳过"都算声明，区别只在处置：会抛的删，会跳过的登记理由。理由不足八个字符的**自己就是一条发现**（`weak-optional-claim`，Batch 882）——与前端两个门禁的 `weak-allow-reason` 同一套房规，因为这个放行阀豁免的是一句关于部署形态的断言，不是一个样式选择 | `test-support/false-optional-wiring-self-test.mjs` | tests 链 |
| `verify-controller-constructor-count.mjs` | controller 声明了多于一个构造器（不区分可见性）——Spring 只从 `@Autowired` 那个注入，其余构造器只有测试够得着，并且替调用方决定哪些协作者被置空。**判据边界（Batch 884 修正了旧成功信息的吹大）**：这道门禁**只数构造器**。真实树上有 **6 个 controller 用 12 处 `@Autowired` 方法注入 13 个协作者**，构造器计数一个也看不见，测试可以不调它们就让字段为空——其中 12 个走 `@Autowired(required = false)`，由 `verify-false-optional-wiring` 判；剩下 1 个走必填 setter，**哪道门禁都不判**。这块没被覆盖的面积每次运行都会**算出来印在输出上** | `test-support/controller-constructor-count-self-test.mjs` | tests 链 |
| `verify-error-code-catalog.mjs` | 错误响应里出现的码没登记在 `ErrorCode`（自称 single source of truth，实测 6 个码缺失）；码旁边的 HTTP status 与目录声明的不一致；手工拼的 `ErrorResponse` 不是 problem detail。全仓库**只有这一道门禁既丢弃注释的换行、又报行号**，所以这两件事必须对上：原来的 `stripComments` 把整段注释替换成空串，而 `lineOf` 在缩短后的文本上数行号，于是真实树上**有 32 条发现被报在错误的行上，最大偏 54 行**——一道门禁的价值有一半在于告诉人去哪一行看，这件事它必须做对。现在注释保留换行，匹配行为逐字符不变（**0 个文件**的发现集合发生变化） | `test-support/error-code-catalog-self-test.mjs` | tests 链 |
| `verify-json-assertions.mjs` | 在 `all(...)` / `any(...)` 里面，否定式断言——字段与 `!=` 比较，或 `// ""` 守卫过的包含判断再取 `\| not`——会被"读不到"的字段满足，于是谓词对它看不见的东西报"没事"。895 靠手读两个谓词找到一处，只能从外面加守卫；随后的普查又找到两处，都在 CI 从不运行的脚本里。**没有 allowlist**：fail-open 的谓词没法被豁免而不引入 allowlist，而 allowlist 就是"这道门禁不检查的东西"的清单 | `test-support/json-assertions-self-test.mjs` | tests 链 |
| `scripts/lib/retrieval_baseline.py` | `run-retrieval-regression.sh` 用来拿一次运行和已提交基线作比的三个读法。替掉了三处"读不到就填 0.0"的写法：`metric_at_k` 在**任何比较看到之前**就把本次运行没给出的指标变成 0；`check_minimum` 让"下限为 0.0"可以被一个从未被测量的指标满足；基线比较在已提交基线缺少某个键时拿它和 0 比——于是**这个指标的回归检查就整个消失了，一个字都没有**。实测：基线完整时 ndcg 从 1.0 掉到 0.70 会被报出，基线缺 `ndcg` 时静默通过；而 `aggregateMinimum` 仍能抓住的全部塌陷，恰恰就是它藏起来的那一种劣化 | `test-support/retrieval-baseline-self-test.mjs` | tests 链 |
| `verify-tautological-assertions.mjs` | 一条不可能失败的测试断言读起来像覆盖率，但它不是。两条规则，都没有正确实例，所以都不需要 allowlist：自满足的字面量（`assertTrue(true)` / `assertFalse(false)` / `assertNull(null)` / `assertNotNull(<非 null 字面量>)`），以及**顶层**连接符为 `\|\| true` 或 `&& false` 的第一个实参。**顶层**两个字就是整条规则——`a == false \|\| true` 是恒定的，尽管你读到的第一个运算符不是那个 `\|\|`；而 `x == false` 是一条普通断言。908 删掉 5 处，其中最响的一处挂着一条中文失败信息，声称 MiniMax 归一化后 assistant 角色不该残留，而同一个文件的 javadoc 与生产代码声明的都是 `system → user`。**故意不作为规则**的是 `assertEquals(x, x)`——普查找到 8 处。JUnit 会走 `equals()` 解析它们，所以写坏的 `equals()` 或不稳定的 `hashCode()` 都能让它们变红：它们是契约检查，而把它们报出来恰恰是会逼出 allowlist 的那件事 | `test-support/tautological-assertions-self-test.mjs` | tests 链 |
| `verify-tracked-env-files.mjs` | 索引里除 `.env.example` 之外的 dot-env 文件。`.env.deepspeed` 就是这样一个：真实的 DeepSeek、Anthropic、MiniMax key，数据库口令，外加两条指向开发机 home 目录的绝对路径，被推到了**公开**仓库的 `main` 上，存在于 `9f772b62` 和 `32fd4495`。当时 `.gitignore` 只列了 `.env` 和 `.env.local`，那是一个洞而不是一次意外——而仓库自带的"新增行密钥扫描"全程是绿的，因为那个文件比任何一次 diff 都老。那道扫描的**边界**（不是它的缺陷）正是这道门禁存在的理由。**第一版查的是"被跟踪文件里任何凭据形态的值"，报了一百多条，其中绝大多数是完全正常的代码**：SQL 里叫 `lease_token` / `owner_token` / `operation_token` 的列、React 里叫 `confirmationToken` 的 state、CI 里的 testcontainer 口令、专门用来证明脱敏有效的测试里的假 key，以及文档里 `RAG_API_KEY=rk_test_…` 这样的示例值。会这样嚎叫的门禁会被关掉，而关掉它比从没写过更糟。所以规则收敛到唯一一种零误报、零豁免表的形状：只有模板该被跟踪。**它永远不打印值**，只报文件名、行号和变量名——一个会把自己发现的密钥抄进 CI 日志的门禁，会把一次泄漏变成两次。**限制说明（由自测钉住，免得日后有人以为它管这些）**：它管的是**索引**，所以 `credentials.yml` 和 `id_rsa` 不在范围内；它也管不了历史——取消跟踪不是清除，清除也不是轮换 | `test-support/tracked-env-files-self-test.mjs` | tests 链 |
| `verify-env-example-consumers.mjs` | `.env.example` **声明了**、却没有代码或脚本去读的环境变量。模板是新同事复制成 `.env` 的那个文件，所以一条声明中的变量就是在承诺"设了它会有变化"。`ad026765` 以「补齐所有现存配置项」的名义把这个承诺违背了六次：`TRANSCRIPTION_BASE_URL/API_KEY/MODEL` 与 `VISION_BASE_URL/API_KEY/MODEL`，它们自己的块头还写着"用于音频转写"和"用于多模态对话"。这是**量出来的**，不是断言出来的：从来没有任何代码读过它们（`git log -S transcription` 在代码树上从未命中），Spring AI 依赖树里没有 transcription / vision 的 autoconfigure 模块，`docs/openai-compatibility-readiness.md` 明写多模态输入不支持。`TEST_IMAGE_PATH` / `TEST_AUDIO_PATH` 是同一形状。**只查未注释的行，而且这个边界是承重的而不是图方便**：40 条注释行里有 37 条确实有消费者，而其中**恰恰有一条删不得**——`SPRING_DATASOURCE_DRIVER_CLASS_NAME`：本仓库里没有任何地方拼出这个名字，而 `spring.datasource.driver-class-name` 在四个 YAML 里配着，Spring Boot 会在你导出该变量时绑定它。**「我没搜到这个名字」不等于「没有东西读它」。** 删掉那 6 条之后，38 条声明变量全部有消费者，规则一条豁免表都不需要；地板断言 30 保证一个悄悄停止解析的解析器不会报出"模板干净"。三类"提到"**不构成**消费者，各有各的理由，且都被自测钉住：文档提到一个变量不代表有代码读它；本地 `.env` / `.env.deepspeed` 是**模板的副本，不是它的读者**（第一版普查把它们算成了读者，于是 44 个变量看起来全都有用）；以及门禁自己在 `test-support/` / `__tests__` 路径段里的夹具——**这道门禁就是这样打败自己的**：`VISION_API_KEY` 躺在模板里没人读，它却报出干净，因为自测必须写出它要检验的变量名，而自测是被跟踪的。排除夹具的实测代价是零：38 条声明变量里没有一条只靠自测文件才成立。**注释要先剥掉，因为下一轮它被自己的头注释打败了**——那段解释"注释会把变量藏起来"的散文里写着 `VISION_API_KEY=your-ark-vision-api-key`，而门禁脚本本身是被跟踪的非文档文件。**一道解释自己规则的注释，不是任何东西的消费者。** `//` 只有紧跟在空白或 `([{,;` 之后才算注释标记，所以 `jdbc:postgresql://localhost:5432/db` 的后半段不会被吃掉；这个取舍的代价是紧贴标识符的注释标记会被当成代码——Java / JS 里这种写法罕见，而误吃的后果是**误报**，比反过来危险得多。反方向不查，理由同 `DocumentedRouteContract`：一份带着 40 条注释开关的模板已经自认不穷举 | `test-support/env-example-consumers-self-test.mjs` | tests 链 |
| `verify-helm-env-consumers.mjs` | `k8s/templates/secret.yaml` 注入、而无人读取的环境变量。`deployment.yaml` 有两处用 `envFrom: secretRef`，于是 `stringData` 的每个键都变成容器环境变量。18 条里有 2 条没有读者。**`DEEPSEEK_API_KEY`** 来自 `docker-compose.yml`，那里的 `${OPENAI_API_KEY:-${DEEPSEEK_API_KEY:-…}}` 是**宿主机侧**回落——compose 在容器还不存在时就把它解析掉了，而替换在运行中的容器里没有意义，于是这个 key 待在进程环境里没人用，而 `application.yml` 读的是 `${SPRING_AI_OPENAI_API_KEY:${OPENAI_API_KEY:dummy}}`；`deepseek` 在主模块里只作为 DTO schema 和一个测试里的模型名字符串出现。**`MINIMAX_API_KEY_ID`** 更是谁都不读——Spring AI 自己的 `spring-configuration-metadata.json` 只声明 `api-key`、`base-url` 和 `chat.options.*`，**没有 `api-key-id`**——而且它还是整个 MiniMax 块的注入条件，所以一个照着应用自己那套词汇正确配置了 MiniMax 的运维会静默地什么都拿不到。这条比死变量更糟：它是一个**静默失效的正确承诺**。**语料正面定义为「被部署的那个应用」**（主模块 + 图表自己的 ConfigMap），而不是一串排除项：`demos/**` 是另一个产物，`docker/**` 是另一条部署路径，而它们各自正是普查时那两条看起来"有人读"的原因。`SPRING_*` 按**命名空间**放行，因为 Spring Boot 绑定整个前缀——那是一条机制，不是一份需要人记得更新的名单；图表的 `SPRING_PROFILES_ACTIVE` 是它唯一能设 profile 的途径（ConfigMap 是挂成 `/config` 文件的）。这条规则不追问名字背后的属性是否真的存在，那是另一个问题 | `test-support/helm-env-consumers-self-test.mjs` | tests 链 |
| `verify-helm-helper-liveness.mjs` | 图表里定义了却没有任何模板 `include` 的 `{{- define }}`。`_helpers.tpl` 里有三个，而它们描述的是**一套并不生效的策略**：`jvm-heap` 按 `jvm.heapPercent` 换算堆上限，而 `deployment.yaml` 是直接读 `jvm.maxHeap` 的；`spring-profile` 读顶层 `.Values.springProfile`，而 `values.yaml` 里根本没有这个键，profile 实际上是由 `secret.yaml` 的 `SPRING_PROFILES_ACTIVE` 环境变量带进去的。留着它们的代价不只是死代码：`jvm.heapPercent` 是一个运维可以 `--set`、而什么都不会发生的旋钮。**这条规则没有豁免表，而这正是值得记的地方**：「提到」不等于「用」，所以 `verify-env-example-consumers` 需要三类排除；而一个 Helm helper 的可见性由模板语言本身决定，中间没有别的状态。读之前先经共享的 `scripts/lib/java-source.mjs` 剥注释——因为图表自己那段解释删除理由的注释把三个名字都写了出来，而**一份解释规则的说明不是规则在起作用**。**它刻意不查 `.Values.*` 路径是否存在**：量出来模板有 10 条引用解析不到，其中 8 条是 `with` / `if` 守卫下的可选键，外加 Helm 保留的 `global` 和 `postgresql` 子图表块——查它就会嚎叫八次 | `test-support/helm-helper-liveness-self-test.mjs` | tests 链 |
| `verify-no-pessimistic-locks.sh` | 生产代码里的悲观锁 / `SKIP LOCKED` / advisory lock | `test-support/pessimistic-locks-self-test.sh` | docs 链 |
| `verify-zh-translation.mjs` | 中文文档里未翻译的英文段落 | `test-support/zh-translation-self-test.mjs` | docs 链 |
| `verify-project-tests.sh` / `verify-project-docs.sh` | 两个聚合入口：一个跑 registry 里登记为 `kind: "gate"` 的全部门禁（每条前面先跑它自己的自测），另一个跑文档链。**两者都会打印实际跑了多少条**，所以链条变长时不需要改这两个文件——这一行以前写的是"上面 9 个的聚合入口"，而那个数字没有任何东西会重算 | 由各门禁承担 | 人跑 / 待接入 CI |
| `verify-gated-it.sh` | 门控 PostgreSQL 全量清单——23 个类 / 153 个测试方法，Batch 912 起全部核实为"纯 Testcontainers + Flyway"，此前只登记了 3 个，而脚本自己那句"跑全部纯 DB 型套件"已经对 20 个套件说了假话。本地约 9 分钟。CI 不带参数调用它，全量清单就是在这一步跑的 | 由开关对账承担 | **CI 已接** |
| `verify-webui-e2e-mock.sh` | 15 spec / 93 用例的前端 mock 回归。Batch 913 第一次在当前工作副本跑它，**93 条全过、2 分 22 秒**——这是"要不要接进 CI"这个未决问题的成本那一半的实测依据：成本只有墙上时间，没有失败。Batch 913 还发现它**提交时没有可执行位**，于是这张表里写的调用方式会以 `Permission denied` 和退出码 126 失败 | 套件自身即自测 | 单独跑（实测 2.4 分钟） |
| `check-alignment-policy.mjs` | 物理 `text-align`、内联 `textAlign`、全局样式表契约；测试文件被跳过，`--text-align` 是 token 而不是声明，没有中心声明认领的 `allow-center` 注释判失败（Batch 881） | `__tests__/alignment-policy.test.mjs` | `npm run lint` |
| `check-design-system.mjs` | 越过设计 token 的硬编码值 | `__tests__/design-tokens.test.mjs` | `npm run lint` |
| `check-a11y-forms.mjs` | 没有可访问名的控件、没绑定的 label | `__tests__/a11y-forms.test.mjs` | `npm run lint` |
| `check-mutation-errors.mjs` | 写操作不报告失败 | `__tests__/mutation-errors.test.mjs` | `npm run lint` |
| `check-query-errors.mjs` | 读操作失败时看起来像"空结果" | `__tests__/query-errors.test.mjs` | `npm run lint` |
| `check-double-submit.mjs` | 请求在途时未加锁的写操作 | `__tests__/double-submit.test.mjs` | `npm run lint` |
| `check-destructive-confirm.mjs` | 破坏性操作没有确认——清单从 `src/api/*.ts` 里真正发出 DELETE 的方法推导，POST 一律不算 | `__tests__/destructive-confirm.test.mjs` | `npm run lint` |
| `check-i18n-keys.mjs` | 两个 locale 键集不对称；`t()` 引用不存在的键；`t('x') \|\| 兜底` 这种永远不会触发的守卫；**某个键两种语言都有、却没有任何源码能到达**——其中"引用"可以是模板前缀、查找表、数据数组、别名翻译函数或 i18next 复数族。`i18n-allow` 注释是标注而非豁免，发现照样判红（Batch 879） | `__tests__/i18n-keys.test.mjs` | `npm run lint` |
| `check-hardcoded-copy.mjs` | 从未接入 i18n 的组件；已接入文件里的硬编码用户文案——含 JSX 表达式容器内的那部分，同时放行 ARIA/机器属性值与 `t()` 兜底文案。它那七条 `ALLOWED` 名单按 `path:copy` 索引，没人再用的条目会判失败（Batch 880）。**它的 `jsx-text` 模式有一个有界字符集，而里面少了一个字符：`%`。** 那个字符类必须匹配到闭合标签为止的每一个字符，于是
`<h3>Cache Hit Rate (%)</h3>`——WebUI 里最后一条硬编码的用户可见文案——对这条规则完全不可见，
而**一条看不见真实违规的规则，和一个干净的文件长得一模一样**。只加 `%`，因为只有它有实证；
第一次还顺手加了 `<` 和 `>`，结果贪婪匹配越过闭合标签，把文案产成 `RECURRING</option>` 而不是
`RECURRING`，而 `auditAllowlist` 正是按精确文案建键的，当场把五条诚实的豁免报成陈旧（Batch 931） | `__tests__/hardcoded-copy.test.mjs` | `npm run lint` |
| `check-time-formatting.mjs` | 在 `src/utils/time.ts` 之外渲染日期。Batch 938 数出**九处**，各自把一个值变成 `Date` 之后立刻 `toLocaleString()` / `toLocaleDateString()` / `toLocaleTimeString()`，而 `src/` 里根本没有共享格式化器。`ApiKeys.tsx` 给那次调用套了个 `try/catch`，读起来像是处理了读不出来的值——并没有：`new Date('garbage')` 返回的是 Invalid Date，而它上面的 `toLocaleString()` 返回**字符串** `"Invalid Date"`。于是 catch 永远进不去，原始线上值也没打印出来，十一个英文字符出现在中文页面上。规则是"一个格式化器，到处 import"，而它一条豁免表都不需要，因为唯一合法的持有者是按路径点名的。它**刻意不评判**格式化器渲染出什么——日期列和时间列本来就该不同，`ChatSidebar` 那套相对时间阶梯也是有意为之；这道门禁拦的是它的第二份拷贝。**它不看 Java 那一侧**，四种时间类型的分裂从那里开始（同一个"什么时候发生"的问题：`LocalDateTime` 73 个字段、`OffsetDateTime` 31、`ZonedDateTime` 15、`Instant` 8），因为让偏移量上线是一次牵动外部业务客户端的 API 变更 | `__tests__/time-formatting.test.mjs` | `npm run lint` |
| `check-page-shell.mjs` | 受保护页面绕过 `PageHeader`、渲染它时不给 `description`、或给的 `description` 可判定为空——遍历是递归的，子目录下的页面同样算页面 | `__tests__/page-shell.test.mjs` | `npm run lint` |
| `check-single-api-base.mjs` | `src/` 里任何一处对 API 基址的第二份拷贝。被检查的值是**从 `src/api/client.ts` 读出来的**，门禁自己不带一份——一道持有自己所 enforcement 字符串副本的门禁就是一个没人重算的机器数字，项目换了前缀它还会继续盯着 `/api/v1/rag`。先剥注释，所以照着规则写说明的文件不会因为遵守规则而变红（Batch 933） | `__tests__/single-api-base.test.mjs` | `npm run lint` |
| `check-reduced-motion.mjs` | 声明了 **`infinite`** 动画、且该选择器从未在 `@media (prefers-reduced-motion: reduce)` 下被中和（`animation: none`）的 CSS 规则。持续运动正是前庭障碍用户要关掉动画的原因，而 910 量到 **5 个**样式表在跑它且无从停止：每个会加载的页面都显示的骨架屏微光、`ReembedAllButton` 里同样的微光、流式光标的闪烁，以及 `Files` 里两个不确定进度轮。**只报 `infinite`**——一次性动画自己会结束且天然很短，为对话框那个 140ms 淡入要求守卫等于去报正确的代码。规则按**选择器**匹配而不是按位置，因为既有写法是默认声明动画、媒体查询里覆盖；第一版按「这条声明在不在 reduced-motion 块外」问，结果把 6 个文件全报了出来，其中 5 个刚被正确修好。先剥注释，这不是形式：910 新加的每条守卫注释都在正文里写出了这个媒体查询，于是按文本计数的版本会把自己的说明当成守卫 | `__tests__/reduced-motion.test.mjs` | `npm run lint` |
| `check-heading-levels.mjs` | 标题层级往下跳级的页面——`h1 → h3` 声称存在一个并不存在的分区，于是屏幕阅读器用户还没读一个字，从页面搭出来的结构就已经是错的。`PageHeader` 计为页面的 `h1`；唯一不用它的 `Unlock.tsx` 自带一个。14 个屏幕里 13 个本来就合规，911 量到 `Alerts` 是唯一跳级的，两处，都是一个根本没有 `h2` 的页面上的两张表单卡。检查是**逐页面文件**的，所以子组件写的标题不计入——这是**漏报**而非误报，与 `check-page-shell` 那个递归遍历从另一侧写下的限制同源。不剥注释直接读树，会把 `Documents.tsx` 报成有标题：那里有一条中文注释在解释一个指向空串的 `aria-labelledby` 时，正文里写出了 `<h2>` | `__tests__/heading-levels.test.mjs` | `npm run lint` |
| `check-decorative-graphics.mjs` | 既有 `role`、又没有可访问名、也没有 `<title>` 的内联 `<svg>`，本来就在对屏幕阅读器说"什么也没有"，而辅助技术照样会走一遍。整棵树只有一个内联 `<svg>`：`Search` 历史开关里的时钟字形，而它的 `<button>` 本来就带着 `aria-label`——所以那个字形是纯装饰，却仍被念了出来。规则同样**拒绝**报告那些真的在给自己命名的图形：把有意义的内容藏起来是反方向的错误，WCAG 自己的判据也把它排除在外。属性按**名字解析**而不是文本搜索，因为文本搜索分不清属性和长得像属性的字符串——把 `data-note="aria-hidden='true'"` 读成一次声明，会让这道门禁报出与它存在目的正好相反的错误 | `__tests__/decorative-graphics.test.mjs` | `npm run lint` |
| `scripts/lib/tsx-source.mjs` | 剥 TSX 注释与字符串字面量的**唯一**做法，供读源码的门禁使用。910 与 911 各自新增了读源码文本的门禁，而它们全都想要一个不存在的东西。Java 那一版是 905 建的——此前四个门禁合计摧毁了 316 个字符串字面量——所以这里第三次写同一种错误的方言，是最不该写的那一次。**两个入口，因为两个使用者需要相反的处理**：`stripTsxNoise` 会抹掉字符串内容（匹配标记的门禁不该匹配字符串里的标题），`stripTsxComments` 保留它们（本文件的装饰图形门禁问的正是 `aria-hidden="true"` 在不在，抹掉值就抹掉了它要找的那个记号）。保留长度与换行，于是发现仍能映射回它所在的那一行；模板串的插值按真代码处理 | `__tests__/tsx-source.test.mjs` | `npm run lint` |

### 读 Surefire 报告

五道验收门禁——`verify-collection-purge.sh`、`verify-api-key-expiry-alerts.sh`、
`verify-next-high-value-feature.sh`、`verify-collection-provisioning.sh` 与
`verify-document-lifecycle.sh`——都靠读 `TEST-*.xml` 上的四个计数来断言某个受开关
门控的 PostgreSQL 套件跑完了。Batch 894 之前它们**各自带一份同样的 `sed` 管道**，
而且**一道自测都没有**——也就是说被验的只是"读出来的东西"，**读法本身从没被验过**。

现在它们共用 `scripts/lib/surefire-report.sh`，其自测**跑的是真的 shell 函数**，
而不是用 JavaScript 把同一条规则重写一遍。改动它之前有两件事必须知道：

- **`tests` 是数 `<testcase>` 元素得来的，不是读 `tests=` 属性。**
  surefire 写这个属性时，`@Nested` 内部类贡献的用例还没并进去，所以它可能比自己
  汇总的子元素还小：四个模块 1006 份报告里有 3 份不一致、共差 9 条，属性求和 8320
  而真实跑了 8329 条。而这几道门禁的期望数是**写死的**——所以第一个给受门控套件
  加上嵌套类的人，会撞上一个"报的数字不是真正跑的数字"的失败。
- **`skipped` / `failures` / `errors` 仍读属性**，因为那 1006 份报告里没有一份在这
  三项上不一致。**没有实测支撑就改计数器，本身就是另一类缺陷。**

四个计数用**逗号**分隔返回而不是空格。**连续三个空计数对任何空白切分来说只是一个
分隔符**——所以空格版本分不清"报告没有 `failures` 属性"和"属性是 `failures=""`"，
它的每一个消费者（shell 和测试都一样）都会静默收到 2 个字段而不是 4 个。
消费方式是 `IFS=, read -r tests failures errors skipped < <(surefire_counts "$report")`。

### 读活跃告警列表

`verify-managed-api-principals.sh` 是 manual 门禁——它要起两个后端和四个容器——
所以它属于那 16 条 CI standing gap 里的门禁之一，它自己的读法从来没在任何地方跑过。
它里面有 **60 处 `jq -e` 谓词**在读 API 响应，其中**两处**读的是活跃告警列表，
而且两处都写成轮询循环里的 `any(...)` / `all(...)` 单行谓词，把"任何非零退出"一律
当成"还没到"。对**轮询**来说这是对的读法，对**读者**来说是错的：

- `all(.[]; .alertType != "API_PRINCIPAL_EXPIRY" or .metrics.principalId != $p)`
  在 `principalId` 被改名时**返回真**——缺失字段读作 `null`，而 `null != $p`。
  这条谓词存在的意义是证明告警**没有**在触发，所以它恰恰是最不能 fail-open 的那个
  方向：**实测**一条明明在场、正在触发、且归属就是被测 principal 的告警，被报成
  "正确地不存在"。
- 响应体一旦不再是数组，`jq` 退出码是 5 而不是 1，循环分不开这两者：它空转满 30 秒
  预算，然后报"告警没有到达 ACTIVE"——**消息指向告警，而不是读法**。

现在这两个谓词住在 `scripts/lib/alert-payload.sh`，两个轮询在读之前先调
`alerts_response_judgable`：不是数组的响应体、或一条读不出 `metrics.principalId` 的
`API_PRINCIPAL_EXPIRY` 告警，都在**第一次轮询**就被拒绝，并给出指向读法本身的理由。
守卫刻意**只作用于这一种告警**——别的种类合法地不带 principal，对所有告警都要求一个
principal 会在正确数据上判红。空数组仍然合法地等于"没有告警"；改变的是**读不懂的
响应体不再被默认点头同意**。

该门禁里另外三处读告警的**没有动**，自测把这个理由钉住了：三处都要求
`($alerts | length) == 1`，所以字段改名只会得到 0 条匹配、于是失败。会 fail-open 的
是"这里面有没有东西"那种问法，而不是"是不是恰好这一条"。

`scripts/test-support/alert-payload-self-test.mjs` 跑的是**真的 shell 函数**，
因为被测对象就是一个 shell 函数——用 JavaScript 重写一遍会在 shell 那份烂掉时依然
全绿。其中有一条用例断言 `alerts_lack_expiry` 对一条**无法归属**的告警**仍然**返回 0。
这看起来是反的，是故意的：它把危害钉在谓词这一层，从而证明轮询循环里的守卫是承重的。

**Batch 896 把这条用例翻转了，因为 896 把谓词本身关上了。** 在同一个合取里要求字段存在，
断言就从"我看不见"变成了"这个字段读得出来，而且它说的是别的东西"：

```jq
all(.[];
  (.alertType != null)
  and ((.alertType != "API_PRINCIPAL_EXPIRY")
    or ((.metrics.principalId != null)
      and (.metrics.principalId != $principal))))
```

对 principal 的要求**刻意**限定在 expiry 分支内——别的种类合法地不带 principal，
对所有告警都要求一个会在正确数据上判红。`alerts_response_judgable` 保留，但身份变了：
它现在是**消息**而不是安全网——第一秒就说清"是读法不行"，
而不是让一次轮询为一个永远不会有答案的问题空转到超时。
> **一条钉住缺陷的用例就是一根钉子；缺陷修好之后，钉子必须拔掉，否则它会把缺陷钉回去。**

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
**尚未接入 CI**——编辑 `.github/workflows/` 需要带 `workflow` scope 的凭据
（Batch 899 推送实测被远端拒绝），交接件在仓库内 `.github/pending/ci-repo-gates.patch`
待人工应用。理由里点名的每个路径都会被 `verify-gate-wiring.mjs` 在磁盘上核对，
所以这个指针不会像它在 `/tmp` 的前身那样腐烂。WebUI 的 9 个检查连同它们的自测已经在 CI 里跑（`ci.yml` 的
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
  理由过短会被单独判为 `weak-allow-reason` 失败，且**按注释报一次**而不是按它管辖的
  每一行报一次。不要用批量豁免换绿。（Batch 878：这个豁免一直存在但完全不起作用——
  门禁据以判红的计数是从所有违规建起来的，豁免与否一视同仁，
  于是站得住脚的理由照样红，而报错信息正是在教读者去写它。）
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
# 聚焦后端、V1-V60 PostgreSQL 与前端 Mock 门槛
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
