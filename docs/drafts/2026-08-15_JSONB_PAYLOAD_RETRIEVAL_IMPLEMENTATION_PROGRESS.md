# JSONB 结构化记录实施进度

> 对应规划：[JSONB 结构化记录导入、嵌入与检索实施规划](2026-08-15_JSONB_PAYLOAD_RETRIEVAL_IMPLEMENTATION_PLAN.md)
> 状态：实现与基本验证已完成，最终文档门禁已通过，正在执行固定范围三轮无修改收敛检查。
> 开始日期：2026-08-15

## 执行规则

- 先更新本文件，再开始下一阶段。
- 不撤销、不覆盖其他开发者在工作区中的修改。
- 迁移编号以当前工作区为准；已存在的 `V25` / `V26` 不修改，JSONB 使用下一个可用版本。
- 功能完成必须同时具备实现、测试、正式文档和可重复验证命令。
- 基本集成验证通过后，执行固定范围的三轮无修改代码检查；任一轮发现问题并修改代码，计数归零。

## 工作区边界

### 并行 WIP（只读保护，除非解除冲突所必需）

- Embedding Profile / 固定维度向量迁移：`V25`、`V26` 及相关 core 配置、检索、持久化、测试。
- Collection API Key ACL：相关 API、文档、测试和迁移前置改动。
- 项目文档系统、构建脚本和正式文档的同期修改。

### 本次 JSONB 实施预期新增或修改

- `rag_documents` JSONB / external identity schema、实体、repository。
- JSON record API DTO、controller、service、ACL 接入和错误契约。
- document version JSONB snapshot 与 version 约束兼容。
- JSON record embedding 分支和普通短文档分块修复。
- collection clone/export/import 的 JSON 字段保留。
- 相关 unit / integration / E2E 验证和正式中英文文档。

## 进度

| 阶段 | 状态 | 关键结果 |
|------|------|----------|
| 0. 工作区与规划审计 | 已完成 | 已确认并行 WIP、Flyway 当前上限为 V29、规划文档已通过三轮审查 |
| 1. Schema、实体、版本、配置、基础 DTO | 已完成 | 新增 V29、JSONB/external identity、payload version snapshot、配置和专用 DTO |
| 2. 分块与 embedding | 已完成 | 普通短文档保留、JSON record 单 chunk、独立 fingerprint、一次 version-only 提交重试和 freshness 检查已实现；待测试收口 |
| 3. Upsert、batch、search、ACL、错误 | 已完成 | 四个端点、幂等 identity、advisory lock、批量逐项失败隔离、ACL 和结构化冲突错误已实现；待测试收口 |
| 4. Version / collection 生命周期 | 已完成 | payload-only 强制版本、版本快照、clone 保留 JSON 字段并建立新初始版本、typed import 复用 JSON persistence |
| 5. 测试、脚本、正式文档 | 已完成 | 聚焦测试、一键门禁、真实 JSONB HTTP E2E 脚本和正式中英文文档已完成；project-docs 门禁通过 |
| 6. 基本集成验证 | 已完成 | JSONB 全层一键门禁 11/11 通过；Mock Playwright 40/40 通过；真实 JSONB HTTP E2E 29/29 通过；后端 PostgreSQL profile 健康检查通过 |
| 7. 三轮实现代码收敛检查 | 进行中 | 基本门槛已绿，正在按固定范围执行连续 3 轮无修改检查 |

## 验证记录

| 时间 | 范围 | 结果 |
|------|------|------|
| 2026-08-15 | 规划阶段三轮系统性审查 | 通过；规划未修改 |
| 2026-08-15 | 当前工作区审计 | 已完成；未撤销并行修改 |
| 2026-08-15 | API/documents/core 快速编译 | 首次被并行 `RagDocumentController` lambda 捕获问题阻断；最小改为显式分支后通过 |
| 2026-08-15 | collection clone/import 与 embedding freshness 首轮实现 | 通过模块编译；clone 复制 JSON 字段并创建新版本，import 复用 JSON record persistence，payload-only fresh embedding 不调用 provider |
| 2026-08-15 | `RagJsonRecordControllerWebTest` | 通过 3/3；补充 WebMvc slice 所需的 `RagProperties` 测试配置 |
| 2026-08-15 | JSONB 后端定向测试批次 | OpenAPI 契约、JSON record WebMvc、现有控制器集成、JSON service 共 82 tests 通过；Collection WIP 仅补测试 stub 和 collectionKey |
| 2026-08-15 | `JsonbStructuredRecordsPostgresIntegrationTest` | 通过 2/2；`-Dapi.version=1.40` 解决 Docker API 协商，`TESTCONTAINERS_RYUK_DISABLED=true` 绕过本机 Ryuk registry 证书问题；Flyway 空库迁移成功到 V29 |
| 2026-08-15 | `./scripts/verify-jsonb-records.sh` gate-06 | 通过 11/11；API DTO、documents chunker、JSON service/controller/OpenAPI、PostgreSQL JSONB、`mvn clean compile test-compile`、WebUI Vitest/生产构建、Mock Playwright 38/38、project-docs、`git diff --check` 全部通过；日志与汇总在 `.verification/jsonb-verification/20260815-jsonb-gate-06/` |
| 2026-08-15 | PostgreSQL profile 服务启动 | `SERVER_PORT=18082 LLM_PROVIDER=openai RAG_SECURITY_ENABLED=false ./scripts/start-real-e2e-server.sh` 启动成功；`/actuator/health` 返回 `UP`，数据库组件为 `UP` |
| 2026-08-15 | 真实 JSONB HTTP E2E | `BASE_URL=http://127.0.0.1:18083` 下通过 29/29；覆盖 upsert/search/detail、collection scope、payload-only 不重嵌入、`retrievalText` 更新重嵌入、clone/export/import 和受限 API key allow/deny；未输出真实 key 或完整 payload |
| 2026-08-15 | `./scripts/verify-jsonb-records.sh` gate-final-02 | 通过 11/11；API DTO 467、documents chunker 26、core JSONB/controller 93、PostgreSQL JSONB 2、WebUI Vitest 170、Mock Playwright 40/40、Maven clean compile/test-compile、project-docs 和 whitespace 全部通过；日志与汇总在 `.verification/jsonb-verification/20260815-jsonb-gate-final-02/` |

