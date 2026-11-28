export type TenantMode = 'SINGLE' | 'STRICT';
export type TenantStatus = 'ACTIVE' | 'SUSPENDED' | 'DISABLED' | 'SOFT_DELETED';
export type QuotaPeriod = 'DAY' | 'MONTH';
export type CredentialStatus = 'ACTIVE' | 'ROTATING' | 'EXPIRED' | 'REVOKED' | 'ORPHAN_NO_KEY';
export type OperatorErrorCode =
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'CSRF_REQUIRED'
  | 'TENANT_CONFLICT'
  | 'DEFAULT_TENANT_PROTECTED'
  | 'VALIDATION_ERROR';

export class OperatorApiError extends Error {
  readonly code: OperatorErrorCode;
  readonly status: number;
  readonly detail: string;

  constructor(code: OperatorErrorCode, status: number, detail: string) {
    super(detail);
    this.name = 'OperatorApiError';
    this.code = code;
    this.status = status;
    this.detail = detail;
  }
}

export interface TenantSummary {
  tenantId: string;
  displayName: string;
  status: TenantStatus;
  protectedDefault: boolean;
  principalCount: number;
  activeKeyCount: number;
  orphanPrincipalCount: number;
  dayTokenUsed: number;
  dayTokenLimit: number | null;
  monthCostUsedUsd: number;
  monthCostLimitUsd: number | null;
  collectionCount: number;
  documentCount: number;
  embeddingCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface QuotaPolicyRecord {
  tenantId: string;
  displayName: string;
  period: QuotaPeriod;
  enabled: boolean;
  maxTokens: number | null;
  maxCostUsd: number | null;
  usedTokens: number;
  usedCostUsd: number;
  reservedTokens: number;
  windowStart: string;
  windowEnd: string;
}

export interface CredentialRecord {
  principalId: string;
  tenantId: string;
  tenantStatus: TenantStatus;
  role: 'NORMAL' | 'ADMIN';
  keyId: string | null;
  keyPrefix: string | null;
  status: CredentialStatus;
  expiresAt: string | null;
  lastUsedAt: string | null;
  createdAt: string;
}

export interface IssuedApiKeyResult {
  tenantId: string;
  principalId: string;
  keyId: string;
  keyPrefix: string;
  rawKeyOnce: string;
  createdAt: string;
}

export interface KnowledgeHealthRow {
  tenantId: string;
  displayName: string;
  status: TenantStatus;
  collectionCount: number;
  documentCount: number;
  embeddingCount: number;
  estimatedStorageMb: number;
  embeddingQueuePending: number;
  purgeJobsPending: number;
  failedJobs24h: number;
  lastRecallCheckStatus: 'PASS' | 'WARN' | 'UNVERIFIED';
  lastRecallAt10: number | null;
}

export interface ComplianceSnapshot {
  tenantMode: TenantMode;
  rlsVerifierStatus: 'PASS' | 'WARN' | 'FAIL';
  rlsVerifierCheckedAt: string;
  rlsFindings: Array<{
    severity: 'P0' | 'P1' | 'INFO';
    code: string;
    message: string;
  }>;
  tableCatalog: {
    total: number;
    tenantPrivate: number;
    platformGlobal: number;
    credential: number;
  };
  endpointGuard: {
    totalRoutes: number;
    declaredRoutes: number;
    platformControlRoutes: number;
  };
  dbRoleChecks: {
    currentUser: string;
    isSuperuser: boolean;
    hasBypassRls: boolean;
    isTableOwner: boolean;
  };
}

export interface PlatformAuditEvent {
  seq: number;
  eventId: string;
  occurredAt: string;
  actor: string;
  actorType: 'ENVIRONMENT_ROOT' | 'LIFECYCLE_ACTOR' | 'OPERATOR_SESSION';
  action:
    | 'TENANT_CREATE'
    | 'TENANT_UPDATE'
    | 'TENANT_SUSPEND'
    | 'TENANT_RESUME'
    | 'TENANT_DISABLE'
    | 'TENANT_SOFT_DELETE'
    | 'API_KEY_ISSUE'
    | 'API_KEY_ROTATE'
    | 'API_KEY_REVOKE'
    | 'QUOTA_UPDATE'
    | 'SUPPORT_SESSION_START'
    | 'SUPPORT_SESSION_REVOKE';
  targetTenantId: string | null;
  targetResourceId: string | null;
  reason: string;
  outcome: 'SUCCESS' | 'DENIED';
}

export interface SupportSessionRecord {
  sessionId: string;
  tenantId: string;
  operatorActor: string;
  ticketRef: string;
  reason: string;
  readOnly: true;
  createdAt: string;
  expiresAt: string;
  revokedAt: string | null;
}

export interface TenantDetail {
  summary: TenantSummary;
  quotas: QuotaPolicyRecord[];
  credentials: CredentialRecord[];
  health: KnowledgeHealthRow;
  recentAudits: PlatformAuditEvent[];
}

export interface OperatorClient {
  readonly mode: 'mock' | 'real';
  listTenants(params?: { status?: TenantStatus | 'ALL'; q?: string; includeDeleted?: boolean }): Promise<TenantSummary[]>;
  getTenantDetail(tenantId: string): Promise<TenantDetail>;
  createTenant(input: { tenantId: string; displayName: string; issueBootstrapKey: boolean; reason: string }): Promise<{ tenant: TenantSummary; bootstrapKey?: IssuedApiKeyResult }>;
  updateTenant(tenantId: string, input: { displayName: string; reason: string }): Promise<TenantSummary>;
  transitionTenant(tenantId: string, action: 'suspend' | 'resume' | 'disable' | 'soft_delete', reason: string): Promise<TenantSummary>;
  listCredentials(params?: { tenantId?: string; status?: CredentialStatus | 'ALL'; orphanOnly?: boolean }): Promise<CredentialRecord[]>;
  issueTenantApiKey(tenantId: string, input: { principalId?: string; label: string; reason: string }): Promise<IssuedApiKeyResult>;
  revokeApiKey(keyId: string, reason: string): Promise<void>;
  listQuotas(params?: { period?: QuotaPeriod }): Promise<QuotaPolicyRecord[]>;
  updateQuota(tenantId: string, period: QuotaPeriod, input: { enabled: boolean; maxTokens: number | null; maxCostUsd: number | null; reason: string }): Promise<QuotaPolicyRecord>;
  listKnowledgeHealth(): Promise<KnowledgeHealthRow[]>;
  getComplianceSnapshot(): Promise<ComplianceSnapshot>;
  listAuditEvents(params?: { tenantId?: string; action?: string }): Promise<PlatformAuditEvent[]>;
  listSupportSessions(): Promise<SupportSessionRecord[]>;
  startSupportSession(input: { tenantId: string; ticketRef: string; reason: string; ttlMinutes: number }): Promise<SupportSessionRecord>;
  revokeSupportSession(sessionId: string, reason: string): Promise<void>;
}
