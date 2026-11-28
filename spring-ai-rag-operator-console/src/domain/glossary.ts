import type {
  ComplianceSnapshot,
  CredentialStatus,
  PlatformAuditEvent,
  TenantMode,
  TenantStatus,
} from './operatorContract';

/**
 * 全站统一中文词汇表（唯一事实源）。
 *
 * <p>界面禁止直接输出后端英文枚举与实现黑话；所有状态、动作、角色检查项
 * 都必须经过本文件映射。机读 ID（tenant_id / principal_id / key_id / seq）仍保留等宽展示。</p>
 */
export const TENANT_STATUS_LABEL: Record<TenantStatus, string> = {
  ACTIVE: '正常',
  SUSPENDED: '已暂停',
  DISABLED: '已禁用',
  SOFT_DELETED: '已软删',
};

export const TENANT_MODE_LABEL: Record<TenantMode, string> = {
  SINGLE: '单租户模式',
  STRICT: '严格多租户模式',
};

export const CREDENTIAL_STATUS_LABEL: Record<CredentialStatus, string> = {
  ACTIVE: '有效',
  ROTATING: '轮换中',
  EXPIRED: '已过期',
  REVOKED: '已吊销',
  ORPHAN_NO_KEY: '未签发密钥',
};

export const QUOTA_SWITCH_LABEL = {
  enabled: '已启用限额',
  unlimited: '不限额',
  reserved: '进行中预占',
} as const;

export const RECALL_STATUS_LABEL: Record<KnowledgeRecallStatus, string> = {
  PASS: '召回正常',
  WARN: '召回偏低',
  UNVERIFIED: '待压测验证',
};

type KnowledgeRecallStatus = 'PASS' | 'WARN' | 'UNVERIFIED';

export const RLS_VERIFIER_LABEL: Record<ComplianceSnapshot['rlsVerifierStatus'], string> = {
  PASS: '校验通过',
  WARN: '存在告警',
  FAIL: '校验失败',
};

export const AUDIT_ACTION_LABEL: Record<PlatformAuditEvent['action'], string> = {
  TENANT_CREATE: '新建租户',
  TENANT_UPDATE: '更新租户',
  TENANT_SUSPEND: '暂停租户',
  TENANT_RESUME: '恢复租户',
  TENANT_DISABLE: '禁用租户',
  TENANT_SOFT_DELETE: '软删租户',
  API_KEY_ISSUE: '签发密钥',
  API_KEY_ROTATE: '轮换密钥',
  API_KEY_REVOKE: '吊销密钥',
  QUOTA_UPDATE: '调整配额',
  SUPPORT_SESSION_START: '开启只读支持会话',
  SUPPORT_SESSION_REVOKE: '撤销只读支持会话',
};

export const AUDIT_OUTCOME_LABEL: Record<PlatformAuditEvent['outcome'], string> = {
  SUCCESS: '成功',
  DENIED: '已拒绝',
};

/** 数据库应用角色安全断言：标签 + 期望状态说明，原始技术项名保留在括号中供 DBA 核对。 */
export const DB_ROLE_CHECK_LABEL: Record<
  'isSuperuser' | 'hasBypassRls' | 'isTableOwner',
  { label: string; tech: string; safeText: string; riskyText: string }
> = {
  isSuperuser: {
    label: '非超级管理员',
    tech: 'rolsuper',
    safeText: '非超级管理员',
    riskyText: '超级管理员（高危）',
  },
  hasBypassRls: {
    label: '强制受 RLS 约束',
    tech: 'rolbypassrls',
    safeText: '强制受 RLS 约束',
    riskyText: '可绕过 RLS（高危）',
  },
  isTableOwner: {
    label: '非业务表拥有者',
    tech: 'is_table_owner',
    safeText: '非业务表拥有者',
    riskyText: '业务表拥有者（需核查）',
  },
};

export const ACCESS_PRINCIPAL_LABEL = '访问主体';
export const PENDING_KEY_PRINCIPAL_LABEL = '待发密钥主体';
export const COLLECTION_LABEL = '知识库';
export const DOCUMENT_LABEL = '文档';
export const EMBEDDING_LABEL = '向量分块';
