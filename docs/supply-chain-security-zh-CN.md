# 供应链安全：依赖漏洞扫描与取舍

> 面向后端 Maven 依赖与 WebUI npm 依赖的漏洞清账记录。  
> **扫描不进 PR 快层**：完整扫描依赖 NVD 数据与外部网络，放在手动 / 夜间任务里跑；
> PR 仍只跑 `ci.yml` 的单测、契约测试与静态门禁。

---

## 1. 怎么跑

前端（区分生产依赖与开发依赖）：

```bash
cd spring-ai-rag-webui
npm audit --omit=dev    # 会随产物发布的生产依赖，必须清零
npm audit               # 含 devDependencies，只记录不阻塞
```

后端（OWASP dependency-check 13.0.0，`security-scan` profile）：

```bash
# nightly / 手动，CVSS >= 7 会让构建失败
NVD_API_KEY=<非空 key> mvn -B -Psecurity-scan -DskipTests \
  org.owasp:dependency-check-maven:aggregate

# 没有 key 时复用本地已下载的 NVD 数据，不做增量更新
mvn -B -Psecurity-scan -DskipTests -DautoUpdate=false \
  org.owasp:dependency-check-maven:aggregate
```

一键入口：`bash scripts/security-dependency-scan.sh`。

报告落在 `target/dependency-check/`（HTML + JSON），`failBuildOnCVSS=7`。

> **NVD key 的坑（实测）**：dependency-check 13.0.0 在环境变量 `NVD_API_KEY` 未设置时会以
> 空字符串调用 NVD API 并抛 `Invalid API Key, length of 0`；所以更新模式必须有非空 key，
> 否则用 `-DautoUpdate=false` 复用缓存。扫描报告里的 `NVD API Last Modified`
> 就是缓存数据日期，判断结果是否过时看它。

扫描范围只包含随产物发布的依赖：`skipTestScope` 与 `skipProvidedScope` 打开，
测试与 provided 依赖不在运行时攻击面内。

---

## 2. 前端结果与取舍

| 状态 | 生产依赖 | 全部依赖（含 dev） |
|------|----------|--------------------|
| 2026-10 清债 | 5（4 high / 1 moderate） | 33 |
| 2026-10 结束 | **0** | 15（全部 moderate） |
| 2026-11 结束 | **0** | **0** |

处理方式：`npm audit fix` 在 semver 范围内更新 `package-lock.json`，没有改 `package.json`
里的版本区间，因此没有跨大版本：

- `axios`、`follow-redirects`、`form-data`：传输层补丁版本。
- `react-router` / `react-router-dom`：升到 7.18.x 线内，避开 8.x（loader / route API 变更）。

2026-10 剩下的 15 条全部收敛到同一个根因：**`minimatch` glob 放大导致的 ReDoS**，
经由 ESLint（`@eslint/eslintrc`、`@typescript-eslint/*`）与 Stryker 的传递依赖进入。

2026-11 的处理：先确认"升级工具链"并不是可用选项——实测（`npm view`，2026-11-04）
ESLint 10.14.0、`@eslint/config-array` 0.23.5、`@typescript-eslint/typescript-estree`
8.72.1、`@stryker-mutator/core` 10.1.1 仍分别依赖 `minimatch ^10.2.6` / `^3.1.5`，
而公告的修复线是 **`>= 11.0.1`**，10.x 也在受影响区间。也就是说，换任何"最新工具链"
都清不掉这条告警；npm 给出的"修复"（把 `typescript-eslint` 降级到 8.36.0）是假修复。

因此在 `spring-ai-rag-webui/package.json` 用 `overrides` 把 **所有** minimatch 统一到
`^11.0.1`（实际锁定 11.0.4）：

```json
"overrides": { "minimatch": "^11.0.1" }
```

强制跨 major 的可行性与验证成本（实测，不是推断）：

