package com.springairag.core.tenant;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 租户表归属 catalog（WP1 产物，机器可读）。
 *
 * <p>这是多租户改造的单一事实清单：每张业务表的归属类别、裁决理由、RLS 决策、
 * tenant-leading 索引要求与平台池访问点都在此登记。对应的逐张裁决文档为
 * {@code docs/drafts/MULTI_TENANCY_TABLE_CATALOG.md}；两者必须保持一致，
 * 新增表必须先登记 catalog 与文档再写迁移。</p>
 *
 * <p>裁决规则（来自实施规划 §5.2，终审核 APPROVE 基线）：</p>
 * <ul>
 *   <li>TENANT_PRIVATE：行属于单一租户，必须 {@code tenant_id NOT NULL}、复合唯一/复合外键、
 *       tenant-leading 索引，RLS ENABLE + FORCE；</li>
 *   <li>PLATFORM_GLOBAL：单例/全局容量/全局配置/平台运营遥测，不启用 RLS，仅平台池或
 *       明确列出的访问点可写；</li>
 *   <li>CREDENTIAL：认证凭据及其生命周期表（key / principal / rotation / provisioning），
 *       认证发生在确定租户之前，为无 RLS 的平台全局表；V80 起带 {@code tenant_id} 绑定列并
 *       回填 {@code default}（SINGLE 守卫与凭据绑定租户的数据载体），app 角色按 digest 精确查找。</li>
 * </ul>
 *
 * <p>基线：WP3 后（V75–V80）的存活业务表全量登记；具体张数以迁移脚本实际
 * CREATE/DROP 结果为准（{@code rag_vector_store} 已 DROP 不登记，V75 新增 {@code rag_tenant}）。
 * 数量不写死，与迁移脚本的一致性由 TenantTableCatalogTest 动态基线校验。</p>
 */
public final class TenantTableCatalog {

    /** 表归属类别。 */
    public enum TableOwnership {
        /** 租户私有：RLS 兜底的安全边界。 */
        TENANT_PRIVATE,
        /** 平台全局：单例 / 全局容量 / 全局配置 / 平台运营面。 */
        PLATFORM_GLOBAL,
        /** 认证凭据：认证发生在确定租户之前，无 RLS，digest 精确查找。 */
        CREDENTIAL
    }

    /**
     * 单张表的归属裁决。
     *
     * @param tableName                表名（小写，无 schema 前缀；Spring AI 框架表为框架自带大写名）
     * @param ownership                归属类别
     * @param reason                   裁决理由（一句话，面向冷读者）
     * @param rlsEnabled               是否启用 RLS（ENABLE + FORCE + 规范 policy）
     * @param tenantLeadingIndexRequired是否要求 tenant-leading 复合索引（WP2 落地）
     * @param platformPoolAccess       平台池访问点说明；无访问点时为「无」
     */
    public record TableEntry(
            String tableName,
            TableOwnership ownership,
            String reason,
            boolean rlsEnabled,
            boolean tenantLeadingIndexRequired,
            String platformPoolAccess) {
    }

    private static final String POOL_NONE = "无";

