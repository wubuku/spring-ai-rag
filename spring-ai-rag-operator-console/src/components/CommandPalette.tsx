import { useEffect, useState } from 'react';
import { Search } from 'lucide-react';
import type { TenantSummary } from '../domain/operatorContract';

export type NavRoute = 'tenants' | 'credentials' | 'quotas' | 'knowledge-health' | 'compliance' | 'audit-support';

const ROUTE_ITEMS: Array<{ id: NavRoute; label: string; hint: string }> = [
  { id: 'tenants', label: '租户目录', hint: '生命周期状态与开户' },
  { id: 'credentials', label: '凭据治理', hint: '租户 API Key 与待发密钥主体' },
  { id: 'quotas', label: '配额与用量', hint: 'DAY/MONTH Token 与费用硬阈值' },
  { id: 'knowledge-health', label: '知识库健康', hint: '聚合规模、异步队列与 HNSW 诊断' },
  { id: 'compliance', label: '模式与合规', hint: 'STRICT/RLS/表目录与路由自检' },
  { id: 'audit-support', label: '审计与支持', hint: '平台持久化审计与只读支持会话' },
];

export function CommandPalette({
  open,
  tenants,
  onClose,
  onSelectRoute,
  onSelectTenant,
}: {
  open: boolean;
  tenants: TenantSummary[];
  onClose: () => void;
  onSelectRoute: (route: NavRoute) => void;
  onSelectTenant: (tenantId: string) => void;
}) {
  const [query, setQuery] = useState('');

  useEffect(() => {
    if (open) setQuery('');
  }, [open]);

  if (!open) return null;
  const q = query.trim().toLowerCase();
  const routes = ROUTE_ITEMS.filter((r) => !q || r.label.toLowerCase().includes(q) || r.hint.toLowerCase().includes(q));
  const matchedTenants = tenants
    .filter((t) => !q || t.tenantId.toLowerCase().includes(q) || t.displayName.toLowerCase().includes(q))
    .slice(0, 6);

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center bg-black/45 pt-24 p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-label="快速命令面板"
        className="w-full max-w-[520px] overflow-hidden rounded-[8px] border border-[var(--border)] bg-[var(--card)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-2 border-b border-[var(--border)] px-3.5 py-2.5">
          <Search className="h-4 w-4 text-[var(--muted-foreground)]" />
          <input
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="跳转视图或搜索 tenant_id / 租户名称…"
            className="w-full bg-transparent text-xs text-[var(--foreground)] outline-none"
          />
          <kbd className="rounded border border-[var(--border)] px-1.5 py-0.5 font-mono text-[10px] text-[var(--muted-foreground)]">ESC</kbd>
        </div>
        <div className="max-h-[340px] overflow-y-auto p-2">
          <div className="px-2 py-1 text-[11px] font-medium text-[var(--muted-foreground)]">导航视图</div>
          {routes.map((r) => (
            <button
              key={r.id}
              onClick={() => {
                onSelectRoute(r.id);
                onClose();
              }}
              className="flex w-full items-center justify-between rounded-[4px] px-2.5 py-2 text-left text-xs hover:bg-[var(--muted)] cursor-pointer"
            >
              <span className="font-medium text-[var(--foreground)]">{r.label}</span>
              <span className="text-[11px] text-[var(--muted-foreground)]">{r.hint}</span>
            </button>
          ))}
          {matchedTenants.length > 0 && (
            <>
              <div className="mt-2 px-2 py-1 text-[11px] font-medium text-[var(--muted-foreground)]">快速打开租户抽屉</div>
              {matchedTenants.map((t) => (
                <button
                  key={t.tenantId}
                  onClick={() => {
                    onSelectRoute('tenants');
                    onSelectTenant(t.tenantId);
                    onClose();
                  }}
                  className="flex w-full items-center justify-between rounded-[4px] px-2.5 py-2 text-left text-xs hover:bg-[var(--muted)] cursor-pointer"
                >
                  <span className="font-mono text-[var(--foreground)]">{t.tenantId}</span>
                  <span className="text-[11px] text-[var(--muted-foreground)]">{t.displayName}</span>
                </button>
              ))}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
