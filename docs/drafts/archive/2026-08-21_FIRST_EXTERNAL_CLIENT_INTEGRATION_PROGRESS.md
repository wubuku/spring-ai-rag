# 下一批高价值功能规划与实施进度

> 对应规划：[2026-08-21_FIRST_EXTERNAL_CLIENT_INTEGRATION_PLAN.md](2026-08-21_FIRST_EXTERNAL_CLIENT_INTEGRATION_PLAN.md)
>
> **当前状态：规划完成，尚未授权实施。**

## 1. 本轮基线

| 仓库 | 基线 | 状态 |
|---|---|---|
| spring-ai-rag | `main` / `09520e0a`，Flyway V1-V43 | 首版规划及长青文档已提交并推送；本轮仅校正分支事实与仓库术语 |
| scenemill | `codex/scenemill-first-rag-client-20260821` / `27871d057` | 已从本地 main 创建，并快进同步到当前最新本地 main 后推送；包含第一阶段收口 `737d38e2d`；相对本地 main 为 0/0，工作区干净 |
| scenemill 历史 | `feature/generated-video-asset-rag-20260820` / `737d38e2d` | 第一阶段远端分支保留；旧基线验证和 `3/3` 已记录，不再承载第二阶段开发 |
| web-studio | `main` / `2a288b98` | 远端 main 已吸收生成视频分页与 Playwright 提交；scenemill 隔离 worktree 中的子模块状态干净 |

本规划明确区分 scenemill 仓库的本地 main 与 `origin/main`：第一阶段已进入本地 main，但尚未进入
scenemill 远端 main；旧基线 `3/3` 也不冒充新分支最终组合证据。第二阶段实施前必须先完成其 Gate 0。

分支事实核查：scenemill 仓库独有内容包括生成视频自动资产化、immutable RAG mutation outbox、
revision/incarnation/fingerprint、删除/恢复语义、历史回填与 PostgreSQL 集成测试；前端独有内容
包括生成视频来源过滤、50 条分页及 Mock/fetch Playwright。它们都是应保留并推进合并的有效实现，
不是可丢弃的旧分支试验。

## 2. 本次规划动作

- 重新核对两个仓库的代码、迁移、集成测试和长青文档；
- 确认 scenemill 已有 immutable `asset_rag_outbox`，但没有 dispatcher/receipt/search；
- 确认 spring-ai-rag 现有 external document + Search 契约足以支撑首期，不需要先造专用入口；
- 将上一份“外部文档 relocation + derivation integrity”规划归档，并保留到双语 TODO；
- 选择“可靠投递 + v2 检索文本 + 项目视频语义搜索”作为下一批唯一 P0 主线；
- 冻结 target/binding、secret、revision ordering、HTTP 重放、authoritative Asset reload、
  keyword-first 降级和跨服务 E2E 边界。

## 3. 当前状态

| 阶段 | 状态 | 恢复入口 |
|---|---|---|
| 当前事实调研 | 完成 | 规划第 3 节 |
| 候选价值排序 | 完成 | 规划第 1 节 |
| 跨服务契约 | 完成 | 规划第 5-9 节 |
| 实施切片与验收 | 完成 | 规划第 11-16 节 |
| 规划连续三轮检查 | 重新执行中，`0/3` | 分支基线调整后重新检查冷读者、代码数据、验证交付 |
| 文档门禁 | 待执行 | `verify-project-docs.sh`、链接、双语同步、`git diff --check` |
| commit / merge / push | 待执行 | 先 spring-ai-rag 文档，再处理 scenemill 现有进度账本 |
| 功能实施 | 未授权 | 不在本轮修改生产代码 |

## 4. 下一步

1. 执行文档门禁；
2. 按两个仓库各自 DELIVERY 规则同步远端、提交并 push；
3. 工作区干净后等待用户授权实施。

## 5. 规划检查计数

固定范围：第 1 轮检查冷读者与需求闭环；第 2 轮检查代码、数据、API 和并发可行性；第 3 轮
检查验证、发布、恢复与交付风险。实质修改后计数归零。

当前连续无实质修改计数：`0/3`。冻结前已修正项目级检索只过滤 `sourceKind`、没有同时过滤
`scopeOwnerKey` 的隔离缺口；随后在代码/数据检查中补齐旧 binding generation receipt 的
`SUPERSEDED` 终态、在途 fencing、初始远端 revision 对账和 admin replay 契约。验证/交付检查又
核实到 scenemill 旧基线实际已完成 `3/3`，真正缺口是提交 WIP、同步最新 main 并复验最终组合。
之后又消除 capability 不调用远端却声称 vector READY 的矛盾，改为本地 availability + 实际
search mode + 运维侧 RAG readiness。核心方案曾连续达到 `3/3`；随后因 scenemill 第一阶段被并发
提交并吸收到本地 main，已创建新专用分支；本地 main 后续前进时又将专用分支快进同步到
`27871d057` 并推送，故规划检查计数按规则归零。
