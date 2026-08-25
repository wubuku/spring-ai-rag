# 外部投影生产合同闭环 P0 实施进度

> **状态**：规划审查已通过（`3/3`），等待创建隔离特性 worktree
>
> **日期**：2026-08-25
>
> **规划**：[EXTERNAL_PROJECTION_CONTRACT_CLOSURE_PLAN.md](EXTERNAL_PROJECTION_CONTRACT_CLOSURE_PLAN.md)
>
> **规划分支**：`main`
>
> **规划基线**：`main == origin/main` @ `2fae5748`
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`

本文记录恢复任务需要的项目内上下文、检查计数、验证证据和下一步。不记录 raw credential、
完整业务 payload、外部项目路径或外部项目背景。

## 1. 当前结论

- 上一轮业务服务接入就绪能力已合并并推送 `main`，对应归档账本状态为已完成。
- 当前活动规划只处理通用 RAG 外部投影合同的剩余 P0 缺口，不重复 `/auth/me` 实现。
- 本轮冻结范围：
  - lookup/tombstone 边界校验；
  - 全数据面跨 Collection 真实 HTTP deny；
  - identity/revision 长度与非法 Collection key 边界；
  - clean Git commit/版本/V48/合同计数的 release manifest；
  - 部署、健康、迁移和验收文档纠偏。
- 不改 schema，不新增 Flyway；目标仍为 V48。
- 不改变 Chat/LLM；真实 Chat LLM 测试不适用，真实 Spring AI embedding HTTP path 继续使用
  确定性 stub。

## 2. 清单

- [x] 探索当前 JSON Record controller/service/DTO、ACL、合同脚本和部署文档。
- [x] 编写自包含实施规划。
- [x] 规划连续 `3/3` 无修改审查。
- [ ] 规划文档门禁、commit 和 push，`main` 恢复干净。
- [ ] 基于最新 `origin/main` 创建专用分支和隔离 worktree。
- [ ] Slice A：边界校验与 focused/OpenAPI 测试。
- [ ] Slice B：扩展双 principal、全数据面 ACL 和字段边界真实合同。
- [ ] Slice C：release manifest 与 final clean gate。
- [ ] Slice D：双语长青文档与部署文档。
- [ ] 合并前/后完整 readiness、实现 `3/3` 和 Git 交付。
- [ ] 合并并推送 `main`，清理隔离 worktree。

## 3. 规划检查账本

| 时间 | 计数 | 范围 | 结果 | 处理 |
|---|---:|---|---|---|
| 2026-08-25 | 0/3 | 初稿 | 待检查 | 规划正文已建立 |
| 2026-08-25 | 0/3 | 第 1 轮：P0 闭环/自包含/兼容 | 发现实质问题 | 冻结 API version 取自真实 `/v3/api-docs`；增加 provider 失败后 Record 保留合同；修正 manifest 失败场景；计数保持 0 |
| 2026-08-25 | 0/3 | 重启第 1 轮：兼容校验 | 发现实质问题 | raw `@Pattern` 会破坏现有 trim 兼容；改为共享 `@ValidSourceNamespace`，OpenAPI 限制显式声明；计数保持 0 |
| 2026-08-25 | 0/3 | 重启第 1 轮：失败注入可实施性 | 发现实质问题 | 冻结 stub `--fail-marker`、测试环境变量和 job default/max attempts=1；计数保持 0 |
| 2026-08-25 | 0/3 | 第 2 轮：API/ACL/脚本可行性 | 发现实质问题 | 失败 fixture 若写 A 会破坏精确 readiness 断言；改为 B principal/Collection B，并复用为双向越权真实目标；计数重置为 0 |
| 2026-08-25 | 0/3 | 重启前：fixture 顺序 | 发现实质问题 | 合法 boundary Record 固定排在 A 最终 freshness 之后，避免 `SKIP` Record 污染精确计数；计数保持 0 |
| 2026-08-25 | 0/3 | 重启第 1 轮：Git/证据可复现性 | 发现实质问题 | 原顺序在 clean readiness 后归档草稿，会让 manifest SHA 落后于最终提交；改为先归档并提交最终特性 tip，再跑 clean readiness，合入后在最终 `main` merge commit 上再次完整验证；计数保持 0 |
| 2026-08-25 | 3/3 | 三轮固定范围审查 | 连续三轮无实质问题 | 第 1 轮核对 P0 闭环/自包含/兼容，第 2 轮核对 API/ACL/失败注入/manifest，第 3 轮核对切片/验收/回滚/Git 交付；期间未修改规划正文 |

## 4. 下一步

运行规划文档、密钥、锁策略和 diff 门禁，提交并推送 `main`；随后从最新
`origin/main` 创建专用隔离特性 worktree。
