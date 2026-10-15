# 下一批高价值改进实施账本

## 当前状态

- 分支：`main`
- 基线：规划开始时工作区干净；实施前不应覆盖其他 WIP。
- 当前阶段：实施、回归和实现阶段连续检查均已完成，待 Git 交付。
- 规划检查计数：`3/3`；实现检查计数：`3/3`（最终账本更新后已完成快速一致性复核）。
- 生产代码：已修改；尚未提交、push 或清理工作区。

## 恢复入口

先阅读 `NEXT_BATCH_QA_WORKBENCH_PDF_PLAN.md`，再按其“文件级实施顺序”和“验收矩阵”推进。当前优先服务端 API 兼容、集成/回归测试和发布门禁；任何实质设计问题都先修规划并将实现检查计数器重置为 0。

## 验证记录

已执行：服务端全量测试、QA/PDF/evaluation focused tests、WebUI typecheck、i18n、alignment、design-system、Vitest、PostgreSQL/Flyway 集成测试、开发栈 readiness、真实 provider smoke 和发布门禁。

## 实施记录

- `KNOWLEDGE_QA`：新增版本化任务字段、事实/证据不足断言、citation/source coverage、可选 judge、usage/latency/compare delta；旧 `RETRIEVAL` JSON 与响应字段保持兼容。
- 文档处理工作台：复用既有 Run API、ACL、CAS、cancel/retry 状态机；Documents 增加记录弹窗和轮询；Evaluation Runs 增加 QA 摘要，原始 JSON 保留。
- PDF 资源边界：默认启用上传/页数/产物数量/产物总字节/内存/转换超时预算；上传流式落临时文件，Marker 输出有界，失败清理且事务回滚。
- 配置默认：`RAG_EVALUATION_MANAGED_SUITES_ENABLED` 默认改为 `true`；仅显式设置为 `false` 才关闭受管 suite。没有 schema 变化，无需迁移脚本。
- API 兼容策略：新增定义字段均可选；旧 case 映射为 `RETRIEVAL`；既有 endpoint、DTO 字段和 PDF 成功响应不删除/改名。
- 真实联调脚本：`start-real-e2e-server.sh` 先编译完整 `spring-ai-rag-starter` reactor，再从 core reactor 生成运行时 classpath，避免陈旧 starter 增量产物和 optional Actuator 依赖导致启动失败。

## 已执行验证

- `mvn -pl spring-ai-rag-core -am clean test-compile -q`：通过。
- `mvn -pl spring-ai-rag-core -am test`：API 559、Core 7837 tests，失败 0，错误 0，跳过 201。
- focused Maven：33 tests，0 failures，0 errors；另一次早期 focused QA/PDF：19 tests，0 failures，0 errors。
- WebUI：typecheck、i18n、alignment、design-system、Vitest（87 files / 1030 tests）通过。
- `./scripts/verify-next-high-value-features.sh`：通过；包含服务端 focused/integration、OpenAPI/PDF controller contract、WebUI 1030 tests、production build、迁移验证和既有质量门禁。
- `./scripts/verify-no-pessimistic-locks.sh`：通过；`./scripts/verify-project-docs.sh`：16 项通过；`git diff --check`：通过。
- `scripts/dev.sh`：后端 `18082` readiness、前端 `15173` readiness、Evaluation suites API、Document processing runs API 均通过；验证后已停止工作区所属进程。
- 真实 LLM smoke（`SERVER_PORT=18083`，OpenAI-compatible provider，未触碰其他项目占用的 `18081`）：embedding、检索、普通 Chat、Agent 工具调用/引用 registry、SSE 全部通过，`PASS=11 FAIL=0`；验证后已停止本次服务。
- MiniMax provider 的 Agent 分支明确返回 `MODEL_CAPABILITY_UNSUPPORTED`，因为当前 legacy provider 未声明 tool-calling；普通 Chat/Embedding/检索/流式仍通过，最终完整 smoke 使用已声明 `toolCalling=true` 的 OpenAI-compatible provider 验证通过。
- 发布门禁首次发现并修复两类可控问题：A/B Test Playwright mock 未随客户端迁移到 `/ab/experiments` 及分页 envelope；文档处理工作台 mutation 缺少失败 toast；SSE reader 清理异常补充开发期诊断日志。
- 修复后 WebUI `npm run lint` 全部通过，hosted preview Playwright `93 passed`；此前直接运行默认 Playwright 未启动 preview 的环境性失败不计入产品结果。
- 发布门禁的 Docker image build 单独失败于 `maven-install-plugin` 从阿里云镜像下载 `plexus-xml:3.0.1` 时的远端 TLS handshake 中断；本机 Maven 全量、WebUI、Flyway、Helm 和本批次 gate 均通过，未修改 Dockerfile 或硬编码区域镜像。
- `./scripts/verify-release.sh --with-next-high-value --skip-docker`：17 项通过、0 项失败、5 项按显式开关跳过；其中 hosted preview Playwright 93/93 通过。
- 实现阶段连续检查：3/3；检查范围覆盖规划目标映射、API/DTO/旧 JSON 兼容、PDF/Run 契约、文档/迁移/锁门禁、静态 bundle、敏感信息和残留进程；未发现需要修复的问题。
