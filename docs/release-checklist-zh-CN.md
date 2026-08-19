# 1.0 发布清单

> 📖 [English](release-checklist.md) · 📖 [中文](release-checklist-zh-CN.md)

候选版本：`1.0.0`
发布日期：`2026-07-21`

## 元数据与产物

- [x] 根模块、子模块与独立 Demo 的 Maven 版本均为 `1.0.0`
- [x] OpenAPI 版本为 `1.0.0`
- [x] Helm `version` 与 `appVersion` 均为 `1.0.0`
- [x] Docker/Helm 默认镜像 tag 为 `1.0.0`
- [x] 本地、Docker 与 Helm 默认端口均为 `8081`
- [x] Flyway 迁移范围为 V1-V42
- [x] JSONB 结构化记录 API、payload 快照和 Collection 生命周期已覆盖
- [x] `scripts/verify-jsonb-records.sh` 固化后端/数据库/前端聚焦验证
- [x] 文档 PATCH/禁用/恢复/永久删除与外部三元身份已覆盖
- [x] `scripts/verify-document-lifecycle.sh` 固化 CRUD、派生索引、client 与 WebUI 验证
- [x] OpenAI 兼容 base URL 末尾不带 `/v1`
- [x] 中英文发布说明已补齐

## 产品门禁

- [x] 生产 query rewrite 与 heuristic rerank 默认开启
- [x] Goldenset 脚本输出 baseline/quality 的 Precision@K、MRR、nDCG
- [x] API Key collection ACL 已持久化并覆盖全部数据面
- [x] Chat 与 Settings 支持运行时选模
- [x] 显式未知或不可用模型返回 HTTP 400
- [x] 外部 `models.json` 加载成功后完整覆盖 YAML

## 验证门禁

- [x] `mvn clean test`
- [x] `mvn package -pl spring-ai-rag-core -am -Pwebui -DskipTests`
- [x] WebUI lint、全量 Vitest 与生产构建
- [x] 全量 Playwright
- [x] Helm lint/template 与 Docker 镜像构建
- [x] PostgreSQL profile 服务启动与 `scripts/e2e-test.sh`
- [x] Retrieval goldenset
- [x] 版本化真实检索回归可通过 `--with-quality-regression` 或 `--with-local-runtime` 执行
- [x] 本机 `.env` 凭据可用时执行真实 LLM 冒烟
- [x] 密钥扫描与 `git diff --check`
- [x] 连续三轮无改动收敛检查

### 2026-08-17 增量门禁

- [x] OpenAI 兼容专项脚本覆盖 alias、scope/ACL、JSON/SSE 与错误信封
- [x] Embedding jobs 专项脚本覆盖 V33、coalesce、lease 与原子条件 claim
- [x] `verify-no-pessimistic-locks.sh` 阻止显式悲观锁、`SKIP LOCKED` 与 advisory lock 回归
- [x] JSONB 专项脚本覆盖 `payloadContains` 与 V34 GIN planner
- [x] 真实检索数据集与 baseline 已提交，质量门禁对外部依赖失败返回非零

### 2026-08-19 文档生命周期门禁

- [x] V40/V41 增加业务 revision、完整快照、source namespace 和 generation fencing
- [x] V42 增加权威外部快照对账 run 和删除来源标记
- [x] 正文变化使旧派生结果立即 stale；metadata/payload/Collection-only 更新不重嵌入
- [x] 外部 reference client 与中英文最佳实践已提交
- [x] PostgreSQL 生命周期验收显式要求 `skipped=0`

### 最终证据（2026-07-21）

- 一键命令：`VERIFY_RUN_ID=20260721-release-complete ./scripts/verify-release.sh --with-local-runtime`
- 归档：`target/release-verification/20260721-release-complete/summary.md`
- 发布门禁：19 passed、0 failed、0 skipped
- Maven：3213 tests（API 530、Documents 74、Core 2557、Starter 52）
- WebUI：lint、153 Vitest、生产构建、内嵌 bundle 完整性、37 Playwright
- 部署：Helm lint/template；DaoCloud 基础镜像 + 阿里云 Maven mirror 的 `linux/arm64` 非 root Docker 镜像
- 运行时：PostgreSQL profile 服务启动、HTTP E2E 66/66
- 检索：baseline/quality 均为 MRR 1.0、Precision@5 0.24、nDCG 1.0，`GOLDENSET_OK`
- 真实模型：MiniMax-M3 + SiliconFlow BGE-M3，ask/stream 与数据清理共 10/10

## 发布

- [x] 所有适用验证门禁通过后才提交 release commit
- [x] 将已验证 commit 推送到当前上游分支
- [ ] 由发布流水线创建不可变源码/镜像 tag `1.0.0`