- minimatch 11 同时提供 CJS 与 ESM 入口，调用方都是稳定的 `minimatch()` /
  `new Minimatch(pattern, { dot, matchBase, flipNegate })` API，11.x 仍接受这些选项。
- 升级前后的 `npx eslint . -f json` 结果**逐文件、逐消息完全一致**（文件数、错误/告警数、
  每个文件的 messages 无差异），说明 ESLint 的 glob 语义没有改变。
- `npm run lint`（含 525 项门禁自测、类型检查、设计语言检查）通过；
  `npm run test:run` 1115 个用例通过；`npx stryker run --dryRunOnly` 完成文件选择与
  初始测试运行（623 个候选文件），Stryker 的 glob 匹配未受影响。
- `npm audit`（含 dev）与 `npm audit --omit=dev` 均为 **0**；Dependabot 告警 110 随之下线。

残余风险：若后续上游按 `minimatch 10` 的新 API 写法迁移，`overrides` 会把它按 11 解析，
可能表现为工具启动即报错——这类失败是**显式**的，不会静默改变 lint 结果；届时改回
上游要求的版本区间并重新评估即可。ESLint 10 / Stryker 11 本身没有为此升级：
它们的新版本仍锁在受影响区间，单升工具链清不掉告警，只会增加配置迁移成本。

---

## 3. 后端结果与取舍

Spring Boot 3.5.16 已是 3.5.x 最新，Spring AI 1.1.8 已是 1.1.x 最新；两者本身没有可跟的
更高补丁版本。但 Spring Boot 管理的若干传递依赖仍落在已知 CVE 区间，因此在根 `pom.xml`
覆盖**补丁 / 小版本**（属性名即 Spring Boot 的版本覆盖入口）：

| 组件 | 原版本 | 覆盖后 | 关键 CVE（最高分） | 取舍 |
|------|--------|--------|--------------------|------|
| `tomcat-embed-core` | 10.1.55 | **10.1.61** | CVE-2026-55276 / 53434 / 59083 / 59084（9.1）等 9 条 | 同 10.1.x 补丁线，Servlet 容器 API 不变 |
| `postgresql` | 42.7.11 | **42.7.14** | CVE-2026-54291（8.2） | 同 42.7.x，JDBC 驱动补丁 |
| `httpclient5` / `httpcore5` | 5.5.2 / 5.3.6 | **5.6.5 / 5.4.4** | CVE-2026-64607；CVE-2026-54399 / 54428（7.5） | 5.6.x 只新增 API，现有调用不受影响 |
| `log4j-api` | 2.24.3 | **2.25.5** | CVE-2026-49844 | 同 2.x，补丁版本 |
| `jackson-databind` | 2.21.4 | **2.21.5** | CVE-2026-54515 | 同 2.21.x，补丁版本 |
| `pdfbox` | 3.0.3 | **3.0.8** | CVE-2026-23907 / 33929 | 描述只涉及 examples，仍按补丁线升级 |
| `swagger-ui`（webjar） | 5.18.2 | **5.34.1** | CVE-2026-116803（10.0，DOMPurify 3.1.6） | springdoc 2.8.17 仍锁 5.32.2（DOMPurify 3.3.2），故在根 `dependencyManagement` 显式覆盖 |
| `commons-lang3` | 3.17.0 | **3.19.0** | CVE-2025-48924（误报） | CVE 实际属于 Jenkins 插件，是 CPE 匹配误报；顺手升到最新 3.x |
| `jsoup` | 1.19.1 | **1.23.2** | Dependabot high（HTML 解析/清洗） | 同 1.x；项目只使用 `Jsoup.clean` + `Safelist`，API 未变 |
| `springdoc-openapi` | 2.8.4 | **2.8.17** | 随 swagger-ui 带入的 DOMPurify 漏洞 | 同 2.8.x 补丁线；不升 3.x（面向 Spring Boot 4 的破坏性变更） |
| `swagger-annotations-jakarta` | 2.2.28 | **2.2.47** | 无（版本对齐） | springdoc 2.8.17 的 swagger-core 是 2.2.47，注解停在 2.2.28 会让 `/v3/api-docs` 运行期抛 `NoSuchMethodError: Schema.$dynamicRef()`；版本只在根 `dependencyManagement` 声明一次，core 模块不再写 `<version>` |

