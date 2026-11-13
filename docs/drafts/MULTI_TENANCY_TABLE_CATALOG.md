# 多租户表归属裁决清单（WP1 产物）

> 机器可读单一事实源：`spring-ai-rag-core/src/main/java/com/springairag/core/tenant/TenantTableCatalog.java`
>（本文与其必须保持一致，新增表先改 catalog + 本文再写迁移）。
> 基线：Flyway V1–V74 共 **69 张存活业务表**（V26 已 DROP 的 `rag_vector_store` 不登记）。
> 分类：**TENANT_PRIVATE 45 / PLATFORM_GLOBAL 20 / CREDENTIAL 4**。
> 裁决规则来自 [多租户化实施规划](MULTI_TENANCY_IMPLEMENTATION_PLAN.md) §5.2（终审核 APPROVE 基线）。
> 行为契约：租户私有表 → RLS ENABLE+FORCE + 规范 policy + tenant-leading 索引；
> 平台全局/凭据表 → 无 RLS，仅按登记访问点读写（WP3 verifier 按 catalog 逐张校验）。

## 1. 汇总

| 类别 | 张数 | RLS | tenant-leading 索引 | 典型访问面 |
|---|---|---|---|---|
| TENANT_PRIVATE | 45 | ENABLE+FORCE | 必须 | 业务请求链路 / TenantJobDispatcher |
| PLATFORM_GLOBAL | 20 | 否 | 否 | 平台池（仅登记访问点）或 app 读写（见逐张行） |
| CREDENTIAL | 4 | 否 | 否 | app 角色 digest 精确查找（认证先于租户解析） |

## 2. 逐张裁决（按迁移引入顺序）

