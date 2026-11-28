# 活跃规划

> [English](README.md) | [中文](README-zh-CN.md)

`docs/drafts/` 只保存**当前仍准备实施或正在实施**的规划与进度记录。这里不是稳定项目
事实的长期存放处；已经落地的能力必须提炼到对应的双语长青文档。

新建或维护活动 plan/progress 前先读
[规划、实施与验收工作流](../delivery-workflow-zh-CN.md)；本页只规定草稿生命周期，不重复
规划内容和三轮检查标准。

## 使用规则

1. 活跃规划使用稳定文件名，例如 `NEXT_HIGH_VALUE_FEATURES_PLAN.md`，避免仅凭日期判断状态。
2. 规划进入实施后，可在同目录增加对应的 `*_PROGRESS.md`，记录恢复任务所需的上下文。
3. 完成、取消或被替代后，先把仍有效的事实同步到 `docs/` 长青文档，再将 plan/progress
   用 `git mv` 移入 [`archive/`](archive/README-zh-CN.md)。
4. 归档时保留或补充 `YYYY-MM-DD_` 前缀，日期表示归档或最后有效的时间点。
5. `AGENTS.md`、`docs/index*` 和长青文档只链接当前活跃规划或归档总入口，不把单份历史稿
   当作当前事实入口。

## 真相顺序

发生冲突时按以下顺序判断：

1. 当前代码、迁移和自动化测试。
2. `docs/` 下的长青 guide/reference。
3. 本目录中的当前活跃规划。
4. `archive/` 中的历史规划和实施账本。

当前活跃规划会在本页和 `docs/index*` 中列出。

## 当前活跃规划

- [WeKnora 参考项目调研与借鉴规划](WEKNORA_REFERENCE_RESEARCH.md)：记录对参考项目的代码级调研、
  与当前能力的差距判断和候选实施路线；仅为活跃调研草案，不代表已承诺实施。
- [加固循环批次规划](HARDENING_LOOP_PLAN.md)：持续循环执行测试加固、WebUI UI/UX
  重构与技术债务处理；本阶段不把新功能作为重点。
- [持久化长任务、多 Embedding Profile、Client 契约与质量漂移](NEXT_BATCH_ASYNC_EMBEDDING_CLIENT_QUALITY_PLAN.md)：
  异步 Purge、固定维度 Profile 路由与 Client 契约已落地；剩余外部阻塞是 MiniMax 账户额度
  恢复后补真实拒答/answer-contract 证据，以及用户轮换曾暴露的 key，尚未完成 Git 交付。
- [多租户现状调研](MULTI_TENANCY_STATUS.md)：只读盘点当前 ACL/归属边界与多租户缺口；
  缺口 1、2 已在 V71 关闭，保留为持续跟踪现状的调研稿。
- [多租户平台技术路线与可插拔方案](MULTI_TENANCY_TECH_ROADMAP.md)：记录 2026-11-04 的路线
  决策（单仓单 schema、表永远租户化、单租户为固定 `default` 的 N=1、多租户强制 RLS）、
  EE/CE 考古、四阶段落地、六条决策与三大风险；**方向已定并已实施**（WP1–WP12 于
  2026-11-14 完成并合并 `main`，含 V85 纠正）；仅中文，当前能力以代码与长青文档为准。
- [多租户外部参考经验教训（补充考古）](MULTI_TENANCY_EXTERNAL_LESSONS.md)：只读考古，
  补充考察 `../seedance-research/`（含可运行多租户 PoC 与生产 checklist）与
  `../AI-Native-Business-Operating-System/`（真实 PG 迁移演练）；含路径基准约定、
  已覆盖/需补强/新增盲点三张影响清单与复现命令；**方向裁决不变，实施已完成**（见下方
  规划与进度账本）；仅中文。
- [多租户化实施规划](MULTI_TENANCY_IMPLEMENTATION_PLAN.md)：在现状调研、技术路线与
  外部经验教训考古之上形成的冻结规划；含 12 个工作包、一次性验收矩阵、三轮检查 `3/3` 记录；
  仅中文。**已实施完毕**：WP1–WP12 于 2026-11-14 完成并合并回 `main`
  （合并提交 `1ad305c7`，含 V85 纠正迁移 `cc181480`）。