## 已知阻塞与处理

- 项目文档校验脚本曾固定期待 V24；当前工作区已有并行 `V25–V28`，JSONB 迁移实际为 `V29`，正式文档和校验脚本必须以 V29 为当前上限。
- Testcontainers 默认镜像必须使用现有可配置机制，境内环境优先通过环境变量或本地脚本覆盖，不把区域镜像硬编码到 Dockerfile。
- 首次运行新 WebMvc 测试时，`CorsConfig` 因测试 slice 缺少 `RagProperties` 无法启动；已按既有 controller 测试模式补充测试配置，重建后测试通过。
- 本机 OrbStack Docker CLI 为 API 1.54、服务端最低 API 1.40；Testcontainers 1.20.4 默认协商到 1.32，JSONB 集成测试命令必须追加 `-Dapi.version=1.40`。本机从 Docker Hub 拉取 Ryuk 时证书被代理替换，测试命令使用 `TESTCONTAINERS_RYUK_DISABLED=true`；这两个环境要求将固化到一键验证脚本和中国境内网络排障文档。
- 2026-08-15 文档收口：V29、JSONB API、`rag.structured-records.*`、短文本保留规则、
  Testcontainers 参数和 `scripts/verify-jsonb-records.sh` 已同步到中英文正式文档及索引；
  需重新运行 project-docs 门禁确认无旧口径残留。
- 2026-08-15 收敛检查发现规划要求的 `scripts/jsonb-records-e2e.sh` 尚未落地；
  已补齐真实 HTTP upsert/search/detail、payload-only、retrievalText 更新、
  clone/export/import 和临时受限 API Key allow/deny 流程，并同步开发/测试文档。
- 2026-08-15 验证流程修正：一次并发启动 `mvn clean compile test-compile` 与 Maven
  测试，`clean` 删除了另一进程正在使用的 `target/`，造成 documents 测试出现类缺失；
  该失败归因于验证命令并发冲突，不是业务或测试夹具问题。后续 Maven 命令串行执行。
- 2026-08-15 一键脚本首轮发现并修复日志生命周期问题：日志原先放在 `target/`，
  会被脚本内的 `mvn clean` 删除，导致 EXIT 汇总失败；现改为默认写入被 gitignore
  的 `.verification/jsonb-verification/<run-id>/`，并在汇总函数中确保目录存在。
- 2026-08-15 Mock Playwright 首轮失败是端口误用：默认 `4174` 已被其他开发进程占用，
 旧脚本把“已有服务可访问”误判为本次 preview 就绪，导致测试打到旧构建。脚本现使用
  Vite `--strictPort`，检查自身 preview 进程和绑定错误，并为 readiness `curl` 设置连接/
  总超时；端口被占用时应通过 `JSONB_PLAYWRIGHT_PORT` 指定空闲端口。
- 2026-08-15 并行 WIP 的 `ApiKeyManagementServiceTest` 使用 `Cache` 但缺少导入，曾阻断
  `mvn clean compile test-compile`；按协作规则只补充 `org.springframework.cache.Cache`
  测试导入，未修改业务实现，随后 gate-06 全部通过。
- 2026-08-15 验证命令必须串行执行；尤其不能并发运行含 `mvn clean` 的门禁与其他 Maven
  测试，否则会删除对方正在使用的 `target/`，产生与业务无关的类缺失。
- 2026-08-15 真实服务验证：启动脚本在本机执行环境中退出后，后台服务可能被一并回收；
  真实 HTTP E2E 使用持久前台会话承载服务，启动参数仍复用
  `scripts/start-real-e2e-server.sh`，避免误判“已启动”但随后连接失败。
- 2026-08-15 最终门禁首轮发现新增草稿的行尾空格，修正后重新执行完整门禁；
  `gate-final-02` 已通过，当前 `git diff HEAD --check`、暂存区和工作区 whitespace 检查均通过。
