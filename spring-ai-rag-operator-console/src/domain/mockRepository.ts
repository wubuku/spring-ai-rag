import {
  createInitialAudits,
  createInitialCompliance,
  createInitialCredentials,
  createInitialKnowledgeHealth,
  createInitialQuotas,
  createInitialSupportSessions,
  createInitialTenants,
  MOCK_NOW,
} from './fixtures';
import {
  OperatorApiError,
  type ComplianceSnapshot,
  type CredentialRecord,
  type CredentialStatus,
  type IssuedApiKeyResult,
  type KnowledgeHealthRow,
  type OperatorClient,
  type OperatorErrorCode,
  type PlatformAuditEvent,
  type QuotaPeriod,
  type QuotaPolicyRecord,
  type SupportSessionRecord,
  type TenantDetail,
  type TenantStatus,
  type TenantSummary,
} from './operatorContract';

export interface MockFaultConfig {
  forcedError: OperatorErrorCode | null;
  delayMs: number;
}

class MockOperatorRepository implements OperatorClient {
  readonly mode = 'mock' as const;

  private tenants: TenantSummary[] = [];
  private credentials: CredentialRecord[] = [];
  private quotas: QuotaPolicyRecord[] = [];
  private health: KnowledgeHealthRow[] = [];
  private compliance: ComplianceSnapshot = createInitialCompliance();
  private audits: PlatformAuditEvent[] = [];
  private supportSessions: SupportSessionRecord[] = [];
  private nextSeq = 113;
  private fault: MockFaultConfig = { forcedError: null, delayMs: 0 };

  constructor() {
    this.reset();
  }

  reset(): void {
    this.tenants = createInitialTenants();
    this.credentials = createInitialCredentials();
    this.quotas = createInitialQuotas(this.tenants);
    this.health = createInitialKnowledgeHealth(this.tenants);
    this.compliance = createInitialCompliance();
    this.audits = createInitialAudits();
    this.supportSessions = createInitialSupportSessions();
    this.nextSeq = 113;
    this.fault = { forcedError: null, delayMs: 0 };
  }

  getFaultConfig(): MockFaultConfig {
    return { ...this.fault };
  }

  setFaultConfig(patch: Partial<MockFaultConfig>): void {
    this.fault = { ...this.fault, ...patch };
  }

  private async maybeFailOrDelay(): Promise<void> {
    if (this.fault.delayMs > 0) {
      await new Promise((r) => setTimeout(r, this.fault.delayMs));
    }
    if (this.fault.forcedError) {
      const code = this.fault.forcedError;
      const statusMap: Record<OperatorErrorCode, number> = {
        UNAUTHORIZED: 401,
        FORBIDDEN: 403,
        CSRF_REQUIRED: 403,
        TENANT_CONFLICT: 409,
        DEFAULT_TENANT_PROTECTED: 409,
        VALIDATION_ERROR: 400,
      };
      const detailMap: Record<OperatorErrorCode, string> = {
        UNAUTHORIZED: '模拟 401：运营方会话已失效，请重新认证。',
        FORBIDDEN: '模拟 403：当前主体不在 rag.tenant.lifecycle-actors 白名单内。',
        CSRF_REQUIRED: '模拟 403：缺少有效 X-CSRF-Token 请求头。',
        TENANT_CONFLICT: '模拟 409：租户状态或并发版本冲突。',
        DEFAULT_TENANT_PROTECTED: '模拟 409：默认租户 default 受保护。',
        VALIDATION_ERROR: '模拟 400：请求参数校验失败。',
      };
      throw new OperatorApiError(code, statusMap[code], detailMap[code]);
    }
  }