    private static final List<TableEntry> ENTRIES = List.of(
            // ---- 租户私有（45）：业务数据与租户内二级边界 ----
            entry("rag_collection", TableOwnership.TENANT_PRIVATE, "租户内知识库根实体，租户是安全边界的第一层", POOL_NONE),
            entry("rag_documents", TableOwnership.TENANT_PRIVATE, "文档归属 collection，collection 归属租户", POOL_NONE),
            entry("rag_embeddings", TableOwnership.TENANT_PRIVATE, "向量主存储，RLS 过滤是检索隔离的兜底（WP2 大表专项）", POOL_NONE),
            entry("rag_chat_history", TableOwnership.TENANT_PRIVATE, "会话消息归属租户内会话", POOL_NONE),
            entry("SPRING_AI_CHAT_MEMORY", TableOwnership.TENANT_PRIVATE, "框架会话记忆表，行随会话归属租户（WP2 加列回填）", POOL_NONE),
            entry("rag_retrieval_logs", TableOwnership.TENANT_PRIVATE, "检索日志随请求归属租户", POOL_NONE),
            entry("rag_retrieval_evaluations", TableOwnership.TENANT_PRIVATE, "评估结果随 collection 归属租户", POOL_NONE),
            entry("rag_user_feedback", TableOwnership.TENANT_PRIVATE, "用户反馈随会话/文档归属租户", POOL_NONE),
            entry("rag_ab_experiments", TableOwnership.TENANT_PRIVATE, "A/B 实验在租户内 collection 上定义", POOL_NONE),
            entry("rag_ab_results", TableOwnership.TENANT_PRIVATE, "实验结果随实验归属租户", POOL_NONE),
            entry("rag_document_versions", TableOwnership.TENANT_PRIVATE, "文档版本随文档归属租户", POOL_NONE),
            entry("fs_files", TableOwnership.TENANT_PRIVATE, "上传文件随 collection 归属租户", POOL_NONE),
            entry("rag_document_embedding_state", TableOwnership.TENANT_PRIVATE, "文档 embedding 状态随文档归属租户", POOL_NONE),
            entry("rag_chat_session_lease", TableOwnership.TENANT_PRIVATE, "会话租约随会话归属租户", POOL_NONE),
            entry("rag_embedding_jobs", TableOwnership.TENANT_PRIVATE, "embedding 任务随文档归属租户，任务载荷自带租户上下文", POOL_NONE),
            entry("rag_evaluation_suites", TableOwnership.TENANT_PRIVATE, "评估套件是租户内资产", POOL_NONE),
            entry("rag_evaluation_suite_versions", TableOwnership.TENANT_PRIVATE, "套件版本随套件归属租户", POOL_NONE),
            entry("rag_evaluation_runs", TableOwnership.TENANT_PRIVATE, "评估运行随套件归属租户", POOL_NONE),
            entry("rag_evaluation_case_results", TableOwnership.TENANT_PRIVATE, "用例结果随运行归属租户", POOL_NONE),
            entry("rag_document_source_namespaces", TableOwnership.TENANT_PRIVATE, "源命名空间随文档/租户登记", POOL_NONE),
            entry("rag_document_idempotency_operations", TableOwnership.TENANT_PRIVATE, "文档幂等操作按租户内文档键控", POOL_NONE),
            entry("rag_document_sync_runs", TableOwnership.TENANT_PRIVATE, "同步 run 随文档归属租户，run 载荷自带租户上下文", POOL_NONE),
            entry("rag_document_sync_run_items", TableOwnership.TENANT_PRIVATE, "同步条目随 run 归属租户", POOL_NONE),
            entry("rag_document_chunks", TableOwnership.TENANT_PRIVATE, "关键词 chunk 随文档归属租户", POOL_NONE),
            entry("rag_document_local_index_state", TableOwnership.TENANT_PRIVATE, "本地索引状态随文档归属租户", POOL_NONE),
            entry("rag_document_relocated_addresses", TableOwnership.TENANT_PRIVATE, "搬迁地址随文档归属租户", POOL_NONE),
            entry("rag_derivation_repair_previews", TableOwnership.TENANT_PRIVATE, "派生修复预览随文档归属租户", POOL_NONE),
            entry("rag_derivation_repair_items", TableOwnership.TENANT_PRIVATE, "修复条目随预览归属租户", POOL_NONE),
            entry("rag_chat_memory_summary", TableOwnership.TENANT_PRIVATE, "会话摘要随会话归属租户", POOL_NONE),
            entry("rag_chat_turn_operations", TableOwnership.TENANT_PRIVATE, "会话轮次幂等操作按租户内会话键控", POOL_NONE),
            entry("rag_collection_provisioning_operation", TableOwnership.TENANT_PRIVATE, "collection 供给幂等操作按租户内 collection 键控", POOL_NONE),
            entry("rag_llm_usage_event", TableOwnership.TENANT_PRIVATE, "用量事件随 principal 归属租户", POOL_NONE),
            entry("rag_api_collection_operation_hourly", TableOwnership.TENANT_PRIVATE, "按 collection 聚合的运营指标，行可归租户", POOL_NONE),
            entry("rag_chat_history_source_document", TableOwnership.TENANT_PRIVATE, "会话来源引用随会话归属租户", POOL_NONE),
            entry("rag_user_feedback_document", TableOwnership.TENANT_PRIVATE, "反馈文档引用随反馈归属租户", POOL_NONE),
            entry("rag_collection_purge_preview", TableOwnership.TENANT_PRIVATE, "清除预览随 collection 归属租户", POOL_NONE),
            entry("fs_import_batches", TableOwnership.TENANT_PRIVATE, "文件导入批次随租户/collection 归属", POOL_NONE),
            entry("rag_document_processing_runs", TableOwnership.TENANT_PRIVATE, "处理 run 随文档归属租户", POOL_NONE),
            entry("rag_document_processing_stages", TableOwnership.TENANT_PRIVATE, "处理阶段随 run 归属租户", POOL_NONE),
            entry("rag_chunking_upgrade_tasks", TableOwnership.TENANT_PRIVATE, "chunk 升级任务随文档归属租户", POOL_NONE),
            entry("rag_chunking_upgrade_retry_buckets", TableOwnership.TENANT_PRIVATE, "升级重试桶随任务归属租户", POOL_NONE),
            entry("rag_long_running_operations", TableOwnership.TENANT_PRIVATE, "长任务随发起 principal 归属租户，任务载荷自带租户上下文", POOL_NONE),
            entry("rag_collection_operation_slot", TableOwnership.TENANT_PRIVATE, "collection 操作槽随 collection 归属租户", POOL_NONE),
            entry("rag_long_running_operation_run_refs", TableOwnership.TENANT_PRIVATE, "run 引用随操作归属租户", POOL_NONE),
            entry("rag_collection_purge_operation_targets", TableOwnership.TENANT_PRIVATE, "清除目标随预览归属租户", POOL_NONE),
            // ---- WP8 新增（V82）：结果/回调持久化证据表，自带 RLS + 规范 policy ----
            entry("rag_tenant_result_receipt", TableOwnership.TENANT_PRIVATE, "结果 owner 四元组（tenant/collection/owner/receipt）持久化证据，禁止任意 task ID 触发外部 Provider 查询", POOL_NONE),
            entry("rag_tenant_callback_receipt", TableOwnership.TENANT_PRIVATE, "回调权威 receipt + response-lost durable intent，按 operation/client_token 幂等绑定 Provider task", POOL_NONE),
            entry("rag_tenant_callback_replay_nonce", TableOwnership.TENANT_PRIVATE, "回调 replay nonce 持久化，首次 claim 成功、重复即拒绝；callback 与 manual audit principal 分离", POOL_NONE),

            // ---- WP10 新增（V84）：租户级配额与用量（PLATFORM_GLOBAL，按 tenant_id 键控，无 RLS）----
            entry("rag_tenant_quota_policy", TableOwnership.PLATFORM_GLOBAL, "租户级硬阈值策略（platform 管理面配置；不是 billing/充值），按 tenant_id 唯一", "平台池：配置读写；app 只读政策"),
            entry("rag_tenant_quota_window", TableOwnership.PLATFORM_GLOBAL, "租户级 DAY/MONTH 窗口聚合账本（reserved/settled tokens），CAS 硬阈值", "app 读写：预留/结算 CAS 与用量 API 聚合"),
            entry("rag_tenant_quota_reservation", TableOwnership.PLATFORM_GLOBAL, "principal 预算预留 → 租户级预留映射；异步 settle/release/TTL 从 DB 恢复租户窗口", "app 读写：预留/结算/释放"),

            // ---- 平台全局（20）：单例 / 全局容量 / 全局配置 / 平台运营遥测 ----
            entry("rag_slo_configs", TableOwnership.PLATFORM_GLOBAL, "SLO 目标是平台级配置（slo_name 全局唯一）", "平台池：运营/告警模块读写"),
            entry("rag_audit_log", TableOwnership.PLATFORM_GLOBAL, "平台审计覆盖跨实体/跨租户操作，认证前后都有写入", "平台池：审计写入与查询"),
            entry("rag_silence_schedules", TableOwnership.PLATFORM_GLOBAL, "告警静默是平台级配置（name 全局唯一）", "平台池：运营配置读写"),
            entry("rag_client_error", TableOwnership.PLATFORM_GLOBAL, "浏览器遥测，无租户契约；引入租户上下文时重新评估", "平台池：错误上报写入"),
            entry("rag_embedding_profiles", TableOwnership.PLATFORM_GLOBAL, "embedding 模型 profile 是全局配置（identity 全局唯一）", "平台池：配置读写；app 只读"),
            entry("rag_api_rate_limit_bucket", TableOwnership.PLATFORM_GLOBAL, "限流桶在认证/租户解析路径上按 principal 键控", "app 读写：请求准入限流"),
            entry("rag_api_admin_guard", TableOwnership.PLATFORM_GLOBAL, "单例守卫表（singleton CHECK），平台管理面原子计数", "平台池：管理端点读写"),
            entry("rag_llm_budget_policy", TableOwnership.PLATFORM_GLOBAL, "预算策略是平台成本治理配置（principal 维度，v1 不随租户隔离）", "平台池：配置读写；app 读策略"),
            entry("rag_llm_budget_window", TableOwnership.PLATFORM_GLOBAL, "预算窗口是平台成本记账（principal+window 键控）", "app 读写：预留/结算 CAS"),
            entry("rag_llm_budget_reservation", TableOwnership.PLATFORM_GLOBAL, "预算预留是平台成本记账，跨请求生命周期", "app 读写：预留/结算"),
            entry("rag_api_operation_hourly", TableOwnership.PLATFORM_GLOBAL, "按 principal 聚合的全局运营指标，无租户键", "app 写入：指标聚合"),
            entry("rag_alerts", TableOwnership.PLATFORM_GLOBAL, "系统告警是平台运营面（v7 起无租户契约）", "平台池：告警写入/查询"),
            entry("rag_alert_notification_delivery", TableOwnership.PLATFORM_GLOBAL, "告警投递是平台运营面，租约由平台 worker 持有", "平台池：投递 worker 读写"),
            entry("rag_long_running_operation_global_capacity", TableOwnership.PLATFORM_GLOBAL, "全局容量单例（singleton_id=1），跨租户并发上限", "app 读写：dispatcher 准入 CAS"),
            entry("rag_long_running_operation_owner_capacity", TableOwnership.PLATFORM_GLOBAL, "owner 容量是平台准入控制平面，v1 不随租户隔离", "app 读写：dispatcher 准入 CAS"),
            entry("rag_collection_purge_preview_admission_global", TableOwnership.PLATFORM_GLOBAL, "清除准入全局单例（singleton_id=1）", "app 读写：准入 CAS"),
            entry("rag_collection_purge_preview_admission_owner", TableOwnership.PLATFORM_GLOBAL, "owner 准入控制平面，v1 不随租户隔离", "app 读写：准入 CAS"),
            entry("rag_embedding_backfill_control", TableOwnership.PLATFORM_GLOBAL, "回填控制单飞（migration_key 主键），迁移控制面", "平台池：迁移 worker 读写"),
            entry("rag_collection_purge_worker_slots", TableOwnership.PLATFORM_GLOBAL, "全局 worker 槽位 1–8；FK 指向租户表属已知例外，WP2 处理跨类外键", "app 读写：purge worker 租约"),
            entry("rag_data_erasure_operation", TableOwnership.PLATFORM_GLOBAL, "擦除操作跨多表编排并平台审计，v1 无租户契约；引入租户级擦除时重新评估", "平台池：擦除编排读写"),

            // ---- 凭据（4）：认证发生在确定租户之前 ----
            entry("rag_api_key", TableOwnership.CREDENTIAL, "API Key 认证入口，按 digest 精确查找；认证前无租户上下文", "app 角色 digest 精确查找；无平台池"),
            entry("rag_api_principal", TableOwnership.CREDENTIAL, "受管 principal，认证入口与配额主体", "app 角色 digest/ID 精确查找；无平台池"),
            entry("rag_api_key_rotation", TableOwnership.CREDENTIAL, "密钥轮换生命周期随凭据，无 RLS", "app 角色：轮换端点读写"),
            entry("rag_api_provisioning_operation", TableOwnership.CREDENTIAL, "凭据供给幂等操作随凭据生命周期，无 RLS", "app 角色：供给端点读写"),

            // ---- WP2 新增（V75）：租户主表，平台全局，认证前即需存在 ----
            entry("rag_tenant", TableOwnership.PLATFORM_GLOBAL,
                    "V75 租户主表，跨租户生命周期由平台池管理；认证/租户解析前即需读取",
                    "平台池：生命周期端点读写；app 只读 tenant 状态")
    );

