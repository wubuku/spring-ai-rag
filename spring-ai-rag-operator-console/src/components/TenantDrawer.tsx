import { useEffect, useState } from 'react';
import { operatorClient } from '../domain/clientFactory';
import {
  AUDIT_ACTION_LABEL,
  CREDENTIAL_STATUS_LABEL,
  TENANT_STATUS_LABEL,
  COLLECTION_LABEL,
  DOCUMENT_LABEL,
  EMBEDDING_LABEL,
} from '../domain/glossary';
import type { TenantDetail, TenantStatus } from '../domain/operatorContract';
import { Badge, Bar, Button, ConfirmModal, Drawer, formatNumber, formatShortTime, type Tone } from '../ui/primitives';

export function statusTone(status: TenantStatus): Tone {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'SUSPENDED':
      return 'warning';
    case 'DISABLED':
    case 'SOFT_DELETED':
      return 'danger';
  }
}

export function TenantDrawer({
  tenantId,
  onClose,
  onMutated,
  onNotify,
}: {
  tenantId: string | null;
  onClose: () => void;
  onMutated: () => void;
  onNotify: (tone: Tone, text: string) => void;
}) {
  const [detail, setDetail] = useState<TenantDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [editingName, setEditingName] = useState(false);
  const [nameInput, setNameInput] = useState('');
  const [pendingAction, setPendingAction] = useState<'suspend' | 'resume' | 'disable' | 'soft_delete' | null>(null);

  const load = async (id: string) => {
    setLoading(true);
    try {
      const data = await operatorClient.getTenantDetail(id);
      setDetail(data);
      setNameInput(data.summary.displayName);
    } catch (err) {
      onNotify('danger', err instanceof Error ? err.message : '加载租户详情失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (tenantId) {
      setEditingName(false);
      load(tenantId);
    } else {
      setDetail(null);
    }
  }, [tenantId]);

  const summary = detail?.summary;

  return (
    <>
      <Drawer
        open={tenantId !== null}
        title={summary ? summary.displayName : '租户详情'}
        subtitle={tenantId ? `tenant_id: ${tenantId}` : undefined}
        onClose={onClose}
      >
        {loading || !detail || !summary ? (
          <div className="py-8 text-center text-xs text-[var(--muted-foreground)]">加载租户详情中…</div>
        ) : (
          <div className="space-y-5 text-xs">
            <section className="border-b border-[var(--border)] pb-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <Badge tone={statusTone(summary.status)}>{TENANT_STATUS_LABEL[summary.status]}</Badge>
                  {summary.protectedDefault && <Badge tone="primary">默认租户 · 受保护</Badge>}
                </div>
                {!editingName && (
                  <Button size="sm" onClick={() => setEditingName(true)}>编辑显示名</Button>
                )}
              </div>
              {editingName && (
                <div className="mt-3 flex gap-2">
                  <input
                    value={nameInput}
                    onChange={(e) => setNameInput(e.target.value)}
                    className="flex-1 rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2.5 py-1 text-xs"
                  />
                  <Button
                    variant="primary"
                    size="sm"
                    onClick={async () => {
                      await operatorClient.updateTenant(summary.tenantId, {
                        displayName: nameInput,
                        reason: '控制台更新租户显示名称',
                      });
                      setEditingName(false);
                      onNotify('success', `已更新 ${summary.tenantId} 显示名称`);
                      onMutated();
                      load(summary.tenantId);
                    }}
                  >
                    保存
                  </Button>
                  <Button size="sm" onClick={() => setEditingName(false)}>取消</Button>
                </div>
              )}
              <div className="mt-3 grid grid-cols-2 gap-2 font-mono text-[11px] text-[var(--muted-foreground)]">
                <div>创建时间：{formatShortTime(summary.createdAt)}</div>
                <div>更新时间：{formatShortTime(summary.updatedAt)}</div>
              </div>
            </section>

            <section className="border-b border-[var(--border)] pb-4">
              <h3 className="font-semibold text-[var(--foreground)]">生命周期控制</h3>
              <p className="mt-1 text-[11px] text-[var(--muted-foreground)]">
                状态变更立即在请求边缘生效（非正常状态返回显式 403）。
              </p>
              <div className="mt-3 flex flex-wrap gap-2">
                {summary.status === 'ACTIVE' && (
                  <Button size="sm" onClick={() => setPendingAction('suspend')}>暂停服务</Button>
                )}
                {summary.status === 'SUSPENDED' && (
                  <Button size="sm" variant="primary" onClick={() => setPendingAction('resume')}>恢复服务</Button>
                )}
                <Button
                  size="sm"
                  variant="danger"
                  disabled={summary.protectedDefault || summary.status === 'DISABLED' || summary.status === 'SOFT_DELETED'}
                  title={summary.protectedDefault ? '默认租户受保护，禁止禁用' : undefined}
                  onClick={() => setPendingAction('disable')}
                >
                  禁用（终态）
                </Button>
                <Button
                  size="sm"
                  variant="danger"
                  disabled={summary.protectedDefault || summary.status === 'SOFT_DELETED'}
                  title={summary.protectedDefault ? '默认租户受保护，禁止软删' : undefined}
                  onClick={() => setPendingAction('soft_delete')}
                >
                  软删租户
                </Button>
              </div>
              {summary.protectedDefault && (
                <p className="mt-2 text-[11px] text-[var(--warning)]">
                  默认租户 default 受系统保护，禁用与软删已锁定。
                </p>
              )}
            </section>

            <section className="border-b border-[var(--border)] pb-4">
              <h3 className="font-semibold text-[var(--foreground)]">配额快照</h3>
              <div className="mt-2.5 space-y-2.5">
                {detail.quotas.map((q) => {
                  const ratio = q.maxTokens ? q.usedTokens / q.maxTokens : 0;
                  return (
                    <div key={q.period} className="rounded-[4px] border border-[var(--border)] p-2.5">
                      <div className="flex items-center justify-between">
                        <span className="font-mono font-medium">{q.period} 窗口</span>
                        <span className="font-mono tabular-nums text-[var(--muted-foreground)]">
                          {formatNumber(q.usedTokens)} / {formatNumber(q.maxTokens)} tokens
                        </span>
                      </div>
                      {q.maxTokens && <div className="mt-1.5"><Bar ratio={ratio} /></div>}
                    </div>
                  );
                })}
              </div>
            </section>

            <section className="border-b border-[var(--border)] pb-4">
              <h3 className="font-semibold text-[var(--foreground)]">凭据概览（仅元数据）</h3>
              <div className="mt-2 space-y-1.5">
                {detail.credentials.map((c) => (
                  <div key={c.principalId} className="flex items-center justify-between rounded-[4px] border border-[var(--border)] px-2.5 py-2 font-mono text-[11px]">
                    <span>{c.principalId}</span>
                    <Badge tone={c.status === 'ACTIVE' ? 'success' : c.status === 'ORPHAN_NO_KEY' ? 'warning' : 'neutral'}>
                      {CREDENTIAL_STATUS_LABEL[c.status]}
                    </Badge>
                  </div>
                ))}
              </div>
            </section>

            <section className="border-b border-[var(--border)] pb-4">
              <h3 className="font-semibold text-[var(--foreground)]">知识库聚合规模（不含文档正文）</h3>
              <div className="mt-2 grid grid-cols-3 gap-2 font-mono tabular-nums">
                <div className="rounded-[4px] border border-[var(--border)] p-2">
                  <div className="text-[10px] text-[var(--muted-foreground)]">{COLLECTION_LABEL}</div>
                  <div className="mt-1 text-sm font-semibold">{formatNumber(detail.health.collectionCount)}</div>
                </div>
                <div className="rounded-[4px] border border-[var(--border)] p-2">
                  <div className="text-[10px] text-[var(--muted-foreground)]">{DOCUMENT_LABEL}</div>
                  <div className="mt-1 text-sm font-semibold">{formatNumber(detail.health.documentCount)}</div>
                </div>
                <div className="rounded-[4px] border border-[var(--border)] p-2">
                  <div className="text-[10px] text-[var(--muted-foreground)]">{EMBEDDING_LABEL}</div>
                  <div className="mt-1 text-sm font-semibold">{formatNumber(detail.health.embeddingCount)}</div>
                </div>
              </div>
            </section>

            <section>
              <h3 className="font-semibold text-[var(--foreground)]">最近平台审计</h3>
              <div className="mt-2 space-y-2">
                {detail.recentAudits.length === 0 ? (
                  <div className="text-[11px] text-[var(--muted-foreground)]">暂无该租户相关审计记录。</div>
                ) : (
                  detail.recentAudits.map((evt) => (
                    <div key={evt.seq} className="rounded-[4px] border border-[var(--border)] p-2 text-[11px]">
                      <div className="flex items-center justify-between font-mono">
                        <span>#{evt.seq} · {AUDIT_ACTION_LABEL[evt.action] ?? evt.action}</span>
                        <span className="text-[var(--muted-foreground)]">{formatShortTime(evt.occurredAt)}</span>
                      </div>
                      <div className="mt-1 text-[var(--muted-foreground)]">{evt.reason}</div>
                    </div>
                  ))
                )}
              </div>
            </section>
          </div>
        )}
      </Drawer>

      <ConfirmModal
        open={pendingAction !== null && summary !== undefined}
        title={
          pendingAction === 'suspend'
            ? '确认暂停租户'
            : pendingAction === 'resume'
              ? '确认恢复租户'
              : pendingAction === 'disable'
                ? '确认禁用租户（不可逆终态）'
                : '确认软删租户'
        }
        description={`将对租户 ${summary?.tenantId ?? ''} 执行 ${pendingAction ?? ''} 操作并写入平台持久化审计流水。`}
        confirmTarget={pendingAction === 'resume' ? undefined : summary?.tenantId}
        confirmLabel="确认执行"
        danger={pendingAction !== 'resume'}
        onCancel={() => setPendingAction(null)}
        onConfirm={async (reason) => {
          if (!summary || !pendingAction) return;
          try {
            await operatorClient.transitionTenant(summary.tenantId, pendingAction, reason);
            onNotify('success', `租户 ${summary.tenantId} 已执行 ${pendingAction}`);
            setPendingAction(null);
            onMutated();
            load(summary.tenantId);
          } catch (err) {
            onNotify('danger', err instanceof Error ? err.message : '操作失败');
          }
        }}
      />
    </>
  );
}
