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

const BASE = '/api/v1/rag/operator';

function mapErrorCode(raw: string | undefined, status: number): OperatorErrorCode {
  if (raw === 'DEFAULT_TENANT_PROTECTED') return 'DEFAULT_TENANT_PROTECTED';
  if (raw === 'CSRF_REQUIRED') return 'CSRF_REQUIRED';
  if (raw === 'TENANT_CONFLICT') return 'TENANT_CONFLICT';
  if (status === 401) return 'UNAUTHORIZED';
  if (status === 403) return 'FORBIDDEN';
  if (status === 409) return 'TENANT_CONFLICT';
  return 'VALIDATION_ERROR';
}

export class HttpOperatorClient implements OperatorClient {
  readonly mode = 'real' as const;

  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    const headers = new Headers(init?.headers);
    headers.set('Accept', 'application/json');
    if (init?.body && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    const response = await fetch(`${BASE}${path}`, {
      ...init,
      credentials: 'include',
      cache: 'no-store',
      headers,
    });
    if (!response.ok) {
      let body: { error?: string; message?: string; detail?: string } = {};
      try {
        body = (await response.json()) as typeof body;
      } catch {
        // non-JSON error
      }
      throw new OperatorApiError(
        mapErrorCode(body.error, response.status),
        response.status,
        body.message || body.detail || `HTTP ${response.status}`,
      );
    }
    if (response.status === 204) {
      return undefined as T;
    }
    return (await response.json()) as T;
  }

  listTenants(params?: { status?: TenantStatus | 'ALL'; q?: string; includeDeleted?: boolean }): Promise<TenantSummary[]> {
    const sp = new URLSearchParams();
    if (params?.status && params.status !== 'ALL') sp.set('status', params.status);
    if (params?.q) sp.set('q', params.q);
    if (params?.includeDeleted) sp.set('includeDeleted', 'true');
    const qs = sp.toString();
    return this.request<TenantSummary[]>(`/tenants${qs ? `?${qs}` : ''}`);
  }

  getTenantDetail(tenantId: string): Promise<TenantDetail> {
    return this.request<TenantDetail>(`/tenants/${encodeURIComponent(tenantId)}`);
  }

  createTenant(input: { tenantId: string; displayName: string; issueBootstrapKey: boolean; reason: string }) {
    return this.request<{ tenant: TenantSummary; bootstrapKey?: IssuedApiKeyResult }>('/tenants', {
      method: 'POST',
      body: JSON.stringify(input),
    });
  }

  updateTenant(tenantId: string, input: { displayName: string; reason: string }): Promise<TenantSummary> {
    return this.request<TenantSummary>(`/tenants/${encodeURIComponent(tenantId)}`, {
      method: 'PATCH',
      body: JSON.stringify(input),
    });
  }

  transitionTenant(tenantId: string, action: 'suspend' | 'resume' | 'disable' | 'soft_delete', reason: string): Promise<TenantSummary> {
    if (action === 'soft_delete') {
      return this.request<TenantSummary>(`/tenants/${encodeURIComponent(tenantId)}`, {
        method: 'DELETE',
        body: JSON.stringify({ reason }),
      });
    }
    return this.request<TenantSummary>(`/tenants/${encodeURIComponent(tenantId)}/${action}`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    });
  }

  listCredentials(params?: { tenantId?: string; status?: CredentialStatus | 'ALL'; orphanOnly?: boolean }): Promise<CredentialRecord[]> {
    const sp = new URLSearchParams();
    if (params?.tenantId) sp.set('tenantId', params.tenantId);
    if (params?.status && params.status !== 'ALL') sp.set('status', params.status);
    if (params?.orphanOnly) sp.set('orphanOnly', 'true');
    const qs = sp.toString();
    return this.request<CredentialRecord[]>(`/credentials${qs ? `?${qs}` : ''}`);
  }

  issueTenantApiKey(tenantId: string, input: { principalId?: string; label: string; reason: string }): Promise<IssuedApiKeyResult> {
    return this.request<IssuedApiKeyResult>(`/tenants/${encodeURIComponent(tenantId)}/api-keys`, {
      method: 'POST',
      body: JSON.stringify(input),
    });
  }

  revokeApiKey(keyId: string, reason: string): Promise<void> {
    return this.request<void>(`/api-keys/${encodeURIComponent(keyId)}`, {
      method: 'DELETE',
      body: JSON.stringify({ reason }),
    });
  }

  listQuotas(params?: { period?: QuotaPeriod }): Promise<QuotaPolicyRecord[]> {
    const qs = params?.period ? `?period=${params.period}` : '';
    return this.request<QuotaPolicyRecord[]>(`/quotas${qs}`);
  }

  updateQuota(tenantId: string, period: QuotaPeriod, input: { enabled: boolean; maxTokens: number | null; maxCostUsd: number | null; reason: string }): Promise<QuotaPolicyRecord> {
    return this.request<QuotaPolicyRecord>(`/tenants/${encodeURIComponent(tenantId)}/quotas/${period}`, {
      method: 'PUT',
      body: JSON.stringify(input),
    });
  }

  listKnowledgeHealth(): Promise<KnowledgeHealthRow[]> {
    return this.request<KnowledgeHealthRow[]>('/knowledge-health');
  }

  getComplianceSnapshot(): Promise<ComplianceSnapshot> {
    return this.request<ComplianceSnapshot>('/compliance');
  }

  listAuditEvents(params?: { tenantId?: string; action?: string }): Promise<PlatformAuditEvent[]> {
    const sp = new URLSearchParams();
    if (params?.tenantId) sp.set('tenantId', params.tenantId);
    if (params?.action) sp.set('action', params.action);
    const qs = sp.toString();
    return this.request<PlatformAuditEvent[]>(`/audit-events${qs ? `?${qs}` : ''}`);
  }

  listSupportSessions(): Promise<SupportSessionRecord[]> {
    return this.request<SupportSessionRecord[]>('/support-sessions');
  }

  startSupportSession(input: { tenantId: string; ticketRef: string; reason: string; ttlMinutes: number }): Promise<SupportSessionRecord> {
    return this.request<SupportSessionRecord>('/support-sessions', {
      method: 'POST',
      body: JSON.stringify(input),
    });
  }

  revokeSupportSession(sessionId: string, reason: string): Promise<void> {
    return this.request<void>(`/support-sessions/${encodeURIComponent(sessionId)}/revoke`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    });
  }
}
