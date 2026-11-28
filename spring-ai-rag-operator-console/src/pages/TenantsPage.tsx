import { useEffect, useMemo, useRef, useState } from 'react';
import { Plus, Search } from 'lucide-react';
import { operatorClient } from '../domain/clientFactory';
import {
  TENANT_STATUS_LABEL,
  ACCESS_PRINCIPAL_LABEL,
  COLLECTION_LABEL,
  DOCUMENT_LABEL,
  EMBEDDING_LABEL,
} from '../domain/glossary';
import type { IssuedApiKeyResult, TenantStatus, TenantSummary } from '../domain/operatorContract';
import { statusTone } from '../components/TenantDrawer';
import {
  AttentionStrip,
  Badge,
  Bar,
  Button,
  EmptyState,
  Modal,
  OneTimeKeyModal,
  OverviewBand,
  PageHeader,
  SkeletonTable,
  SortIcon,
  formatNumber,
  formatShortTime,
  type Tone,
} from '../ui/primitives';

type SortKey = 'tenantId' | 'status' | 'dayTokenUsed' | 'updatedAt';

const TENANT_ID_PATTERN = /^[a-z0-9][a-z0-9_-]{0,63}$/;

export function TenantsPage({
  refreshTick,
  onSelectTenant,
  onMutated,
  onNotify,
  onError,
}: {
  refreshTick: number;
  onSelectTenant: (tenantId: string) => void;
  onMutated: () => void;
  onNotify: (tone: Tone, text: string) => void;
  onError: (err: unknown) => void;
}) {
  const [tenants, setTenants] = useState<TenantSummary[] | null>(null);
  const [status, setStatus] = useState<TenantStatus | 'ALL'>('ALL');
  const [q, setQ] = useState('');
  const [includeDeleted, setIncludeDeleted] = useState(false);
  const [sortKey, setSortKey] = useState<SortKey>('tenantId');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');
  const [focusedIdx, setFocusedIdx] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [newId, setNewId] = useState('');
  const [newName, setNewName] = useState('');
  const [issueKey, setIssueKey] = useState(true);
  const [reason, setReason] = useState('');
  const [issuedKey, setIssuedKey] = useState<IssuedApiKeyResult | null>(null);
  const rowRefs = useRef<Array<HTMLTableRowElement | null>>([]);

  useEffect(() => {
    let active = true;
    setTenants(null);
    operatorClient
      .listTenants({ status, q, includeDeleted })
      .then((rows) => {
        if (active) {
          setTenants(rows);
          setFocusedIdx(0);
        }
      })
      .catch((err) => {
        if (active) onError(err);
      });
    return () => {
      active = false;
    };
  }, [status, q, includeDeleted, refreshTick, onError]);

  const filtered = useMemo(() => {
    if (!tenants) return [];
    const direction = sortDir === 'asc' ? 1 : -1;
    return [...tenants].sort((a, b) => {
      let left: string | number;
      let right: string | number;
      switch (sortKey) {
        case 'status':
          left = TENANT_STATUS_LABEL[a.status];
          right = TENANT_STATUS_LABEL[b.status];
          return left.localeCompare(right, 'zh-CN') * direction;
        case 'dayTokenUsed':
          left = a.dayTokenUsed;
          right = b.dayTokenUsed;
          break;
        case 'updatedAt':
          left = Date.parse(a.updatedAt);
          right = Date.parse(b.updatedAt);
          break;
        default:
          left = a.tenantId;
          right = b.tenantId;
          return left.localeCompare(right) * direction;
      }
      return ((left as number) - (right as number)) * direction;
    });
  }, [tenants, sortKey, sortDir]);

  const activeFilterCount = [status !== 'ALL', q.trim().length > 0, includeDeleted].filter(Boolean).length;
  const clearFilters = () => {
    setStatus('ALL');
    setQ('');
    setIncludeDeleted(false);
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement | null)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || createOpen || issuedKey) return;
      if (document.querySelector('[data-layer="drawer"], [role="dialog"]')) return;
      const move = (delta: number) => {
        setFocusedIdx((i) => {
          const next = Math.min(Math.max(filtered.length - 1, 0), i + delta);
          rowRefs.current[next]?.scrollIntoView({ block: 'nearest' });
          rowRefs.current[next]?.focus();
          return next;
        });
      };
      if (e.key === 'j') move(1);
      else if (e.key === 'k') move(-1);
      else if (e.key === 'Enter' && filtered[focusedIdx]) onSelectTenant(filtered[focusedIdx].tenantId);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [filtered, focusedIdx, createOpen, issuedKey, onSelectTenant]);

  const highQuotaCount = filtered.filter((t) => t.dayTokenLimit && t.dayTokenUsed / t.dayTokenLimit >= 0.8).length;
  const orphanTenantCount = filtered.filter((t) => t.orphanPrincipalCount > 0).length;
  const suspendedCount = filtered.filter((t) => t.status === 'SUSPENDED').length;
  const activeCount = filtered.filter((t) => t.status === 'ACTIVE').length;
  const attention = [
    ...(highQuotaCount > 0 ? [{
      id: 'quota', tone: 'warning' as const,
      message: `${highQuotaCount} 个租户日配额已达 80%，建议核查用量或调整限额。`,
      action: <Button size="sm" onClick={() => setSortKey('dayTokenUsed')}>按今日用量排序</Button>,
    }] : []),
    ...(orphanTenantCount > 0 ? [{
      id: 'orphan', tone: 'danger' as const,
      message: `${orphanTenantCount} 个租户存在待发密钥主体，租户接入会受阻。`,
      action: <Button size="sm" onClick={() => onSelectTenant(filtered.find((t) => t.orphanPrincipalCount > 0)!.tenantId)}>查看首个租户</Button>,
    }] : []),
    ...(suspendedCount > 0 ? [{
      id: 'suspended', tone: 'warning' as const,
      message: `${suspendedCount} 个租户已暂停，业务请求将在边缘返回拒绝。`,
      action: <Button size="sm" onClick={() => setStatus('SUSPENDED')}>只看已暂停</Button>,
    }] : []),
  ];
  const idValid = TENANT_ID_PATTERN.test(newId.trim());

  const sortHeader = (key: SortKey, label: string) => (
    <th aria-sort={sortKey === key ? (sortDir === 'asc' ? 'ascending' : 'descending') : undefined}>
      <button
        type="button"
        className="inline-flex items-center gap-1 cursor-pointer"
        onClick={() => {
          if (sortKey === key) setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
          else { setSortKey(key); setSortDir('asc'); }
        }}
      >
        {label} <SortIcon active={sortKey === key} direction={sortDir} />
      </button>
    </th>
  );

  const clearButton = activeFilterCount > 0 ? (
    <Button size="sm" variant="ghost" onClick={clearFilters}>清除筛选</Button>
  ) : undefined;

  return (
    <div>
      <PageHeader
        title="租户目录"
        description="按租户粒度管理生命周期状态、初始凭据与配额快照。点击任意行打开右侧详情抽屉（支持 j/k/Enter 键盘导航）。"
        actions={
          <Button variant="primary" onClick={() => setCreateOpen(true)} data-testid="create-tenant-btn">
            <Plus className="h-3.5 w-3.5" />
            新建租户
          </Button>
        }
      />

      <OverviewBand
        items={[
          { label: '当前结果租户', value: String(filtered.length), note: '按筛选条件统计' },
          { label: '正常服务', value: String(activeCount), note: '可正常鉴权与读写' },
          { label: '日配额 ≥ 80% 预警', value: String(highQuotaCount), note: '含已触发限流租户', tone: highQuotaCount > 0 ? 'warning' : 'neutral' },
          { label: '待发密钥主体', value: String(orphanTenantCount), note: '需补发首把 API Key', tone: orphanTenantCount > 0 ? 'danger' : 'neutral' },
        ]}
      />

      {attention.map((item) => (
        <AttentionStrip key={item.id} tone={item.tone} message={item.message} action={item.action} />
      ))}

      <div className="surface">
        <div className="filter-bar">
          <div className="relative min-w-[200px] flex-1">
            <Search className="pointer-events-none absolute top-2 left-2.5 h-3.5 w-3.5 text-[var(--muted-foreground)]" />
            <input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="搜索租户标识或租户名称…"
              className="w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--card)] py-1 pr-2.5 pl-8 text-xs"
            />
          </div>
          <select
            value={status}
            onChange={(e) => setStatus(e.target.value as TenantStatus | 'ALL')}
            className="rounded-[4px] border border-[var(--border-strong)] bg-[var(--card)] px-2.5 py-1 text-xs"
          >
            <option value="ALL">全部状态</option>
            {(Object.keys(TENANT_STATUS_LABEL) as TenantStatus[]).map((s) => (
              <option key={s} value={s}>{TENANT_STATUS_LABEL[s]}</option>
            ))}
          </select>
          <label className="flex items-center gap-1.5 text-xs text-[var(--muted-foreground)]">
            <input type="checkbox" checked={includeDeleted} onChange={(e) => setIncludeDeleted(e.target.checked)} />
            包含已软删
          </label>
          <span className="ml-auto text-xs text-[var(--muted-foreground)]">
            {activeFilterCount ? `${activeFilterCount} 个筛选 · ` : ''}{filtered.length} 条结果
          </span>
          {clearButton}
        </div>

        {tenants === null ? (
          <SkeletonTable rows={6} columns={6} />
        ) : filtered.length === 0 ? (
          <EmptyState title="没有匹配的租户" detail="调整筛选条件，或清除全部筛选后重试。" action={clearButton} />
        ) : (
          <>
            <div className="hidden md:block overflow-x-auto">
              <table className="data-table text-xs">
                <thead>
                  <tr>
                    {sortHeader('tenantId', '租户标识 / 名称')}
                    {sortHeader('status', '状态')}
                    <th>凭据（有效 / 待发）</th>
                    {sortHeader('dayTokenUsed', '今日 Token 配额')}
                    <th>知识库 / 文档 / 向量分块</th>
                    {sortHeader('updatedAt', '更新时间')}
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((t, idx) => {
                    const ratio = t.dayTokenLimit ? t.dayTokenUsed / t.dayTokenLimit : 0;
                    return (
                      <tr
                        key={t.tenantId}
                        ref={(el) => { rowRefs.current[idx] = el; }}
                        tabIndex={0}
                        data-testid={`tenant-row-${t.tenantId}`}
                        data-row={idx}
                        onClick={() => onSelectTenant(t.tenantId)}
                        onFocus={() => setFocusedIdx(idx)}
                        className={`cursor-pointer ${idx === focusedIdx ? 'bg-[var(--primary-soft)]/45' : ''}`}
                      >
                        <td>
                          <div className="font-mono font-semibold text-[var(--foreground)]">{t.tenantId}</div>
                          <div className="mt-0.5 text-[11px] text-[var(--muted-foreground)]">
                            {t.displayName} {t.protectedDefault ? '· 系统保留' : ''}
                          </div>
                        </td>
                        <td><Badge tone={statusTone(t.status)}>{TENANT_STATUS_LABEL[t.status]}</Badge></td>
                        <td className="font-mono tabular-nums">
                          <span>{t.activeKeyCount}</span>
                          {t.orphanPrincipalCount > 0 && (
                            <span className="ml-1.5 text-[var(--destructive)]">/ {t.orphanPrincipalCount} 待发</span>
                          )}
                        </td>
                        <td className="min-w-[160px]">
                          <div className="font-mono text-[11px] tabular-nums">
                            {formatNumber(t.dayTokenUsed)} / {formatNumber(t.dayTokenLimit)}
                          </div>
                          {t.dayTokenLimit && <div className="mt-1"><Bar ratio={ratio} /></div>}
                        </td>
                        <td className="font-mono text-[11px] tabular-nums text-[var(--muted-foreground)]">
                          {t.collectionCount} / {formatNumber(t.documentCount)} / {formatNumber(t.embeddingCount)}
                        </td>
                        <td className="font-mono text-[11px] text-[var(--muted-foreground)]">
                          {formatShortTime(t.updatedAt)}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <div className="divide-y divide-[var(--border)] md:hidden">
              {filtered.map((t) => (
                <div
                  key={t.tenantId}
                  onClick={() => onSelectTenant(t.tenantId)}
                  className="p-3.5 text-xs hover:bg-[var(--muted)]/40 cursor-pointer"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono font-semibold">{t.tenantId}</span>
                    <Badge tone={statusTone(t.status)}>{TENANT_STATUS_LABEL[t.status]}</Badge>
                  </div>
                  <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-[11px] tabular-nums">
                    <dt className="text-[var(--muted-foreground)]">{ACCESS_PRINCIPAL_LABEL}</dt>
                    <dd className="font-mono text-right">{t.principalCount}</dd>
                    <dt className="text-[var(--muted-foreground)]">有效密钥 / 待发</dt>
                    <dd className="font-mono text-right">{t.activeKeyCount} / {t.orphanPrincipalCount}</dd>
                    <dt className="text-[var(--muted-foreground)]">今日已用 / 上限</dt>
                    <dd className="font-mono text-right">{formatNumber(t.dayTokenUsed)} / {formatNumber(t.dayTokenLimit)}</dd>
                    <dt className="text-[var(--muted-foreground)]">{COLLECTION_LABEL} / {DOCUMENT_LABEL} / {EMBEDDING_LABEL}</dt>
                    <dd className="font-mono text-right">{t.collectionCount} / {formatNumber(t.documentCount)} / {formatNumber(t.embeddingCount)}</dd>
                    <dt className="text-[var(--muted-foreground)]">更新时间</dt>
                    <dd className="font-mono text-right">{formatShortTime(t.updatedAt)}</dd>
                  </dl>
                </div>
              ))}
            </div>
          </>
        )}
      </div>

      <Modal
        open={createOpen}
        title="新建租户"
        description="单事务原子创建租户记录与初始访问主体；建议同时签发首把 API Key。"
        onClose={() => setCreateOpen(false)}
        footer={
          <>
            <Button onClick={() => setCreateOpen(false)}>取消</Button>
            <Button
              variant="primary"
              data-testid="submit-create-tenant"
              disabled={!idValid || !reason.trim()}
              onClick={async () => {
                try {
                  const res = await operatorClient.createTenant({
                    tenantId: newId.trim().toLowerCase(),
                    displayName: newName || newId,
                    issueBootstrapKey: issueKey,
                    reason,
                  });
                  setCreateOpen(false);
                  setNewId('');
                  setNewName('');
                  setReason('');
                  onNotify('success', `已创建租户 ${res.tenant.tenantId}`);
                  onMutated();
                  if (res.bootstrapKey) setIssuedKey(res.bootstrapKey);
                } catch (err) {
                  onNotify('danger', err instanceof Error ? err.message : '创建租户失败');
                }
              }}
            >
              确认创建
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <div>
            <label className="block font-medium">租户标识（小写字母 / 数字 / 连字符）</label>
            <input
              data-testid="new-tenant-id"
              value={newId}
              onChange={(e) => setNewId(e.target.value)}
              placeholder="例如：vertex-bio"
              className="mt-1 w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1.5 font-mono text-xs"
            />
            {newId.trim().length > 0 && !idValid && (
              <p data-testid="new-tenant-id-error" className="mt-1 text-[11px] text-[var(--destructive)]">
                租户标识须以小写字母或数字开头，仅包含小写字母、数字、连字符或下划线，长度 1–64。
              </p>
            )}
          </div>
          <div>
            <label className="block font-medium">展示名称</label>
            <input
              data-testid="new-tenant-name"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              placeholder="例如：Vertex 生物医药知识库"
              className="mt-1 w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1.5 text-xs"
            />
          </div>
          <label className="flex items-center gap-2">
            <input type="checkbox" checked={issueKey} onChange={(e) => setIssueKey(e.target.checked)} />
            开户同时签发首把 API Key（一次性明文返回）
          </label>
          <div>
            <label className="block font-medium">开户原因（写入平台审计）</label>
            <input
              data-testid="new-tenant-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="例如：新客户签约开户"
              className="mt-1 w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1.5 text-xs"
            />
          </div>
        </div>
      </Modal>

      <OneTimeKeyModal issued={issuedKey} onClose={() => setIssuedKey(null)} />
    </div>
  );
}
