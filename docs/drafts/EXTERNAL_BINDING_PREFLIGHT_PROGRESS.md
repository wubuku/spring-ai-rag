# 已部署业务绑定预检与可移交证据 P0 实施进度

> **状态**：规划连续三轮无修改审查已通过（`3/3`），待规划 Git 交付后开始实施
>
> **日期**：2026-08-26
>
> **规划**：[EXTERNAL_BINDING_PREFLIGHT_PLAN.md](EXTERNAL_BINDING_PREFLIGHT_PLAN.md)
>
> **规划分支**：`main`
>
> **规划基线**：`main == origin/main` @ `88f9314b`
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`

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
- [ ] 规划文档门禁、commit 和 push，`main` 恢复干净。
- [ ] 基于最新 `origin/main` 创建专用分支和隔离 worktree。
- [ ] Slice A：实现已部署实例 binding preflight runner。
- [ ] Slice B：纳入真实 Spring Boot/PostgreSQL/embedding HTTP 黑盒验收。
- [ ] Slice C：更新双语长青文档。
- [ ] 完整后端、前端、真实服务与质量门槛。
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

- 重跑项目文档、链接、密钥和 diff 门禁；
- 在 `main` 提交并推送规划；
- 基于最新 `origin/main` 创建专用分支和隔离 worktree；
- 开始 Slice A runner 实现。
