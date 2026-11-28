import { useEffect, useMemo, useState } from 'react';
import { operatorClient } from '../domain/clientFactory';
import { QUOTA_SWITCH_LABEL } from '../domain/glossary';
import type { QuotaPeriod, QuotaPolicyRecord } from '../domain/operatorContract';
import {
  Badge,
  Bar,
  Button,
  EmptyState,
  Modal,
  OverviewBand,
  PageHeader,
  SkeletonTable,
  SortIcon,
  formatNumber,
  formatShortTime,
  type Tone,
} from '../ui/primitives';

type SortKey = 'tenantId' | 'usedTokens' | 'reservedTokens' | 'windowEnd';

export function QuotasPage({
  refreshTick,
  onMutated,
  onNotify,
  onError,
}: {
  refreshTick: number;
  onMutated: () => void;
  onNotify: (tone: Tone, text: string) => void;
  onError: (err: unknown) => void;
}) {
  const [period, setPeriod] = useState<QuotaPeriod>('DAY');
  const [rows, setRows] = useState<QuotaPolicyRecord[] | null>(null);
  const [q, setQ] = useState('');
  const [sortKey, setSortKey] = useState<SortKey>('usedTokens');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');
  const [editing, setEditing] = useState<QuotaPolicyRecord | null>(null);
  const [enabled, setEnabled] = useState(true);
  const [maxTokensStr, setMaxTokensStr] = useState('');
  const [reason, setReason] = useState('');

  useEffect(() => {
    let active = true;
    setRows(null);
    operatorClient
      .listQuotas({ period })
      .then((data) => { if (active) setRows(data); })
      .catch((err) => { if (active) onError(err); });
    return () => { active = false; };
  }, [period, refreshTick, onError]);

  const filtered = useMemo(() => {
    if (!rows) return [];
    const needle = q.trim().toLowerCase();
    const result = rows.filter((r) =>
      !needle || r.tenantId.toLowerCase().includes(needle) || r.displayName.toLowerCase().includes(needle));
    const direction = sortDir === 'asc' ? 1 : -1;
    return [...result].sort((a, b) => {
      let left: string | number;
      let right: string | number;
      switch (sortKey) {
        case 'usedTokens': left = a.usedTokens; right = b.usedTokens; break;
        case 'reservedTokens': left = a.reservedTokens; right = b.reservedTokens; break;
        case 'windowEnd': left = Date.parse(a.windowEnd); right = Date.parse(b.windowEnd); break;
        default: return a.tenantId.localeCompare(b.tenantId) * direction;
      }
      return ((left as number) - (right as number)) * direction;
    });
  }, [rows, q, sortKey, sortDir]);

  const activeFilterCount = q.trim().length > 0 ? 1 : 0;
  const clearFilters = () => setQ('');
  const totalUsed = filtered.reduce((acc, r) => acc + r.usedTokens, 0);
  const totalReserved = filtered.reduce((acc, r) => acc + r.reservedTokens, 0);
  const warnCount = filtered.filter((r) => r.enabled && r.maxTokens && r.usedTokens / r.maxTokens >= 0.8 && r.usedTokens / r.maxTokens < 1).length;
  const exceededCount = filtered.filter((r) => r.enabled && r.maxTokens && r.usedTokens >= r.maxTokens).length;

  const sortHeader = (key: SortKey, label: string) => (
    <th aria-sort={sortKey === key ? (sortDir === 'asc' ? 'ascending' : 'descending') : undefined}>
      <button
        type="button"
        className="inline-flex items-center gap-1 cursor-pointer"
        onClick={() => {
          if (sortKey === key) setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
          else { setSortKey(key); setSortDir('desc'); }
        }}
      >
        {label} <SortIcon active={sortKey === key} direction={sortDir} />
      </button>
    </th>
  );

  return (
    <div>
      <PageHeader
        title="配额与用量"
        description="按租户配置日 / 月 Token 硬阈值并查看窗口用量排行。严格仅做配额与用量数字治理，不包含发票、支付或账单计费。"
        actions={
          <div className="inline-flex rounded-[4px] border border-[var(--border-strong)] bg-[var(--card)] p-0.5 text-xs">
            {(['DAY', 'MONTH'] as const).map((p) => (
              <button
                key={p}
                onClick={() => setPeriod(p)}
                className={`rounded-[3px] px-3 py-1 cursor-pointer ${period === p ? 'bg-[var(--primary)] text-[var(--primary-foreground)]' : 'text-[var(--muted-foreground)]'}`}
              >
                {p === 'DAY' ? '每日' : '每月'}
              </button>
            ))}
          </div>
        }
      />

      <OverviewBand
        items={[
          { label: `${period === 'DAY' ? '每日' : '每月'}窗口全平台已用`, value: formatNumber(totalUsed), note: '已结算 Tokens' },
          { label: `${period === 'DAY' ? '每日' : '每月'}窗口进行中预占`, value: formatNumber(totalReserved), note: QUOTA_SWITCH_LABEL.reserved },
          { label: '高水位预警（≥80%）', value: String(warnCount), note: '建议关注或扩容', tone: warnCount > 0 ? 'warning' : 'neutral' },
          { label: '已触发硬阈值（≥100%）', value: String(exceededCount), note: '超限请求返回 429', tone: exceededCount > 0 ? 'danger' : 'neutral' },
        ]}
      />

      <div className="surface">
        <div className="filter-bar">
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="搜索租户标识或名称…"
            className="min-w-[200px] flex-1 rounded-[4px] border border-[var(--border-strong)] bg-[var(--card)] px-2.5 py-1 text-xs"
          />
          <span className="ml-auto text-xs text-[var(--muted-foreground)]">
            {activeFilterCount ? `${activeFilterCount} 个筛选 · ` : ''}{filtered.length} 条结果
          </span>
          {activeFilterCount > 0 && <Button size="sm" variant="ghost" onClick={clearFilters}>清除筛选</Button>}
        </div>

        {rows === null ? <SkeletonTable rows={6} columns={6} /> : filtered.length === 0 ? (
          <EmptyState title="没有匹配的配额策略" detail="调整筛选条件，或清除全部筛选后重试。" action={activeFilterCount > 0 ? <Button size="sm" onClick={clearFilters}>清除筛选</Button> : undefined} />
        ) : (
          <>
            <div className="hidden md:block overflow-x-auto">
              <table className="data-table text-xs">
                <thead>
                  <tr>
                    {sortHeader('tenantId', '租户')}
                    <th>限额状态</th>
                    {sortHeader('usedTokens', '已用 / 上限（Tokens）')}
                    {sortHeader('reservedTokens', '进行中预占')}
                    {sortHeader('windowEnd', '窗口截止')}
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((r) => {
                    const ratio = r.enabled && r.maxTokens ? r.usedTokens / r.maxTokens : 0;
                    return (
                      <tr key={`${r.tenantId}-${r.period}`}>
                        <td>
                          <div className="font-mono font-semibold">{r.tenantId}</div>
                          <div className="text-[11px] text-[var(--muted-foreground)]">{r.displayName}</div>
                        </td>
                        <td>
                          <Badge tone={r.enabled ? 'primary' : 'neutral'}>{r.enabled ? QUOTA_SWITCH_LABEL.enabled : QUOTA_SWITCH_LABEL.unlimited}</Badge>
                        </td>
                        <td className="min-w-[200px]">
                          <div className="flex justify-between font-mono text-[11px] tabular-nums">
                            <span>{formatNumber(r.usedTokens)} / {formatNumber(r.maxTokens)}</span>
                            {r.enabled && r.maxTokens && <span>{Math.round(ratio * 100)}%</span>}
                          </div>
                          {r.enabled && r.maxTokens && <div className="mt-1"><Bar ratio={ratio} /></div>}
                        </td>
                        <td className="font-mono text-[11px] tabular-nums text-[var(--muted-foreground)]">{formatNumber(r.reservedTokens)}</td>
                        <td className="font-mono text-[11px] text-[var(--muted-foreground)]">{formatShortTime(r.windowEnd)}</td>
                        <td>
                          <Button
                            size="sm"
                            data-testid={`edit-quota-${r.tenantId}`}
                            onClick={() => { setEditing(r); setEnabled(r.enabled); setMaxTokensStr(r.maxTokens ? String(r.maxTokens) : ''); setReason(''); }}
                          >
                            调整配额
                          </Button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <div className="divide-y divide-[var(--border)] md:hidden">
              {filtered.map((r) => {
                const ratio = r.enabled && r.maxTokens ? r.usedTokens / r.maxTokens : 0;
                return (
                  <div key={`${r.tenantId}-${r.period}`} className="p-3.5 text-xs space-y-2">
                    <div className="flex items-center justify-between">
                      <span className="font-mono font-semibold">{r.tenantId}</span>
                      <Badge tone={r.enabled ? 'primary' : 'neutral'}>{r.enabled ? QUOTA_SWITCH_LABEL.enabled : QUOTA_SWITCH_LABEL.unlimited}</Badge>
                    </div>
                    <dl className="grid grid-cols-2 gap-x-3 gap-y-1 text-[11px] tabular-nums">
                      <dt className="text-[var(--muted-foreground)]">已用 / 上限</dt>
                      <dd className="font-mono text-right">{formatNumber(r.usedTokens)} / {formatNumber(r.maxTokens)}</dd>
                      <dt className="text-[var(--muted-foreground)]">进行中预占</dt>
                      <dd className="font-mono text-right">{formatNumber(r.reservedTokens)}</dd>
                      <dt className="text-[var(--muted-foreground)]">窗口截止</dt>
                      <dd className="font-mono text-right">{formatShortTime(r.windowEnd)}</dd>
                    </dl>
                    {r.enabled && r.maxTokens && <Bar ratio={ratio} />}
                    <Button size="sm" data-testid={`edit-quota-${r.tenantId}`} onClick={() => { setEditing(r); setEnabled(r.enabled); setMaxTokensStr(r.maxTokens ? String(r.maxTokens) : ''); setReason(''); }}>调整配额</Button>
                  </div>
                );
              })}
            </div>
          </>
        )}
      </div>

      <Modal
        open={editing !== null}
        title={editing ? `调整 ${editing.tenantId}（${editing.period === 'DAY' ? '每日' : '每月'}配额）` : '调整配额'}
        onClose={() => setEditing(null)}
        footer={
          <>
            <Button onClick={() => setEditing(null)}>取消</Button>
            <Button
              variant="primary"
              data-testid="submit-quota-update"
              disabled={!reason.trim()}
              onClick={async () => {
                if (!editing) return;
                const parsed = maxTokensStr.trim() ? Number(maxTokensStr) : null;
                await operatorClient.updateQuota(editing.tenantId, editing.period, {
                  enabled, maxTokens: parsed, maxCostUsd: editing.maxCostUsd, reason,
                });
                setEditing(null);
                onNotify('success', `已更新 ${editing.tenantId} 的配额策略`);
                onMutated();
              }}
            >
              保存策略
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <label className="flex items-center gap-2">
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
            启用窗口硬阈值拦截
          </label>
          <div>
            <label className="block font-medium">Token 上限（留空表示不限）</label>
            <input
              data-testid="quota-max-tokens-input"
              type="number"
              min={0}
              value={maxTokensStr}
              onChange={(e) => setMaxTokensStr(e.target.value)}
              className="mt-1 w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1.5 font-mono text-xs"
            />
          </div>
          <div>
            <label className="block font-medium">变更原因（写入平台审计）</label>
            <input
              data-testid="quota-reason-input"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="例如：客户集中导入期临时扩容"
              className="mt-1 w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1.5 text-xs"
            />
          </div>
        </div>
      </Modal>
    </div>
  );
}
