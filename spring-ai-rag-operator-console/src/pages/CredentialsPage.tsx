import { useEffect, useState } from 'react';
import { KeyRound } from 'lucide-react';
import { operatorClient } from '../domain/clientFactory';
import {
  CREDENTIAL_STATUS_LABEL,
  ACCESS_PRINCIPAL_LABEL,
  PENDING_KEY_PRINCIPAL_LABEL,
} from '../domain/glossary';
import type { CredentialRecord, CredentialStatus, IssuedApiKeyResult, TenantSummary } from '../domain/operatorContract';
import {
  AttentionStrip,
  Badge,
  Button,
  ConfirmModal,
  EmptyState,
  Modal,
  OneTimeKeyModal,
  PageHeader,
  SkeletonTable,
  formatShortTime,
  type Tone,
} from '../ui/primitives';

function credTone(s: CredentialStatus): Tone {
  if (s === 'ACTIVE') return 'success';
  if (s === 'ROTATING' || s === 'ORPHAN_NO_KEY') return 'warning';
  return 'danger';
}

export function CredentialsPage({
  refreshTick,
  tenants,
  onMutated,
  onNotify,
  onError,
}: {
  refreshTick: number;
  tenants: TenantSummary[];
  onMutated: () => void;
  onNotify: (tone: Tone, text: string) => void;
  onError: (err: unknown) => void;
}) {
  const [rows, setRows] = useState<CredentialRecord[] | null>(null);
  const [tenantFilter, setTenantFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState<CredentialStatus | 'ALL'>('ALL');
  const [orphanOnly, setOrphanOnly] = useState(false);
  const [issueTarget, setIssueTarget] = useState<{ tenantId: string; principalId?: string } | null>(null);
  const [issueLabel, setIssueLabel] = useState('');
  const [issueReason, setIssueReason] = useState('');
  const [issuedKey, setIssuedKey] = useState<IssuedApiKeyResult | null>(null);
  const [revokeKeyId, setRevokeKeyId] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setRows(null);
    operatorClient
      .listCredentials({ tenantId: tenantFilter || undefined, status: statusFilter, orphanOnly })
      .then((data) => { if (active) setRows(data); })
      .catch((err) => { if (active) onError(err); });
    return () => { active = false; };
  }, [tenantFilter, statusFilter, orphanOnly, refreshTick]);

  const orphanCount = (rows ?? []).filter((r) => r.status === 'ORPHAN_NO_KEY').length;
  const activeFilterCount = [Boolean(tenantFilter), statusFilter !== 'ALL', orphanOnly].filter(Boolean).length;
  const clearFilters = () => { setTenantFilter(''); setStatusFilter('ALL'); setOrphanOnly(false); };
  const activeTenants = tenants.filter((t) => t.status === 'ACTIVE');

  return (
    <div>
      <PageHeader
        title="凭据治理"
        description={`按租户管理${ACCESS_PRINCIPAL_LABEL}与 API Key 元数据（绝不回显哈希或明文）。支持为新租户或${PENDING_KEY_PRINCIPAL_LABEL}补发首把密钥，以及受审计吊销。`}
        actions={
          <Button
            variant="primary"
            onClick={() => {
              setIssueTarget({ tenantId: activeTenants[0]?.tenantId ?? 'default' });
              setIssueLabel('');
              setIssueReason('');
            }}
          >
            <KeyRound className="h-3.5 w-3.5" />
            为租户签发 API Key
          </Button>
        }
      />

      {orphanCount > 0 && !orphanOnly && (
        <AttentionStrip
          tone="warning"
          message={`发现 ${orphanCount} 个尚未签发 API Key 的${PENDING_KEY_PRINCIPAL_LABEL}（如新开户未发首把密钥），请及时补发以打通租户接入。`}
          action={
            <Button size="sm" onClick={() => setOrphanOnly(true)}>
              一键筛选待发密钥主体
            </Button>
          }
        />
      )}

      <div className="surface">
        <div className="filter-bar">
          <select
            value={tenantFilter}
            onChange={(e) => setTenantFilter(e.target.value)}
            className="rounded-[4px] border border-[var(--border-strong)] bg-[var(--card)] px-2.5 py-1 text-xs font-mono"
          >
            <option value="">全部租户</option>
            {tenants.map((t) => (
              <option key={t.tenantId} value={t.tenantId}>{t.tenantId}</option>
            ))}
          </select>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as CredentialStatus | 'ALL')}
            className="rounded-[4px] border border-[var(--border-strong)] bg-[var(--card)] px-2.5 py-1 text-xs"
          >
            <option value="ALL">全部状态</option>
            {(Object.keys(CREDENTIAL_STATUS_LABEL) as CredentialStatus[]).map((s) => (
              <option key={s} value={s}>{CREDENTIAL_STATUS_LABEL[s]}</option>
            ))}
          </select>
          <label className="flex items-center gap-1.5 text-xs text-[var(--muted-foreground)]">
            <input type="checkbox" checked={orphanOnly} onChange={(e) => setOrphanOnly(e.target.checked)} />
            仅看待发密钥主体
          </label>
          <span className="ml-auto text-xs text-[var(--muted-foreground)]">
            {activeFilterCount ? `${activeFilterCount} 个筛选 · ` : ''}{rows?.length ?? 0} 条结果
          </span>
          {activeFilterCount > 0 && <Button size="sm" variant="ghost" onClick={clearFilters}>清除筛选</Button>}
        </div>

        {rows === null ? <SkeletonTable rows={6} columns={6} /> : rows.length === 0 ? (
          <EmptyState
            title="没有匹配的凭据"
            detail="调整筛选条件，或清除全部筛选后重试。"
            action={activeFilterCount > 0 ? <Button size="sm" onClick={clearFilters}>清除筛选</Button> : undefined}
          />
        ) : (
        <>
        <div className="hidden md:block overflow-x-auto">
          <table className="data-table text-xs">
            <thead>
              <tr>
                <th>所属租户</th>
                <th>访问主体标识</th>
                <th>Key 前缀</th>
                <th>状态</th>
                <th>到期 / 最近使用</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.principalId}>
                  <td className="font-mono font-medium">{c.tenantId}</td>
                  <td className="font-mono text-[11px]">{c.principalId}</td>
                  <td className="font-mono text-[11px] text-[var(--muted-foreground)]">{c.keyPrefix ?? '（未签发）'}</td>
                  <td><Badge tone={credTone(c.status)}>{CREDENTIAL_STATUS_LABEL[c.status]}</Badge></td>
                  <td className="font-mono text-[11px] text-[var(--muted-foreground)]">
                    <div>到期：{formatShortTime(c.expiresAt)}</div>
                    <div>最近：{formatShortTime(c.lastUsedAt)}</div>
                  </td>
                  <td>
                    <div className="flex gap-1.5">
                      {c.status === 'ORPHAN_NO_KEY' && (
                        <Button
                          size="sm"
                          variant="primary"
                          onClick={() => {
                            setIssueTarget({ tenantId: c.tenantId, principalId: c.principalId });
                            setIssueLabel('bootstrap-key');
                            setIssueReason(`为${PENDING_KEY_PRINCIPAL_LABEL}补发首把 API Key`);
                          }}
                        >
                          补发首把 Key
                        </Button>
                      )}
                      {c.keyId && c.status !== 'REVOKED' && (
                        <Button size="sm" variant="danger" onClick={() => setRevokeKeyId(c.keyId)}>
                          吊销
                        </Button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="divide-y divide-[var(--border)] md:hidden">
          {rows.map((c) => (
            <div key={c.principalId} className="p-3.5 text-xs space-y-1.5">
              <div className="flex items-center justify-between">
                <span className="font-mono font-semibold">{c.tenantId}</span>
                <Badge tone={credTone(c.status)}>{CREDENTIAL_STATUS_LABEL[c.status]}</Badge>
              </div>
              <dl className="grid grid-cols-2 gap-x-3 gap-y-1 text-[11px] tabular-nums">
                <dt className="text-[var(--muted-foreground)]">访问主体标识</dt>
                <dd className="font-mono text-right">{c.principalId}</dd>
                <dt className="text-[var(--muted-foreground)]">Key 前缀</dt>
                <dd className="font-mono text-right">{c.keyPrefix ?? '（未签发）'}</dd>
                <dt className="text-[var(--muted-foreground)]">到期时间</dt>
                <dd className="font-mono text-right">{formatShortTime(c.expiresAt)}</dd>
                <dt className="text-[var(--muted-foreground)]">最近使用</dt>
                <dd className="font-mono text-right">{formatShortTime(c.lastUsedAt)}</dd>
              </dl>
              <div className="flex gap-2 pt-1">
                {c.status === 'ORPHAN_NO_KEY' && (
                  <Button
                    size="sm"
                    variant="primary"
                    onClick={() => {
                      setIssueTarget({ tenantId: c.tenantId, principalId: c.principalId });
                      setIssueLabel('bootstrap-key');
                      setIssueReason('补发首把 API Key');
                    }}
                  >
                    补发首把 Key
                  </Button>
                )}
                {c.keyId && c.status !== 'REVOKED' && (
                  <Button size="sm" variant="danger" onClick={() => setRevokeKeyId(c.keyId)}>吊销</Button>
                )}
              </div>
            </div>
          ))}
        </div>
        </>
        )}
      </div>

      <Modal
        open={issueTarget !== null}
        title="签发租户 API Key"
        onClose={() => setIssueTarget(null)}
        footer={
          <>
            <Button onClick={() => setIssueTarget(null)}>取消</Button>
            <Button
              variant="primary"
              disabled={!issueReason.trim()}
              onClick={async () => {
                if (!issueTarget) return;
                try {
                  const res = await operatorClient.issueTenantApiKey(issueTarget.tenantId, {
                    principalId: issueTarget.principalId,
                    label: issueLabel || 'tenant-key',
                    reason: issueReason,
                  });
                  setIssueTarget(null);
                  setIssuedKey(res);
                  onNotify('success', `已为 ${issueTarget.tenantId} 签发 API Key`);
                  onMutated();
                } catch (err) {
                  onNotify('danger', err instanceof Error ? err.message : '签发失败');
                }
              }}
            >
              确认签发
            </Button>
          </>
        }
      >
        {issueTarget && (
            <div className="mt-3 space-y-3">
              <div>
                <label className="block font-medium">目标正常租户</label>
                <select
                  value={issueTarget.tenantId}
                  disabled={Boolean(issueTarget.principalId)}
                  onChange={(e) => setIssueTarget({ ...issueTarget, tenantId: e.target.value })}
                  className="mt-1 w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1.5 font-mono text-xs"
                >
                  {activeTenants.map((t) => (
                    <option key={t.tenantId} value={t.tenantId}>{t.tenantId} ({t.displayName})</option>
                  ))}
                </select>
              </div>
              {issueTarget.principalId && (
                <div className="font-mono text-[11px] text-[var(--muted-foreground)]">
                  绑定已有待发密钥主体：{issueTarget.principalId}
                </div>
              )}
              <div>
                <label className="block font-medium">密钥标签</label>
                <input
                  value={issueLabel}
                  onChange={(e) => setIssueLabel(e.target.value)}
                  placeholder="例如：prod-ingestion-key"
                  className="mt-1 w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1.5 text-xs"
                />
              </div>
              <div>
                <label className="block font-medium">签发原因（写入平台审计）</label>
                <input
                  value={issueReason}
                  onChange={(e) => setIssueReason(e.target.value)}
                  placeholder="例如：客户接入新业务线"
                  className="mt-1 w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1.5 text-xs"
                />
              </div>
            </div>
        )}
      </Modal>

      <ConfirmModal
        open={revokeKeyId !== null}
        title="确认吊销 API Key"
        description="吊销后该密钥立即失效且不可恢复，并写入平台审计流水。"
        confirmTarget={revokeKeyId ?? undefined}
        confirmLabel="立即吊销"
        onCancel={() => setRevokeKeyId(null)}
        onConfirm={async (reason) => {
          if (!revokeKeyId) return;
          await operatorClient.revokeApiKey(revokeKeyId, reason);
          setRevokeKeyId(null);
          onNotify('success', `已吊销 ${revokeKeyId}`);
          onMutated();
        }}
      />

      <OneTimeKeyModal issued={issuedKey} onClose={() => setIssuedKey(null)} />
    </div>
  );
}