| # | 表 | 类别 | RLS | tenant-leading 索引 | 创建迁移 | PK | 列 | UNIQUE | FK | 理由 | 平台池访问点 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `rag_collection` | 租户私有 | 是 | 是 | `V1__init_rag_schema.sql` | 1 | 9 | 0 | 0 | 租户内知识库根实体，租户是安全边界的第一层 | POOL_NONE |
| 2 | `rag_documents` | 租户私有 | 是 | 是 | `V1__init_rag_schema.sql` | 1 | 15 | 0 | 1 | 文档归属 collection，collection 归属租户 | POOL_NONE |
| 3 | `rag_embeddings` | 租户私有 | 是 | 是 | `V1__init_rag_schema.sql` | 1 | 9 | 0 | 1 | 向量主存储，RLS 过滤是检索隔离的兜底（WP2 大表专项） | POOL_NONE |
| 4 | `rag_chat_history` | 租户私有 | 是 | 是 | `V1__init_rag_schema.sql` | 1 | 7 | 0 | 0 | 会话消息归属租户内会话 | POOL_NONE |
| 5 | `SPRING_AI_CHAT_MEMORY` | 租户私有 | 是 | 是 | `V2__add_spring_ai_chat_memory.sql` | 0 | 3 | 0 | 0 | 框架会话记忆表，行随会话归属租户（WP2 加列回填） | POOL_NONE |
| 6 | `rag_retrieval_logs` | 租户私有 | 是 | 是 | `V3__add_retrieval_logs.sql` | 1 | 12 | 0 | 0 | 检索日志随请求归属租户 | POOL_NONE |
| 7 | `rag_retrieval_evaluations` | 租户私有 | 是 | 是 | `V4__add_retrieval_evaluations.sql` | 1 | 14 | 0 | 0 | 评估结果随 collection 归属租户 | POOL_NONE |
| 8 | `rag_user_feedback` | 租户私有 | 是 | 是 | `V5__add_user_feedback.sql` | 1 | 11 | 0 | 0 | 用户反馈随会话/文档归属租户 | POOL_NONE |
| 9 | `rag_ab_experiments` | 租户私有 | 是 | 是 | `V6__add_ab_testing.sql` | 1 | 12 | 1 | 0 | A/B 实验在租户内 collection 上定义 | POOL_NONE |
| 10 | `rag_ab_results` | 租户私有 | 是 | 是 | `V6__add_ab_testing.sql` | 1 | 9 | 0 | 1 | 实验结果随实验归属租户 | POOL_NONE |
| 11 | `rag_document_versions` | 租户私有 | 是 | 是 | `V9__add_document_versions.sql` | 1 | 11 | 1 | 0 | 文档版本随文档归属租户 | POOL_NONE |
| 12 | `fs_files` | 租户私有 | 是 | 是 | `V20__add_fs_files.sql` | 1 | 8 | 0 | 0 | 上传文件随 collection 归属租户 | POOL_NONE |
| 13 | `rag_document_embedding_state` | 租户私有 | 是 | 是 | `V25__embedding_profile_expand.sql` | 1 | 12 | 0 | 2 | 文档 embedding 状态随文档归属租户 | POOL_NONE |
| 14 | `rag_chat_session_lease` | 租户私有 | 是 | 是 | `V32__add_chat_history_owner_sources_lease.sql` | 1 | 14 | 0 | 0 | 会话租约随会话归属租户 | POOL_NONE |
| 15 | `rag_embedding_jobs` | 租户私有 | 是 | 是 | `V33__add_embedding_jobs.sql` | 1 | 35 | 0 | 2 | embedding 任务随文档归属租户，任务载荷自带租户上下文 | POOL_NONE |
| 16 | `rag_evaluation_suites` | 租户私有 | 是 | 是 | `V38__add_managed_evaluation_suites.sql` | 1 | 6 | 1 | 0 | 评估套件是租户内资产 | POOL_NONE |
| 17 | `rag_evaluation_suite_versions` | 租户私有 | 是 | 是 | `V38__add_managed_evaluation_suites.sql` | 1 | 8 | 2 | 1 | 套件版本随套件归属租户 | POOL_NONE |
| 18 | `rag_evaluation_runs` | 租户私有 | 是 | 是 | `V38__add_managed_evaluation_suites.sql` | 1 | 16 | 0 | 1 | 评估运行随套件归属租户 | POOL_NONE |
| 19 | `rag_evaluation_case_results` | 租户私有 | 是 | 是 | `V38__add_managed_evaluation_suites.sql` | 1 | 10 | 0 | 1 | 用例结果随运行归属租户 | POOL_NONE |
| 20 | `rag_document_source_namespaces` | 租户私有 | 是 | 是 | `V40__document_lifecycle_expand.sql` | 1 | 15 | 0 | 1 | 源命名空间随文档/租户登记 | POOL_NONE |
| 21 | `rag_document_idempotency_operations` | 租户私有 | 是 | 是 | `V40__document_lifecycle_expand.sql` | 1 | 15 | 1 | 1 | 文档幂等操作按租户内文档键控 | POOL_NONE |
| 22 | `rag_document_sync_runs` | 租户私有 | 是 | 是 | `V42__document_sync_runs.sql` | 1 | 39 | 1 | 1 | 同步 run 随文档归属租户，run 载荷自带租户上下文 | POOL_NONE |
| 23 | `rag_document_sync_run_items` | 租户私有 | 是 | 是 | `V42__document_sync_runs.sql` | 1 | 15 | 0 | 2 | 同步条目随 run 归属租户 | POOL_NONE |
| 24 | `rag_document_chunks` | 租户私有 | 是 | 是 | `V43__decouple_keyword_chunks_from_embeddings.sql` | 1 | 27 | 1 | 1 | 关键词 chunk 随文档归属租户 | POOL_NONE |
| 25 | `rag_document_local_index_state` | 租户私有 | 是 | 是 | `V43__decouple_keyword_chunks_from_embeddings.sql` | 1 | 23 | 0 | 1 | 本地索引状态随文档归属租户 | POOL_NONE |
| 26 | `rag_document_relocated_addresses` | 租户私有 | 是 | 是 | `V44__document_relocation_idempotency.sql` | 1 | 20 | 0 | 4 | 搬迁地址随文档归属租户 | POOL_NONE |
| 27 | `rag_derivation_repair_previews` | 租户私有 | 是 | 是 | `V45__derivation_repair_control_plane.sql` | 1 | 24 | 0 | 2 | 派生修复预览随文档归属租户 | POOL_NONE |
| 28 | `rag_derivation_repair_items` | 租户私有 | 是 | 是 | `V45__derivation_repair_control_plane.sql` | 1 | 31 | 0 | 2 | 修复条目随预览归属租户 | POOL_NONE |
| 29 | `rag_chat_memory_summary` | 租户私有 | 是 | 是 | `V46__add_chat_memory_summary.sql` | 1 | 22 | 0 | 0 | 会话摘要随会话归属租户 | POOL_NONE |
| 30 | `rag_chat_turn_operations` | 租户私有 | 是 | 是 | `V47__add_chat_turn_idempotency.sql` | 1 | 75 | 2 | 0 | 会话轮次幂等操作按租户内会话键控 | POOL_NONE |
| 31 | `rag_collection_provisioning_operation` | 租户私有 | 是 | 是 | `V52__collection_provisioning_idempotency.sql` | 1 | 18 | 1 | 2 | collection 供给幂等操作按租户内 collection 键控 | POOL_NONE |
| 32 | `rag_llm_usage_event` | 租户私有 | 是 | 是 | `V53__add_llm_usage_event.sql` | 1 | 77 | 2 | 0 | 用量事件随 principal 归属租户 | POOL_NONE |
| 33 | `rag_api_collection_operation_hourly` | 租户私有 | 是 | 是 | `V54__add_api_operation_rollups.sql` | 1 | 46 | 0 | 1 | 按 collection 聚合的运营指标，行可归租户 | POOL_NONE |
| 34 | `rag_chat_history_source_document` | 租户私有 | 是 | 是 | `V56__collection_purge_and_content_references.sql` | 1 | 4 | 0 | 1 | 会话来源引用随会话归属租户 | POOL_NONE |
| 35 | `rag_user_feedback_document` | 租户私有 | 是 | 是 | `V56__collection_purge_and_content_references.sql` | 1 | 4 | 0 | 1 | 反馈文档引用随反馈归属租户 | POOL_NONE |
| 36 | `rag_collection_purge_preview` | 租户私有 | 是 | 是 | `V56__collection_purge_and_content_references.sql` | 1 | 57 | 0 | 1 | 清除预览随 collection 归属租户 | POOL_NONE |
| 37 | `fs_import_batches` | 租户私有 | 是 | 是 | `V59__add_fs_import_batches.sql` | 1 | 12 | 2 | 1 | 文件导入批次随租户/collection 归属 | POOL_NONE |
| 38 | `rag_document_processing_runs` | 租户私有 | 是 | 是 | `V61__document_processing_runs.sql` | 1 | 42 | 0 | 4 | 处理 run 随文档归属租户 | POOL_NONE |
| 39 | `rag_document_processing_stages` | 租户私有 | 是 | 是 | `V61__document_processing_runs.sql` | 1 | 25 | 0 | 1 | 处理阶段随 run 归属租户 | POOL_NONE |
| 40 | `rag_chunking_upgrade_tasks` | 租户私有 | 是 | 是 | `V62__automatic_chunking_upgrade.sql` | 1 | 13 | 0 | 2 | chunk 升级任务随文档归属租户 | POOL_NONE |
| 41 | `rag_chunking_upgrade_retry_buckets` | 租户私有 | 是 | 是 | `V63__chunking_upgrade_recovery.sql` | 1 | 4 | 0 | 1 | 升级重试桶随任务归属租户 | POOL_NONE |
| 42 | `rag_long_running_operations` | 租户私有 | 是 | 是 | `V67__durable_operations_and_embedding_profiles.sql` | 1 | 52 | 2 | 3 | 长任务随发起 principal 归属租户，任务载荷自带租户上下文 | POOL_NONE |
| 43 | `rag_collection_operation_slot` | 租户私有 | 是 | 是 | `V67__durable_operations_and_embedding_profiles.sql` | 1 | 8 | 1 | 2 | collection 操作槽随 collection 归属租户 | POOL_NONE |
| 44 | `rag_long_running_operation_run_refs` | 租户私有 | 是 | 是 | `V67__durable_operations_and_embedding_profiles.sql` | 1 | 5 | 0 | 2 | run 引用随操作归属租户 | POOL_NONE |
| 45 | `rag_collection_purge_operation_targets` | 租户私有 | 是 | 是 | `V70__collection_purge_operation_targets.sql` | 1 | 6 | 0 | 1 | 清除目标随预览归属租户 | POOL_NONE |
| 46 | `rag_slo_configs` | 平台全局 | 否 | 否 | `V7__add_alerts.sql` | 1 | 10 | 1 | 0 | SLO 目标是平台级配置（slo_name 全局唯一） | "平台池：运营/告警模块读写" |
| 47 | `rag_audit_log` | 平台全局 | 否 | 否 | `V10__add_audit_log.sql` | 1 | 11 | 0 | 0 | 平台审计覆盖跨实体/跨租户操作，认证前后都有写入 | "平台池：审计写入与查询" |
| 48 | `rag_silence_schedules` | 平台全局 | 否 | 否 | `V12__add_silence_schedules.sql` | 1 | 11 | 1 | 0 | 告警静默是平台级配置（name 全局唯一） | "平台池：运营配置读写" |
| 49 | `rag_client_error` | 平台全局 | 否 | 否 | `V14__add_client_error_log.sql` | 1 | 10 | 0 | 0 | 浏览器遥测，无租户契约；引入租户上下文时重新评估 | "平台池：错误上报写入" |
| 50 | `rag_embedding_profiles` | 平台全局 | 否 | 否 | `V25__embedding_profile_expand.sql` | 1 | 12 | 2 | 0 | embedding 模型 profile 是全局配置（identity 全局唯一） | "平台池：配置读写；app 只读" |
| 51 | `rag_api_rate_limit_bucket` | 平台全局 | 否 | 否 | `V48__managed_api_principals_and_shared_quota.sql` | 1 | 8 | 0 | 0 | 限流桶在认证/租户解析路径上按 principal 键控 | "app 读写：请求准入限流" |
| 52 | `rag_api_admin_guard` | 平台全局 | 否 | 否 | `V48__managed_api_principals_and_shared_quota.sql` | 1 | 6 | 0 | 0 | 单例守卫表（singleton CHECK），平台管理面原子计数 | "平台池：管理端点读写" |
| 53 | `rag_llm_budget_policy` | 平台全局 | 否 | 否 | `V64__llm_budget_and_data_governance.sql` | 1 | 28 | 0 | 0 | 预算策略是平台成本治理配置（principal 维度，v1 不随租户隔离） | "平台池：配置读写；app 读策略" |
| 54 | `rag_llm_budget_window` | 平台全局 | 否 | 否 | `V64__llm_budget_and_data_governance.sql` | 1 | 17 | 0 | 0 | 预算窗口是平台成本记账（principal+window 键控） | "app 读写：预留/结算 CAS" |
| 55 | `rag_llm_budget_reservation` | 平台全局 | 否 | 否 | `V64__llm_budget_and_data_governance.sql` | 1 | 31 | 1 | 0 | 预算预留是平台成本记账，跨请求生命周期 | "app 读写：预留/结算" |
| 56 | `rag_api_operation_hourly` | 平台全局 | 否 | 否 | `V54__add_api_operation_rollups.sql` | 1 | 45 | 0 | 0 | 按 principal 聚合的全局运营指标，无租户键 | "app 写入：指标聚合" |
| 57 | `rag_alerts` | 平台全局 | 否 | 否 | `V7__add_alerts.sql` | 1 | 12 | 0 | 0 | 系统告警是平台运营面（v7 起无租户契约） | "平台池：告警写入/查询" |
| 58 | `rag_alert_notification_delivery` | 平台全局 | 否 | 否 | `V58__durable_alert_notification_delivery.sql` | 1 | 44 | 1 | 0 | 告警投递是平台运营面，租约由平台 worker 持有 | "平台池：投递 worker 读写" |
| 59 | `rag_long_running_operation_global_capacity` | 平台全局 | 否 | 否 | `V67__durable_operations_and_embedding_profiles.sql` | 1 | 3 | 0 | 0 | 全局容量单例（singleton_id=1），跨租户并发上限 | "app 读写：dispatcher 准入 CAS" |
| 60 | `rag_long_running_operation_owner_capacity` | 平台全局 | 否 | 否 | `V67__durable_operations_and_embedding_profiles.sql` | 1 | 3 | 0 | 0 | owner 容量是平台准入控制平面，v1 不随租户隔离 | "app 读写：dispatcher 准入 CAS" |
| 61 | `rag_collection_purge_preview_admission_global` | 平台全局 | 否 | 否 | `V67__durable_operations_and_embedding_profiles.sql` | 1 | 3 | 0 | 0 | 清除准入全局单例（singleton_id=1） | "app 读写：准入 CAS" |
| 62 | `rag_collection_purge_preview_admission_owner` | 平台全局 | 否 | 否 | `V67__durable_operations_and_embedding_profiles.sql` | 1 | 7 | 0 | 0 | owner 准入控制平面，v1 不随租户隔离 | "app 读写：准入 CAS" |
| 63 | `rag_embedding_backfill_control` | 平台全局 | 否 | 否 | `V67__durable_operations_and_embedding_profiles.sql` | 1 | 17 | 0 | 0 | 回填控制单飞（migration_key 主键），迁移控制面 | "平台池：迁移 worker 读写" |
| 64 | `rag_collection_purge_worker_slots` | 平台全局 | 否 | 否 | `V69__collection_purge_worker_slots.sql` | 1 | 11 | 1 | 1 | 全局 worker 槽位 1–8；FK 指向租户表属已知例外，WP2 处理跨类外键 | "app 读写：purge worker 租约" |
| 65 | `rag_data_erasure_operation` | 平台全局 | 否 | 否 | `V64__llm_budget_and_data_governance.sql` | 1 | 31 | 0 | 0 | 擦除操作跨多表编排并平台审计，v1 无租户契约；引入租户级擦除时重新评估 | "平台池：擦除编排读写" |
| 66 | `rag_api_key` | 凭据 | 否 | 否 | `V18__add_api_key_table.sql` | 1 | 8 | 1 | 0 | API Key 认证入口，按 digest 精确查找；认证前无租户上下文 | "app 角色 digest 精确查找；无平台池" |
| 67 | `rag_api_principal` | 凭据 | 否 | 否 | `V48__managed_api_principals_and_shared_quota.sql` | 1 | 18 | 0 | 0 | 受管 principal，认证入口与配额主体 | "app 角色 digest/ID 精确查找；无平台池" |
| 68 | `rag_api_key_rotation` | 凭据 | 否 | 否 | `V55__bounded_api_credential_rotation.sql` | 1 | 28 | 1 | 3 | 密钥轮换生命周期随凭据，无 RLS | "app 角色：轮换端点读写" |
| 69 | `rag_api_provisioning_operation` | 凭据 | 否 | 否 | `V50__api_key_provisioning_idempotency.sql` | 1 | 24 | 1 | 0 | 凭据供给幂等操作随凭据生命周期，无 RLS | "app 角色：供给端点读写" |

## 3. 已知例外与 WP2 落地要求

1. **跨类外键**：`rag_collection_purge_worker_slots.operation_id` FK 指向租户私有表
   `rag_long_running_operations`；平台全局表引用租户行属于已知例外，WP2 落地时由
   `rag_long_running_operations` 的复合外键 `(tenant_id, id)` 承接，或改无 FK+应用层校验（实施时定）。
2. **`collection_key` 全局唯一**：V27 定义的 `(collection_key)` 全局唯一在 WP2 改为
   `(tenant_id, collection_key)`；同步修正依赖它的幂等表与 index。
3. **`rag_llm_budget_*` / `rag_long_running_operation_owner_capacity` /
   `rag_collection_purge_preview_admission_owner`** 按 principal 键控、v1 不随租户隔离；
   引入租户级配额或租户切换 principal 时重新评估归属（规划 §9 已登记）。
4. **`SPRING_AI_CHAT_MEMORY`** 为 Spring AI 框架自带大写表名；WP2 通过适配层/迁移加列回填，
   不改框架 DDL 语义。
5. **`rag_audit_log` / `rag_client_error`** 平台遥测：写入点遍布认证前后，v1 无租户列；
   引入租户上下文后如需按租户审计，重新评估归属。