    private static TableEntry entry(String tableName, TableOwnership ownership,
                                    String reason, String platformPoolAccess) {
        boolean tenantPrivate = ownership == TableOwnership.TENANT_PRIVATE;
        return new TableEntry(tableName, ownership, reason,
                tenantPrivate, tenantPrivate, platformPoolAccess);
    }

    private static final Map<String, TableEntry> BY_NAME = ENTRIES.stream()
            .collect(Collectors.toUnmodifiableMap(TableEntry::tableName, Function.identity()));

    private TenantTableCatalog() {
    }

    /** 全量登记表清单（不写死张数，以迁移脚本实际存活业务表为准）。 */
    public static List<TableEntry> entries() {
        return ENTRIES;
    }

    /** 按表名取回登记项；未登记即拒绝（fail-closed）。 */
    public static TableEntry requireRegistered(String tableName) {
        TableEntry entry = BY_NAME.get(tableName);
        if (entry == null) {
            throw new IllegalArgumentException(
                    "表 " + tableName + " 未登记到 TenantTableCatalog："
                            + "新增表必须先更新 catalog 与 docs/drafts/MULTI_TENANCY_TABLE_CATALOG.md，再写迁移");
        }
        return entry;
    }

    /** 是否登记（只读判断，用于校验器与测试）。 */
    public static boolean isRegistered(String tableName) {
        return BY_NAME.containsKey(tableName);
    }
}