结果：覆盖后 `dependency-check` 在 CVSS ≥ 7 上 **0 条**（本地缓存 NVD 数据截止
2026-08-12；夜间任务带 key 更新后应复核一次）。

### 明确不升级的大版本

| 候选 | 结论 | 理由 |
|------|------|------|
| Spring Boot 4.x | 不升 | 需要 Jakarta EE 11 / Spring Framework 7，属于框架级迁移，不是安全补丁 |
| Spring AI 2.x | 不升 | 2.x 是新的 API 线，当前 1.1.8 已是 1.1 最新 |
| springdoc 3.x | 不升 | 与 Spring Boot 4 配套，2.8.17 已能覆盖本轮所有 CVSS ≥ 7 |
| `react-router` 8 | 不升 | 路由 / loader API 变更，需要单独迁移与回归 |
| ESLint 10 / Stryker 11 | 暂不升 | 仅为清 minimatch 的 dev 风险，收益低于配置与规则迁移成本 |

**原则**：不为清零而升 breaking 版本；能同线补丁升级的直接升，不能的逐条记录取舍。

### 版本收敛（2026-11）

上一轮清债是逐模块改 pom，代价是版本散落：core 把 `hibernate-vector` 写死
`6.6.5.Final`，而 Spring Boot 管理同一 classpath 上的 `hibernate-core` 为
`6.6.53.Final`，构建和测试都不会报这种漂移。本轮把第三方版本全部收进根
`pom.xml` 的 `dependencyManagement`：

- `hibernate-vector` 改用 `${hibernate.version}`，与 `hibernate-core` 同版本；
- `springdoc`、`logstash-logback-encoder`、`commonmark`、`jsoup`、`pdfbox`、
  `testcontainers`、`rest-assured` 的版本从模块 pom 移到根 pom，模块只保留坐标；
- 门禁 `scripts/verify-dependency-versions.mjs`：模块 pom 不得写 BOM / 根
  `dependencyManagement` 已管理的版本，不得写第三方字面量版本，同一
  `groupId:artifactId` 在不同模块解析出不同版本即失败。项目自身的 reactor
  模块用 `${project.version}` 表达同版本，属于例外。

前端同样检查了间接依赖分叉：`package-lock.json` 中有 21 个包存在多个版本，
均为不同工具链的大版本并存（Babel 7/8、chalk 4/5、ajv 6/8 等）；`npm dedupe`
实测无法再合并（父级声明的区间彼此不兼容），因此不做强制覆盖，只记录该结论。
同大版本内的 6 处分叉（Babel 8.0.x/8.1.0、fsevents 2.3.2/2.3.3 等）由父级 pin
造成，强行提升会违反其声明区间。

---

## 4. 复核节奏

- 手动 / 夜间跑完整扫描；PR 快层只跑单测、契约测试与静态门禁。
- 每次升级 Spring Boot 父版本或 Spring AI 后，先看新版本是否已带上上述补丁；
  带上了就删除对应的覆盖属性，避免长期"影子管理"版本。
- 前端每次 `npm install` 后跑 `npm audit --omit=dev`；生产依赖不为 0 时视为回归。
- 扫描报告的缓存日期超过 30 天时，先带 key 更新再下结论。

---

## 5. 相关入口

- [开发者参考：依赖更新、供应链扫描与静态分析](developer-reference-zh-CN.md#依赖更新供应链扫描与静态分析)
- [部署与发布检查](release-checklist-zh-CN.md)
- 前端容量与导出预算：[容量与规模](capacity-and-scale-zh-CN.md#38-webui-大数据量表现本轮实测)
