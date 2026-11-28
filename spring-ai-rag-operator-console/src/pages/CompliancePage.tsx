import { useEffect, useState } from 'react';
import { operatorClient } from '../domain/clientFactory';
import {
  DB_ROLE_CHECK_LABEL,
  RLS_VERIFIER_LABEL,
  TENANT_MODE_LABEL,
} from '../domain/glossary';
import type { ComplianceSnapshot } from '../domain/operatorContract';
import { AttentionStrip, Badge, OverviewBand, PageHeader, StatusDot, formatShortTime } from '../ui/primitives';

export function CompliancePage({
  refreshTick,
  onError,
}: {
  refreshTick: number;
  onError: (err: unknown) => void;
}) {
  const [snap, setSnap] = useState<ComplianceSnapshot | null>(null);

  useEffect(() => {
    let active = true;
    operatorClient
      .getComplianceSnapshot()
      .then((data) => { if (active) setSnap(data); })
      .catch((err) => { if (active) onError(err); });
    return () => { active = false; };
  }, [refreshTick]);

  if (!snap) {
    return <div data-testid="skeleton-table" className="py-12 text-center text-xs text-[var(--muted-foreground)]"><div className="animate-pulse">加载隔离合规快照中…</div></div>;
  }

  return (
    <div>
      <PageHeader
        title="模式与隔离合规"
        description="只读安全驾驶舱：展示运行模式、租户隔离策略自检、数据库角色权限、表归属清点与端点声明覆盖率。"
        meta={
          <>
            <Badge tone={snap.tenantMode === 'STRICT' ? 'success' : 'warning'}>模式：{TENANT_MODE_LABEL[snap.tenantMode]}</Badge>
            <Badge tone={snap.rlsVerifierStatus === 'PASS' ? 'success' : snap.rlsVerifierStatus === 'WARN' ? 'warning' : 'danger'}>
              租户隔离校验：{RLS_VERIFIER_LABEL[snap.rlsVerifierStatus]}
            </Badge>
            <span className="font-mono text-[11px] text-[var(--muted-foreground)]">
              最近核验：{formatShortTime(snap.rlsVerifierCheckedAt)}
            </span>
          </>
        }
      />

      {snap.rlsVerifierStatus !== 'PASS' && (
        <AttentionStrip
          tone="danger"
          message="租户隔离启动校验或数据库角色权限存在阻断项，请立即排查后再开放生产多租户流量。"
        />
      )}

      <OverviewBand
        items={[
          { label: '已登记业务表总数', value: String(snap.tableCatalog.total), note: 'TenantTableCatalog 真源' },
          { label: '租户私有表（强制隔离）', value: String(snap.tableCatalog.tenantPrivate), note: '会话上下文强制隔离' },
          { label: '平台全局 / 访问凭据', value: `${snap.tableCatalog.platformGlobal} / ${snap.tableCatalog.credential}`, note: '平台全局与认证凭据表' },
          { label: '端点声明覆盖率', value: `${snap.endpointGuard.declaredRoutes}/${snap.endpointGuard.totalRoutes}`, note: `含 ${snap.endpointGuard.platformControlRoutes} 条平台控制面路由` },
        ]}
      />

      <div className="grid gap-4 md:grid-cols-2">
        <div className="surface p-4 text-xs">
          <h2 className="text-sm font-semibold">数据库应用角色安全断言</h2>
          <p className="mt-1 text-[var(--muted-foreground)]">应用连接池必须为非超级管理员、强制受 RLS 约束且非业务表拥有者。</p>
          <dl className="mt-3 space-y-2 font-mono">
            <div className="flex items-center justify-between border-b border-[var(--border)] pb-2">
              <dt className="text-[var(--muted-foreground)]">当前数据库角色</dt>
              <dd>{snap.dbRoleChecks.currentUser}</dd>
            </div>
            {(['isSuperuser', 'hasBypassRls', 'isTableOwner'] as const).map((key, idx) => {
              const meta = DB_ROLE_CHECK_LABEL[key];
              const risky = snap.dbRoleChecks[key];
              return (
                <div key={key} className={`flex items-center justify-between ${idx < 2 ? 'border-b border-[var(--border)] pb-2' : ''}`}>
                  <dt className="text-[var(--muted-foreground)]" title={`数据库检查项：${meta.tech}`}>{meta.label}</dt>
                  <dd className="flex items-center gap-1.5">
                    <StatusDot tone={risky ? (key === 'isTableOwner' ? 'warning' : 'danger') : 'success'} />
                    {risky ? meta.riskyText : meta.safeText}
                  </dd>
                </div>
              );
            })}
          </dl>
        </div>

        <div className="surface p-4 text-xs">
          <h2 className="text-sm font-semibold">合规核验发现（Findings）</h2>
          <div className="mt-3 space-y-2.5">
            {snap.rlsFindings.map((f) => (
              <div key={f.code} className="rounded-[4px] border border-[var(--border)] p-2.5">
                <div className="flex items-center gap-2">
                  <Badge tone={f.severity === 'P0' ? 'danger' : f.severity === 'P1' ? 'warning' : 'primary'}>
                    {f.severity}
                  </Badge>
                  <span className="font-mono text-[11px] font-semibold">{f.code}</span>
                </div>
                <p className="mt-1.5 text-[var(--muted-foreground)] leading-5">{f.message}</p>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
