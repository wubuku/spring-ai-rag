import { useEffect, useState } from 'react';
import { operatorClient } from '../domain/clientFactory';
import {
  TENANT_STATUS_LABEL,
  RECALL_STATUS_LABEL,
  COLLECTION_LABEL,
  DOCUMENT_LABEL,
  EMBEDDING_LABEL,
} from '../domain/glossary';
import type { KnowledgeHealthRow } from '../domain/operatorContract';
import { statusTone } from '../components/TenantDrawer';
import { Badge, EmptyState, OverviewBand, PageHeader, SkeletonTable, formatNumber } from '../ui/primitives';

export function KnowledgeHealthPage({
  refreshTick,
  onError,
}: {
  refreshTick: number;
  onError: (err: unknown) => void;
}) {
  const [rows, setRows] = useState<KnowledgeHealthRow[] | null>(null);

  useEffect(() => {
    let active = true;
    setRows(null);
    operatorClient
      .listKnowledgeHealth()
      .then((data) => { if (active) setRows(data); })
      .catch((err) => { if (active) onError(err); });
    return () => { active = false; };
  }, [refreshTick, onError]);

  const totalCollections = (rows ?? []).reduce((a, r) => a + r.collectionCount, 0);
  const totalDocs = (rows ?? []).reduce((a, r) => a + r.documentCount, 0);
  const totalEmbeddings = (rows ?? []).reduce((a, r) => a + r.embeddingCount, 0);
  const totalFailed = (rows ?? []).reduce((a, r) => a + r.failedJobs24h, 0);

  return (
    <div>
      <PageHeader
        title="知识库健康"
        description="按租户查看知识库、文档与向量分块聚合规模，异步任务队列积压与召回质量诊断。"
      />

      <div className="context-note mb-4">
        隐私与隔离边界声明：本页仅展示各租户聚合元数据、队列深度与检索质量指标，<strong>不读取也不展示</strong>任何租户的原始文档正文、分块文本或聊天内容。
      </div>

      <OverviewBand
        items={[
          { label: `全平台${COLLECTION_LABEL}`, value: formatNumber(totalCollections), note: '聚合计数' },
          { label: `全平台${DOCUMENT_LABEL}`, value: formatNumber(totalDocs), note: '聚合计数' },
          { label: `全平台${EMBEDDING_LABEL}`, value: formatNumber(totalEmbeddings), note: '向量主表规模' },
          { label: '24h 失败异步任务', value: formatNumber(totalFailed), note: '嵌入 / 清理失败合计', tone: totalFailed > 0 ? 'danger' : 'neutral' },
        ]}
      />

      <div className="surface">
        {rows === null ? <SkeletonTable rows={6} columns={7} /> : rows.length === 0 ? (
          <EmptyState title="暂无租户知识库指标" detail="租户创建并写入聚合任务后，这里会展示健康数据。" />
        ) : (
          <>
            <div className="hidden md:block overflow-x-auto">
              <table className="data-table text-xs">
                <thead>
                  <tr>
                    <th>租户</th>
                    <th>状态</th>
                    <th>{COLLECTION_LABEL} / {DOCUMENT_LABEL} / {EMBEDDING_LABEL}</th>
                    <th>估算存储</th>
                    <th>待处理队列（嵌入 / 清理）</th>
                    <th>24h 失败</th>
                    <th>召回质量</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.tenantId}>
                      <td>
                        <div className="font-mono font-semibold">{r.tenantId}</div>
                        <div className="text-[11px] text-[var(--muted-foreground)]">{r.displayName}</div>
                      </td>
                      <td><Badge tone={statusTone(r.status)}>{TENANT_STATUS_LABEL[r.status]}</Badge></td>
                      <td className="font-mono tabular-nums">
                        {r.collectionCount} / {formatNumber(r.documentCount)} / {formatNumber(r.embeddingCount)}
                      </td>
                      <td className="font-mono tabular-nums text-[var(--muted-foreground)]">{r.estimatedStorageMb.toFixed(1)} MB</td>
                      <td className="font-mono tabular-nums">{r.embeddingQueuePending} / {r.purgeJobsPending}</td>
                      <td className={`font-mono tabular-nums ${r.failedJobs24h > 0 ? 'font-semibold text-[var(--destructive)]' : 'text-[var(--muted-foreground)]'}`}>
                        {r.failedJobs24h}
                      </td>
                      <td>
                        <Badge tone={r.lastRecallCheckStatus === 'PASS' ? 'success' : r.lastRecallCheckStatus === 'WARN' ? 'warning' : 'neutral'} title="Recall@10 压测指标">
                          {RECALL_STATUS_LABEL[r.lastRecallCheckStatus]}{r.lastRecallAt10 !== null ? ` · ${(r.lastRecallAt10 * 100).toFixed(0)}%` : ''}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="divide-y divide-[var(--border)] md:hidden">
              {rows.map((r) => (
                <div key={r.tenantId} className="p-3.5 text-xs space-y-1.5">
                  <div className="flex items-center justify-between">
                    <span className="font-mono font-semibold">{r.tenantId}</span>
                    <Badge tone={r.lastRecallCheckStatus === 'PASS' ? 'success' : r.lastRecallCheckStatus === 'WARN' ? 'warning' : 'neutral'}>
                      {RECALL_STATUS_LABEL[r.lastRecallCheckStatus]}
                    </Badge>
                  </div>
                  <dl className="grid grid-cols-2 gap-x-3 gap-y-1 text-[11px] tabular-nums">
                    <dt className="text-[var(--muted-foreground)]">状态</dt>
                    <dd className="text-right">{TENANT_STATUS_LABEL[r.status]}</dd>
                    <dt className="text-[var(--muted-foreground)]">{COLLECTION_LABEL} / {DOCUMENT_LABEL} / {EMBEDDING_LABEL}</dt>
                    <dd className="font-mono text-right">{r.collectionCount} / {formatNumber(r.documentCount)} / {formatNumber(r.embeddingCount)}</dd>
                    <dt className="text-[var(--muted-foreground)]">估算存储</dt>
                    <dd className="font-mono text-right">{r.estimatedStorageMb.toFixed(1)} MB</dd>
                    <dt className="text-[var(--muted-foreground)]">待处理队列（嵌入 / 清理）</dt>
                    <dd className="font-mono text-right">{r.embeddingQueuePending} / {r.purgeJobsPending}</dd>
                    <dt className="text-[var(--muted-foreground)]">24h 失败</dt>
                    <dd className="font-mono text-right">{r.failedJobs24h}</dd>
                  </dl>
                </div>
              ))}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
