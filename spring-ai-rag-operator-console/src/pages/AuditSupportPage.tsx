import { useEffect, useState } from 'react';
import { ShieldAlert } from 'lucide-react';
import { operatorClient } from '../domain/clientFactory';
import { AUDIT_ACTION_LABEL, AUDIT_OUTCOME_LABEL } from '../domain/glossary';
import type { PlatformAuditEvent, SupportSessionRecord, TenantSummary } from '../domain/operatorContract';
import { Badge, Button, EmptyState, Modal, PageHeader, SkeletonTable, formatShortTime, type Tone } from '../ui/primitives';

export function AuditSupportPage({
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
  const [audits, setAudits] = useState<PlatformAuditEvent[] | null>(null);
  const [sessions, setSessions] = useState<SupportSessionRecord[]>([]);
  const [tenantFilter, setTenantFilter] = useState('');
  const [startOpen, setStartOpen] = useState(false);
  const [targetTenant, setTargetTenant] = useState('acme-legal');
  const [ticketRef, setTicketRef] = useState('');
  const [reason, setReason] = useState('');
  const [ttlMinutes, setTtlMinutes] = useState(15);

  useEffect(() => {
    let active = true;
    setAudits(null);
    Promise.all([
      operatorClient.listAuditEvents({ tenantId: tenantFilter || undefined }),
      operatorClient.listSupportSessions(),
    ])
      .then(([a, s]) => {
        if (active) {
          setAudits(a);
          setSessions(s);
        }
      })
      .catch((err) => {
        if (active) onError(err);
      });
    return () => { active = false; };
  }, [tenantFilter, refreshTick]);

  const activeSession = sessions.find((s) => s.revokedAt === null);

  return (
    <div className="space-y-5">
      <PageHeader
        title="审计与只读支持"
        description="按单调序号倒序追溯全量平台管理操作，并管理限时、只读、强审计的支持代看会话。"
        actions={
          <Button variant="primary" onClick={() => setStartOpen(true)}>
            <ShieldAlert className="h-3.5 w-3.5" />
            申请只读支持会话
          </Button>
        }
      />

      {activeSession && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-[4px] border border-[var(--primary)]/40 bg-[var(--primary-soft)] px-4 py-2.5 text-xs text-[var(--primary-soft-foreground)]">
          <div>
            <strong>只读支持会话生效中：</strong>
            <span className="font-mono"> {activeSession.sessionId}</span> · 租户{' '}
            <span className="font-mono">{activeSession.tenantId}</span> · 工单{' '}
            <span className="font-mono">{activeSession.ticketRef}</span> · 到期{' '}
            <span className="font-mono">{formatShortTime(activeSession.expiresAt)}</span>（严格只读，禁止写入业务数据）
          </div>
          <Button
            size="sm"
            variant="danger"
            onClick={async () => {
              await operatorClient.revokeSupportSession(activeSession.sessionId, '排查结束提前撤销');
              onNotify('success', `已撤销只读支持会话 ${activeSession.sessionId}`);
              onMutated();
            }}
          >
            立即撤销
          </Button>
        </div>
      )}

      <div className="surface">
        <div className="surface-header">
          <div>
            <h2 className="text-sm font-semibold">平台持久化审计流水</h2>
            <p className="text-[11px] text-[var(--muted-foreground)]">所有开户、状态变更、凭据签发/吊销、配额调整与支持会话操作均不可篡改落库。</p>
          </div>
          <select
            value={tenantFilter}
            onChange={(e) => setTenantFilter(e.target.value)}
            className="rounded-[4px] border border-[var(--border-strong)] bg-[var(--card)] px-2.5 py-1 font-mono text-xs"
          >
            <option value="">全部租户</option>
            {tenants.map((t) => (
              <option key={t.tenantId} value={t.tenantId}>{t.tenantId}</option>
            ))}
          </select>
        </div>

        {audits === null ? <SkeletonTable rows={6} columns={7} /> : audits.length === 0 ? (
          <EmptyState title="暂无平台审计流水" detail="执行租户或凭据管理操作后，这里会按时间倒序展示记录。" />
        ) : (
        <>
        <div className="hidden md:block overflow-x-auto">
          <table className="data-table text-xs" data-testid="audit-table">
            <thead>
              <tr>
                <th>#Seq</th>
                <th>时间</th>
                <th>操作者</th>
                <th>动作</th>
                <th>目标租户 / 资源</th>
                <th>原因</th>
                <th>结果</th>
              </tr>
            </thead>
            <tbody>
              {audits.map((a) => (
                <tr key={a.seq}>
                  <td className="font-mono tabular-nums font-semibold">#{a.seq}</td>
                  <td className="font-mono text-[11px] text-[var(--muted-foreground)]">{formatShortTime(a.occurredAt)}</td>
                  <td className="font-mono text-[11px]">{a.actor}</td>
                  <td><Badge tone="primary">{AUDIT_ACTION_LABEL[a.action] ?? a.action}</Badge></td>
                  <td className="font-mono text-[11px]">{a.targetTenantId ?? '—'} / {a.targetResourceId ?? '—'}</td>
                  <td className="max-w-[280px] text-[var(--muted-foreground)]">{a.reason}</td>
                  <td><Badge tone={a.outcome === 'SUCCESS' ? 'success' : 'danger'}>{AUDIT_OUTCOME_LABEL[a.outcome]}</Badge></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="divide-y divide-[var(--border)] md:hidden">
          {audits.map((a) => (
            <div key={a.seq} className="p-3.5 text-xs space-y-1">
              <div className="flex items-center justify-between font-mono">
                <span className="font-semibold">#{a.seq} · {AUDIT_ACTION_LABEL[a.action] ?? a.action}</span>
                <Badge tone={a.outcome === 'SUCCESS' ? 'success' : 'danger'}>{AUDIT_OUTCOME_LABEL[a.outcome]}</Badge>
              </div>
              <div className="font-mono text-[11px] text-[var(--muted-foreground)]">
                {a.targetTenantId ?? '—'} · {a.actor} · {formatShortTime(a.occurredAt)}
              </div>
              <div className="text-[11px] text-[var(--muted-foreground)]">{a.reason}</div>
            </div>
          ))}
        </div>
        </>
        )}
      </div>

      <Modal
        open={startOpen}
        title="申请限时只读支持会话"
        description="强制只读，到期自动失效并全程审计。"
        onClose={() => setStartOpen(false)}
        footer={
          <>
            <Button onClick={() => setStartOpen(false)}>取消</Button>
            <Button
              variant="primary"
              disabled={!ticketRef.trim() || !reason.trim()}
              onClick={async () => {
                await operatorClient.startSupportSession({ tenantId: targetTenant, ticketRef, reason, ttlMinutes });
                setStartOpen(false);
                setTicketRef('');
                setReason('');
                onNotify('success', `已开启 ${targetTenant} 的只读支持会话`);
                onMutated();
              }}
            >
              开启只读会话
            </Button>
          </>
        }
      >
            <div className="mt-3 space-y-3">
              <div>
                <label className="block font-medium">目标租户</label>
                <select
                  value={targetTenant}
                  onChange={(e) => setTargetTenant(e.target.value)}
                  className="mt-1 w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1.5 font-mono text-xs"
                >
                  {tenants.map((t) => (
                    <option key={t.tenantId} value={t.tenantId}>{t.tenantId}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block font-medium">关联工单号（必填）</label>
                <input
                  value={ticketRef}
                  onChange={(e) => setTicketRef(e.target.value)}
                  placeholder="例如：INC-4902"
                  className="mt-1 w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1.5 font-mono text-xs"
                />
              </div>
              <div>
                <label className="block font-medium">申请原因（必填）</label>
                <input
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  placeholder="例如：核查队列失败任务元数据"
                  className="mt-1 w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1.5 text-xs"
                />
              </div>
              <div>
                <label className="block font-medium">会话有效期</label>
                <select
                  value={ttlMinutes}
                  onChange={(e) => setTtlMinutes(Number(e.target.value))}
                  className="mt-1 w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1.5 text-xs"
                >
                  <option value={15}>15 分钟（推荐）</option>
                  <option value={30}>30 分钟</option>
                </select>
              </div>
            </div>
      </Modal>
    </div>
  );
}