  private appendAudit(
    action: PlatformAuditEvent['action'],
    targetTenantId: string | null,
    targetResourceId: string | null,
    reason: string,
    outcome: 'SUCCESS' | 'DENIED' = 'SUCCESS',
  ): PlatformAuditEvent {
    const seq = this.nextSeq++;
    const evt: PlatformAuditEvent = {
      seq,
      eventId: `evt_${String(seq).padStart(4, '0')}`,
      occurredAt: MOCK_NOW,
      actor: 'environment-root',
      actorType: 'ENVIRONMENT_ROOT',
      action,
      targetTenantId,
      targetResourceId,
      reason: reason.trim() || '（未填原因）',
      outcome,
    };
    this.audits = [evt, ...this.audits];
    return evt;
  }

  async listTenants(params?: { status?: TenantStatus | 'ALL'; q?: string; includeDeleted?: boolean }): Promise<TenantSummary[]> {
    await this.maybeFailOrDelay();
    const includeDeleted = params?.includeDeleted ?? false;
    const status = params?.status ?? 'ALL';
    const q = (params?.q ?? '').trim().toLowerCase();
    return this.tenants
      .filter((t) => includeDeleted || t.status !== 'SOFT_DELETED')
      .filter((t) => status === 'ALL' || t.status === status)
      .filter((t) => !q || t.tenantId.toLowerCase().includes(q) || t.displayName.toLowerCase().includes(q))
      .map((t) => ({ ...t }));
  }

  async getTenantDetail(tenantId: string): Promise<TenantDetail> {
    await this.maybeFailOrDelay();
    const summary = this.tenants.find((t) => t.tenantId === tenantId);
    if (!summary) {
      throw new OperatorApiError('VALIDATION_ERROR', 404, `未找到租户：${tenantId}`);
    }
    const health = this.health.find((h) => h.tenantId === tenantId) ?? {
      tenantId: summary.tenantId,
      displayName: summary.displayName,
      status: summary.status,
      collectionCount: summary.collectionCount,
      documentCount: summary.documentCount,
      embeddingCount: summary.embeddingCount,
      estimatedStorageMb: 0,
      embeddingQueuePending: 0,
      purgeJobsPending: 0,
      failedJobs24h: 0,
      lastRecallCheckStatus: 'UNVERIFIED' as const,
      lastRecallAt10: null,
    };
    return {
      summary: { ...summary },
      quotas: this.quotas.filter((q) => q.tenantId === tenantId).map((q) => ({ ...q })),
      credentials: this.credentials.filter((c) => c.tenantId === tenantId).map((c) => ({ ...c })),
      health: { ...health },
      recentAudits: this.audits.filter((a) => a.targetTenantId === tenantId).slice(0, 10),
    };
  }

  async createTenant(input: { tenantId: string; displayName: string; issueBootstrapKey: boolean; reason: string }): Promise<{ tenant: TenantSummary; bootstrapKey?: IssuedApiKeyResult }> {
    await this.maybeFailOrDelay();
    const slug = input.tenantId.trim().toLowerCase();
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(slug)) {
      throw new OperatorApiError('VALIDATION_ERROR', 400, 'tenantId 须匹配 ^[a-z0-9][a-z0-9_-]{0,63}$');
    }
    if (this.tenants.some((t) => t.tenantId === slug)) {
      throw new OperatorApiError('TENANT_CONFLICT', 409, `租户已存在：${slug}`);
    }
    const principalId = `rag_t_${slug.replace(/[^a-z0-9]/g, '').slice(0, 8)}_boot`;
    const tenant: TenantSummary = {
      tenantId: slug,
      displayName: input.displayName.trim() || slug,
      status: 'ACTIVE',
      protectedDefault: false,
      principalCount: 1,
      activeKeyCount: input.issueBootstrapKey ? 1 : 0,
      orphanPrincipalCount: input.issueBootstrapKey ? 0 : 1,
      dayTokenUsed: 0,
      dayTokenLimit: 500000,
      monthCostUsedUsd: 0,
      monthCostLimitUsd: 150,
      collectionCount: 0,
      documentCount: 0,
      embeddingCount: 0,
      createdAt: MOCK_NOW,
      updatedAt: MOCK_NOW,
    };
    this.tenants = [tenant, ...this.tenants];
    this.quotas = [...createInitialQuotas([tenant]), ...this.quotas];
    this.health = [createInitialKnowledgeHealth([tenant])[0], ...this.health];
    this.appendAudit('TENANT_CREATE', slug, slug, input.reason);

