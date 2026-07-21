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
- [x] Flyway 迁移范围为 V1-V24
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
- [x] 本机 `.env` 凭据可用时执行真实 LLM 冒烟
- [x] 密钥扫描与 `git diff --check`
- [x] 连续三轮无改动收敛检查

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