- [多租户化实施进度](MULTI_TENANCY_IMPLEMENTATION_PROGRESS.md)：实施期间唯一进度账本；
  记录各工作包验证台账、红绿证据、已知未验项与恢复入口；仅中文。
- [多租户表归属清单](MULTI_TENANCY_TABLE_CATALOG.md)：76 张表逐张裁决
  （租户私有 48 / 平台全局 24 / 凭据 4），机器可读源为 `TenantTableCatalog.java`；仅中文。
- [多租户升级演练](MULTI_TENANCY_UPGRADE_REHEARSAL.md)：大表 `CONCURRENTLY` 锁窗口、
  分批回填与 STRICT→SINGLE 回退前置条件记录；仅中文。
- [知识图谱模块评审](KNOWLEDGE_GRAPH_MODULE_REVIEW.md)：对一份外部知识图谱方案的批评性评审、
  与本仓真实实现的逐条对齐及分阶段落地路径；仅中文，待 D1–D3 决策收敛后再决定是否转为正式文档。
- [多租户成熟度评估与 P0 问题审计](MULTI_TENANCY_MATURITY_AND_P0_AUDIT.md)：代码级多租户成熟度评估，
  记录 5 项 P0 缺陷/阻塞项（RLS 全策略校验、新租户无法通过 REST API 签发 Key、非 ACTIVE 租户未显式 403、`default` 保护与 `lifecycle-actors` 放通、混合语料 HNSW 召回与大表演练）及 4 项 P1 运维缺口与修复规格；仅中文。
- [运营方管理控制台总设计与分阶段实施规划](OPERATOR_CONSOLE_DESIGN_AND_PLAN.md) 与
  [UI 原型施工手册（Mock-First）](OPERATOR_CONSOLE_UI_CONSTRUCTION_MANUAL.md)：参考 ESA 与
  `../AI-Agent-Operator-Console/` 设计的 RAG 运营方管理控制台（多租户管理后台）规划包，含 6 视图 IA、
  安全隔离与 V86 后端 API 契约、Phase 0–4 路线图，以及面向任意前端 Coding Agent 的 9 步 Mock-First 施工手册；仅中文。
- [运营方管理控制台 UI/UX 全面审核报告](OPERATOR_CONSOLE_UI_UX_AUDIT.md) 与
  [客户 Web UI 保守对齐审核报告](CUSTOMER_WEBUI_UI_UX_AUDIT.md)：对照 BOS 与 ESA 共享的
  `classic-light`/`classic-dark` 统一设计语言与公司级词汇表，分别输出运营方控制台精修清单（词汇表、骨架屏/空态、排序、顶栏与 `DevPanel` 位置）及客户侧 `spring-ai-rag-webui` 保守对齐方案（仅改色值令牌与 i18n 文案，不改功能与布局）；仅中文。
- [Scenemill（星幕 AI）系统接缝对齐与加固规划](SCENEMILL_SEAM_ALIGNMENT_AND_HARDENING_PLAN.md)：针对上游星幕 AI N:1 共享 Collection 与多租户演进的代码级接缝加固规划（3/3 审校冻结），覆盖 P0（pgvector `hnsw.iterative_scan`、`/integration-capabilities` 租户字段与 `/auth/me` 对齐、`JsonRecordUpsertResponse.contentHash` 与非 `ACTIVE` 租户 403 契约）及 P1（双模式 `ScenemillSeamContractTest` 契约护栏、响应头一致性与 `scenemill-prod` provisioning 模板）；仅中文。
- [Scenemill STRICT 模式 provisioning 模板](SCENEMILL_STRICT_PROVISIONING.md)：面向多产品共用一套 RAG 实例的 `scenemill-prod` 租户、6 个 Collection 与 4 个受限 Key 的参考编排；包含非 `ACTIVE` 租户 403 契约校验；仅中文。

前三批文档处理恢复、引用/流式安全、分块正确性与诊断、默认启用与自动升级、故障后无人值守恢复，以及补充真实 LLM 验收已归档至
[历史规划归档](archive/README-zh-CN.md)。

知识库 Chat 检索后证据组装与依据完整性回归已归档至[历史规划归档](archive/README-zh-CN.md)。

API Route Policy 自检与未声明 route 默认拒绝已归档至[历史规划归档](archive/README-zh-CN.md)。

已完成、已停止和被替代的材料可从[历史归档](archive/README-zh-CN.md)追溯。