    let bootstrapKey: IssuedApiKeyResult | undefined;
    if (input.issueBootstrapKey) {
      const keyId = `rag_k_${slug.slice(0, 4)}${String(this.nextSeq).padStart(6, '0')}`;
      const rawKeyOnce = `rag_sk_mock_${slug}_${this.nextSeq}_only_shown_once`;
      this.credentials = [
        {
          principalId,
          tenantId: slug,
          tenantStatus: 'ACTIVE',
          role: 'NORMAL',
          keyId,
          keyPrefix: `${keyId.slice(0, 12)}...`,
          status: 'ACTIVE',
          expiresAt: '2027-02-27T00:00:00Z',
          lastUsedAt: null,
          createdAt: MOCK_NOW,
        },
        ...this.credentials,
      ];
      bootstrapKey = { tenantId: slug, principalId, keyId, keyPrefix: `${keyId.slice(0, 12)}...`, rawKeyOnce, createdAt: MOCK_NOW };
      this.appendAudit('API_KEY_ISSUE', slug, keyId, `开户同时签发初始密钥：${input.reason}`);
    } else {
      this.credentials = [
        {
          principalId,
          tenantId: slug,
          tenantStatus: 'ACTIVE',
          role: 'NORMAL',
          keyId: null,
          keyPrefix: null,
          status: 'ORPHAN_NO_KEY',
          expiresAt: null,
          lastUsedAt: null,
          createdAt: MOCK_NOW,
        },
        ...this.credentials,
      ];
    }
    return { tenant: { ...tenant }, bootstrapKey };
  }

  async updateTenant(tenantId: string, input: { displayName: string; reason: string }): Promise<TenantSummary> {
    await this.maybeFailOrDelay();
    const target = this.tenants.find((t) => t.tenantId === tenantId);
    if (!target) throw new OperatorApiError('VALIDATION_ERROR', 404, `未找到租户：${tenantId}`);
    target.displayName = input.displayName.trim() || target.displayName;
    target.updatedAt = MOCK_NOW;
    this.quotas.forEach((q) => { if (q.tenantId === tenantId) q.displayName = target.displayName; });
    this.health.forEach((h) => { if (h.tenantId === tenantId) h.displayName = target.displayName; });
    this.appendAudit('TENANT_UPDATE', tenantId, tenantId, input.reason);
    return { ...target };
  }

  async transitionTenant(tenantId: string, action: 'suspend' | 'resume' | 'disable' | 'soft_delete', reason: string): Promise<TenantSummary> {
    await this.maybeFailOrDelay();
    const target = this.tenants.find((t) => t.tenantId === tenantId);
    if (!target) throw new OperatorApiError('VALIDATION_ERROR', 404, `未找到租户：${tenantId}`);
    if (target.protectedDefault && (action === 'disable' || action === 'soft_delete')) {
      this.appendAudit(action === 'disable' ? 'TENANT_DISABLE' : 'TENANT_SOFT_DELETE', tenantId, tenantId, reason, 'DENIED');
      throw new OperatorApiError('DEFAULT_TENANT_PROTECTED', 409, '默认租户 default 受保护，禁止禁用或软删。');
    }
    const nextStatusMap: Record<typeof action, TenantStatus> = {
      suspend: 'SUSPENDED',
      resume: 'ACTIVE',
      disable: 'DISABLED',
      soft_delete: 'SOFT_DELETED',
    };
    const auditActionMap: Record<typeof action, PlatformAuditEvent['action']> = {
      suspend: 'TENANT_SUSPEND',
      resume: 'TENANT_RESUME',
      disable: 'TENANT_DISABLE',
      soft_delete: 'TENANT_SOFT_DELETE',
    };
    target.status = nextStatusMap[action];
    target.updatedAt = MOCK_NOW;
    this.credentials.forEach((c) => { if (c.tenantId === tenantId) c.tenantStatus = target.status; });
    this.health.forEach((h) => { if (h.tenantId === tenantId) h.status = target.status; });
    this.appendAudit(auditActionMap[action], tenantId, tenantId, reason);
    return { ...target };
  }

  async listCredentials(params?: { tenantId?: string; status?: CredentialStatus | 'ALL'; orphanOnly?: boolean }): Promise<CredentialRecord[]> {
    await this.maybeFailOrDelay();
    return this.credentials
      .filter((c) => !params?.tenantId || c.tenantId === params.tenantId)
      .filter((c) => !params?.orphanOnly || c.status === 'ORPHAN_NO_KEY')
      .filter((c) => !params?.status || params.status === 'ALL' || c.status === params.status)
      .map((c) => ({ ...c }));
  }

  async issueTenantApiKey(tenantId: string, input: { principalId?: string; label: string; reason: string }): Promise<IssuedApiKeyResult> {
    await this.maybeFailOrDelay();
    const tenant = this.tenants.find((t) => t.tenantId === tenantId);
    if (!tenant || tenant.status !== 'ACTIVE') {
      throw new OperatorApiError('TENANT_CONFLICT', 409, `仅 ACTIVE 租户允许签发 API Key：${tenantId}`);
    }
    const keyId = `rag_k_${tenantId.slice(0, 4)}${String(this.nextSeq).padStart(6, '0')}`;
    const rawKeyOnce = `rag_sk_mock_${tenantId}_${this.nextSeq}_once`;
    const keyPrefix = `${keyId.slice(0, 12)}...`;

    let principalId = input.principalId?.trim();
    if (principalId) {
      const existing = this.credentials.find((c) => c.principalId === principalId && c.tenantId === tenantId);
      if (existing) {
        existing.keyId = keyId;
        existing.keyPrefix = keyPrefix;
        existing.status = 'ACTIVE';
        existing.expiresAt = '2027-02-27T00:00:00Z';
        tenant.activeKeyCount += 1;
        tenant.orphanPrincipalCount = Math.max(0, tenant.orphanPrincipalCount - 1);
      }
    } else {
      principalId = `rag_p_${tenantId.slice(0, 6)}_${this.nextSeq}`;
      this.credentials = [
        {
          principalId,
          tenantId,
          tenantStatus: tenant.status,
          role: 'NORMAL',
          keyId,
          keyPrefix,
          status: 'ACTIVE',
          expiresAt: '2027-02-27T00:00:00Z',
          lastUsedAt: null,
          createdAt: MOCK_NOW,
        },
        ...this.credentials,
      ];
      tenant.principalCount += 1;
      tenant.activeKeyCount += 1;
    }
    this.appendAudit('API_KEY_ISSUE', tenantId, keyId, `${input.label || '业务密钥'}：${input.reason}`);
    return { tenantId, principalId, keyId, keyPrefix, rawKeyOnce, createdAt: MOCK_NOW };
  }

  async revokeApiKey(keyId: string, reason: string): Promise<void> {
    await this.maybeFailOrDelay();
    const cred = this.credentials.find((c) => c.keyId === keyId);
    if (!cred) throw new OperatorApiError('VALIDATION_ERROR', 404, `未找到密钥：${keyId}`);
    if (cred.status !== 'REVOKED') {
      cred.status = 'REVOKED';
      const tenant = this.tenants.find((t) => t.tenantId === cred.tenantId);
      if (tenant) tenant.activeKeyCount = Math.max(0, tenant.activeKeyCount - 1);
    }
    this.appendAudit('API_KEY_REVOKE', cred.tenantId, keyId, reason);
  }

  async listQuotas(params?: { period?: QuotaPeriod }): Promise<QuotaPolicyRecord[]> {
    await this.maybeFailOrDelay();
    return this.quotas
      .filter((q) => !params?.period || q.period === params.period)
      .map((q) => ({ ...q }));
  }

  async updateQuota(tenantId: string, period: QuotaPeriod, input: { enabled: boolean; maxTokens: number | null; maxCostUsd: number | null; reason: string }): Promise<QuotaPolicyRecord> {
    await this.maybeFailOrDelay();
    const target = this.quotas.find((q) => q.tenantId === tenantId && q.period === period);
    if (!target) throw new OperatorApiError('VALIDATION_ERROR', 404, `未找到租户配额：${tenantId}`);
    target.enabled = input.enabled;
    target.maxTokens = input.maxTokens;
    target.maxCostUsd = input.maxCostUsd;
    const tenant = this.tenants.find((t) => t.tenantId === tenantId);
    if (tenant) {
      if (period === 'DAY') tenant.dayTokenLimit = input.enabled ? input.maxTokens : null;
      if (period === 'MONTH') tenant.monthCostLimitUsd = input.enabled ? input.maxCostUsd : null;
      tenant.updatedAt = MOCK_NOW;
    }
    this.appendAudit('QUOTA_UPDATE', tenantId, `${tenantId}:${period}`, input.reason);
    return { ...target };
  }

  async listKnowledgeHealth(): Promise<KnowledgeHealthRow[]> {
    await this.maybeFailOrDelay();
    return this.health.map((h) => ({ ...h }));
  }

  async getComplianceSnapshot(): Promise<ComplianceSnapshot> {
    await this.maybeFailOrDelay();
    return {
      ...this.compliance,
      rlsFindings: [...this.compliance.rlsFindings],
      tableCatalog: { ...this.compliance.tableCatalog },
      endpointGuard: { ...this.compliance.endpointGuard },
      dbRoleChecks: { ...this.compliance.dbRoleChecks },
    };
  }

  async listAuditEvents(params?: { tenantId?: string; action?: string }): Promise<PlatformAuditEvent[]> {
    await this.maybeFailOrDelay();
    return this.audits
      .filter((a) => !params?.tenantId || a.targetTenantId === params.tenantId)
      .filter((a) => !params?.action || a.action === params.action)
      .map((a) => ({ ...a }));
  }

  async listSupportSessions(): Promise<SupportSessionRecord[]> {
    await this.maybeFailOrDelay();
    return this.supportSessions.map((s) => ({ ...s }));
  }

  async startSupportSession(input: { tenantId: string; ticketRef: string; reason: string; ttlMinutes: number }): Promise<SupportSessionRecord> {
    await this.maybeFailOrDelay();
    const sessionId = `sup_${String(this.nextSeq).padStart(3, '0')}`;
    const record: SupportSessionRecord = {
      sessionId,
      tenantId: input.tenantId,
      operatorActor: 'environment-root',
      ticketRef: input.ticketRef.trim(),
      reason: input.reason.trim(),
      readOnly: true,
      createdAt: MOCK_NOW,
      expiresAt: `2026-11-27T10:${String(Math.min(59, input.ttlMinutes)).padStart(2, '0')}:00Z`,
      revokedAt: null,
    };
    this.supportSessions = [record, ...this.supportSessions];
    this.appendAudit('SUPPORT_SESSION_START', input.tenantId, sessionId, `工单 ${record.ticketRef}：${record.reason}`);
    return { ...record };
  }

  async revokeSupportSession(sessionId: string, reason: string): Promise<void> {
    await this.maybeFailOrDelay();
    const target = this.supportSessions.find((s) => s.sessionId === sessionId);
    if (!target) throw new OperatorApiError('VALIDATION_ERROR', 404, `未找到支持会话：${sessionId}`);
    target.revokedAt = MOCK_NOW;
    this.appendAudit('SUPPORT_SESSION_REVOKE', target.tenantId, sessionId, reason);
  }
}

export const mockOperatorRepository = new MockOperatorRepository();
