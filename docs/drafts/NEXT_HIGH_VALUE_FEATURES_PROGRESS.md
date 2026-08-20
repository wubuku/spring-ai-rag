# 下一批高价值功能规划与实施进度

> 对应规划：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)
>
> **当前状态：规划完成，尚未授权实施。**

## 1. 本轮基线

| 仓库 | 基线 | 状态 |
|---|---|---|
| spring-ai-rag | `main` / `957603e3`，Flyway V1-V43 | 本规划明确 scenemill 是当前 RAG 服务的第一个外部 Client；仅文档修改待提交 |
| scenemill | `feature/generated-video-asset-rag-20260820` / `5ddb31035` | 远端同名分支存在；相对最新 `origin/main@71e8cda86` 为 3 behind / 8 ahead；调度池配置与最新进度账本未提交，必须保留；旧基线已完成 `3/3`，合并最新 main 后须复验 |
| web-studio | 本地 `feature/generated-video-asset-rag-20260820` / `e49c4814` | 远端无同名分支；相对 `origin/main@434b7baea` 为 0 behind / 3 ahead；工作区干净 |

本规划不把 scenemill 第一阶段描述成已合 main，也不把旧基线 `3/3` 冒充最新组合证据。第二阶段
实施前必须先完成其 Gate 0。

分支事实核查：父仓独有内容包括生成视频自动资产化、immutable RAG mutation outbox、
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
| 规划连续三轮检查 | 完成，`3/3` | 冷读者、代码数据、验证交付三轮连续无实质修改通过 |
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

当前连续无实质修改计数：`3/3`。冻结前已修正项目级检索只过滤 `sourceKind`、没有同时过滤
`scopeOwnerKey` 的隔离缺口；随后在代码/数据检查中补齐旧 binding generation receipt 的
`SUPERSEDED` 终态、在途 fencing、初始远端 revision 对账和 admin replay 契约。验证/交付检查又
核实到 scenemill 旧基线实际已完成 `3/3`，真正缺口是提交 WIP、同步最新 main 并复验最终组合。
之后又消除 capability 不调用远端却声称 vector READY 的矛盾，改为本地 availability + 实际
search mode + 运维侧 RAG readiness。修正完成后，冷读者与需求闭环、代码与数据可行性、验证与
交付风险三轮连续未再发现实质问题，也未修改方案，计数达到 `3/3`。
