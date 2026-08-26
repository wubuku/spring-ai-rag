# 已部署业务绑定预检与可移交证据 P0 实施进度

> **状态**：实现已完成，进入完整硬门槛与交付前审查
>
> **日期**：2026-08-26
>
> **规划**：[2026-08-26_EXTERNAL_BINDING_PREFLIGHT_PLAN.md](2026-08-26_EXTERNAL_BINDING_PREFLIGHT_PLAN.md)
>
> **规划分支**：`main`
>
> **规划基线**：`main == origin/main` @ `88f9314b`
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> **实施分支**：`feat/deployed-binding-preflight-20260826`
>
> **实施基线**：`origin/main` @ `5d4145c3`
>
> **实施工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-deployed-binding-preflight`

本文记录恢复任务需要的项目内上下文、检查计数、验证证据和下一步。不记录 raw credential、
部署 URL、Collection key、external ID、业务 payload 或外部项目背景。

## 1. 当前结论

- 上一轮外部投影生产合同已经合并并推送 `main`，P0 数据面能力与 109 项真实 HTTP 合同均已
  交付。
- 本轮不重复实现 `/auth/me`、ACL、CAS、tombstone、ASYNC 或 release manifest。
- 当前真实缺口是：缺少一个无需 root、默认只读、可选专用 canary mutation、可对已部署实例
  直接执行并生成机器报告的 binding preflight。
- 本轮计划新增独立 runner，把它作为黑盒纳入现有 disposable 真实服务验收；不改 schema、
  服务端 API 或 WebUI 页面。

## 2. 清单

- [x] 读完外部 P0 输入并抽取通用 RAG 服务要求。
- [x] 对照当前代码、OpenAPI、合同脚本和双语长青文档形成差距矩阵。
- [x] 编写自包含实施规划初稿。
- [x] 规划连续三轮无修改审查。
- [x] 规划文档门禁、commit 和 push，`main` 恢复干净（`5d4145c3`）。
- [x] 基于最新 `origin/main` 创建专用分支和隔离 worktree。
- [x] Slice A：实现已部署实例 binding preflight runner。
- [x] Slice B：纳入真实 Spring Boot/PostgreSQL/embedding HTTP 黑盒验收。
- [x] Slice C：更新双语长青文档。
- [x] 完整后端、前端、真实服务与质量门槛。
- [ ] 同步最新 `origin/main` 后完整复验与 Git 交付。
- [ ] 合并并推送 `main`，清理隔离 worktree。

## 3. 规划检查账本

| 时间 | 计数 | 范围 | 结果 | 处理 |
|---|---:|---|---|---|
| 2026-08-26 | 0/3 | 初稿 | 待检查 | 已冻结通用 deployed binding preflight 范围 |
| 2026-08-26 | 0/3 | 第 1 轮：P0 闭环与默认安全 | 发现实质问题 | 增加远端 HTTPS/本机 HTTP 显式开关与禁止 redirect；把 OpenAPI 证据改为 6 个 operation；先建立 evidence 上下文以保证输入/HTTP 失败报告，计数保持 0 |
| 2026-08-26 | 0/3 | 重启后第 2 轮：HTTP 状态机与失败恢复 | 发现实质问题 | 规划要求 provider 失败 cleanup，但实施切片缺少失败注入；新增基于现有 embedding fail marker 的真实 canary 失败 run、FAIL 报告与最终 tombstone 断言，计数重置为 0 |
| 2026-08-26 | 0/3 | 重新计数前：输入与日志边界 | 发现实质问题 | 前置 expected allow-list 的 1-100、去重和 Collection key 格式校验；curl stderr/response 只进入临时 private 目录，计数保持 0 |
| 2026-08-26 | 0/3 | 重启后第 2 轮：部署传输可实施性 | 发现实质问题 | 增加 `X_API_KEY`/`BEARER` 枚举与黑盒覆盖；增加可选私有 CA PEM 且禁止 insecure TLS，计数重置为 0 |
| 2026-08-26 | 0/3 | 再次重启第 2 轮：credential 文件安全 | 发现实质问题 | 按当前生成器冻结 `rag_sk_[0-9a-f]{64}`，防止 root/legacy 值和 curl config 注入字符，最终身份仍由 `/auth/me` 确认；计数重置为 0 |
| 2026-08-26 | 0/3 | 第 3 轮：发布身份闭环 | 发现实质问题 | 当前 release checklist 的不可变 tag 仍未完成；新增最终已验证 main 的通用 annotated source tag，禁止移动/覆盖，计数重置为 0 |
| 2026-08-26 | 0/3 | 重启后第 3 轮：文档生命周期 | 发现实质问题 | 规划日期已更新为 2026-08-26，但归档前缀仍为旧日期；统一为 `2026-08-26_`，计数重置为 0 |
| 2026-08-26 | 3/3 | 最终三轮固定范围审查 | 连续三轮无实质问题 | 第 1 轮核对 P0 闭环/自包含/默认安全，第 2 轮核对 CLI/TLS/认证/状态机/cleanup，第 3 轮核对验收/发布身份/文档生命周期/Git 交付；期间未修改规划正文 |

## 4. 下一步

- 执行 `BUSINESS_CLIENT_VERIFY_PHASE=all`，取得 PostgreSQL 矩阵、Maven、WebUI、
  Mock Playwright、文档/锁/密钥/diff 和真实服务合同的统一证据；
- 按固定范围完成实现阶段三轮无修改审查；实质修复会重置计数并重跑受影响门槛；
- 同步最新 `origin/main`，按合并后基线完整复验，提交并推送特性分支；
- 将特性分支合并并推送 `main`，创建不可移动 source tag，确认工作区干净后移除隔离
  worktree。

## 5. 实施记录

- 2026-08-26：规划提交 `5d4145c3` 已推送 `main`，主规划工作区干净且
  `main == origin/main`。
- 2026-08-26：从最新 `origin/main` 创建
  `feat/deployed-binding-preflight-20260826` 和隔离 worktree；开始 Slice A。
- 2026-08-26：新增 `scripts/business-client-binding-preflight.sh` 初版，已实现：
  默认只读、`X_API_KEY`/`BEARER`、HTTPS/loopback 与私有 CA 校验、精确 restricted
  allow-list、OpenAPI/readiness/Collection 探针、ASYNC canary replay/CAS/search/
  tombstone/restore/final cleanup、provider 失败后的有界 cleanup，以及无密钥报告。
- 2026-08-26：修复 self-test 初始 Collection fixture，8 个输入/报告安全负向案例通过。
- 2026-08-26：将 runner 作为黑盒接入 `business-client-contract-e2e.sh`，新增专用
  canary Collection/principal，覆盖只读成功、allow-list 不匹配、Bearer canary 成功、
  provider fail-marker 失败清理四场景；readiness 脚本新增 runner/self-test 静态门禁。
- 2026-08-26：真实运行 `preflight-real-20260826-r5` 通过：
  `mvn clean compile test-compile`、稳定 disposable PostgreSQL、真实 Spring Boot、
  129 项 HTTP 合同（包含四个 preflight 场景）、数据库事实和真实 API-key Playwright
  `1/1` 均通过。证据目录为
  `.verification/preflight-real-20260826-r5/`；该目录只保存机器证据，不含 raw credential。
- 2026-08-26：真实验证中发现并修复三项实现问题：OpenAPI path 必须使用完整
  `/api/v1/rag/...`；upsert restore 响应没有顶层 `enabled` 字段；disposable PostgreSQL
  初始化需要连续三次稳定 readiness 后才能创建附加数据库。修复后以 r5 重新完整运行通过。
- 2026-08-26：统一全量门槛 `preflight-all-20260826` 全部 `16/16 PASS`：focused 后端合同
  `119` 项、PostgreSQL 集成矩阵、`mvn clean compile test-compile`、WebUI
  `typecheck`、Vitest `218/218`、production build、核心 Mock Playwright `1/1`、
  脚本/锁/文档/密钥/whitespace 门禁，以及真实隔离服务 HTTP 合同 `129` 项和真实
  API-key Playwright `1/1`。机器证据位于 `.verification/preflight-all-20260826/`；
  证据目录只保存无密钥机器结果。
- 2026-08-26：实现固定范围三轮收敛审查完成，连续 `3/3` 无实质问题、无实现修改：
  第 1 轮核对 runner 输入/TLS/认证/状态机/报告安全，第 2 轮重新执行脚本自检、
  禁锁/文档/whitespace 门禁并核对 cleanup 与生命周期，第 3 轮核对双语长青文档、
  验证证据、Git 祖先关系和完成定义。
- 2026-08-26：合并后真实全量复验首次暴露 canary 成功路径的并发缺口：restore 的 ASYNC
  embedding 尚未稳定时立即发送最终 tombstone，可能与 embedding worker 的派生任务更新
  形成 PostgreSQL deadlock 并返回 HTTP 500。已修复 runner：restore 后先按同一有界预算
  等待当前 revision 达到 `searchability=READY`，再执行最终 tombstone；实现审查计数重置
  为 `0/3`，需重新通过基本门槛和连续三轮审查。
- 2026-08-26：修复后的全量验收重新通过 `16/16`。其中 focused 后端测试
  `119` 项、PostgreSQL 集成矩阵、`mvn clean compile test-compile`、WebUI
  typecheck/Vitest `218/218`/production build、核心 Mock Playwright、脚本/锁/文档/密钥/
  whitespace 门禁，以及真实服务 HTTP 合同 `129` 项和真实 API-key Playwright `1/1`
  均通过；证据目录为 `.verification/preflight-fix-20260826/`。现在重新开始实现阶段
  三轮固定范围只读审查，计数为 `0/3`。
- 2026-08-26：第 3 轮实现审查发现 mutation 安全边界与长青文档不一致：实现允许
  canary key 与其他期望 Collection 一起进入 `CANARY_MUTATION`，而文档要求 mutation
  只能针对专用 canary Collection。已收紧输入校验为“期望集合恰好一个且等于 canary key”，
  增加负向自测并同步规划；本轮修改使实现审查计数重置为 `0/3`，需重新通过受影响门槛。
- 2026-08-26：重新门槛在负向自测中发现分类顺序缺口：当 canary key 不在期望集合时，
  必须保留 `CANARY_COLLECTION_NOT_ALLOWED`，不能被 only-expected 校验覆盖。已将两项
  校验改为先判“未授权 key”、再判“非单独 canary 集合”；门槛重新运行。
- 2026-08-26：分类顺序修复后的统一全量门槛重新通过 `16/16`。新增
  `canary-only-expected` 负向自测与原有 8 个 runner 负向场景均通过；focused 后端
  `119` 项、PostgreSQL 矩阵、Maven、WebUI `218/218`、构建、Mock Playwright、文档/
  禁锁/密钥/whitespace 门禁、真实服务 HTTP 合同 `129` 项和真实 API-key Playwright
  `1/1` 均通过。证据目录为 `.verification/preflight-canary-boundary-r2-20260826/`；
  实现审查重新从 `0/3` 开始。
- 2026-08-26：重新开始的实现审查第 1 轮发现归档规划的 mutation 流程仍把 canary
  描述为 allow-list 成员，未同步“唯一 expected Collection”安全默认。已修正文档并
  重跑文档门禁；实现审查计数保持 `0/3`。
- 2026-08-26：修复后的 fresh 实现三轮限定范围审查连续 `3/3` 无实质问题、期间无
  代码或测试修改。第 1 轮核对输入安全与规划/双语文档一致性，第 2 轮核对异步
  readiness、restore revision、cleanup 与失败报告，第 3 轮核对验收证据、密钥/whitespace
  门禁、文档归档和 `origin/main` 祖先关系；仅在三轮完成后写入本总结。
