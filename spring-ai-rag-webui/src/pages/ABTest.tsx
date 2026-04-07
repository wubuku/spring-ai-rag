import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend,
  ResponsiveContainer,
} from 'recharts';
import { abtestApi, type CreateExperimentRequest } from '../api/abtest';
import { useToast } from '../components/Toast';
import styles from './ABTest.module.css';

type Tab = 'list' | 'detail';

const STATUS_COLORS: Record<string, string> = {
  DRAFT: '#9ca3af',
  RUNNING: '#22c55e',
  PAUSED: '#f59e0b',
  STOPPED: '#6b7280',
  COMPLETED: '#3b82f6',
};

export function ABTest() {
  const { t } = useTranslation();
  const [tab, setTab] = useState<Tab>('list');
  const [selectedId, setSelectedId] = useState<number | null>(null);

  return (
    <div>
      <h1 className="page-title">{t('abtest.title')}</h1>
      {tab === 'list' && (
        <ExperimentList onSelect={id => { setSelectedId(id); setTab('detail'); }} />
      )}
      {tab === 'detail' && selectedId !== null && (
        <ExperimentDetail experimentId={selectedId} onBack={() => setTab('list')} />
      )}
    </div>
  );
}

// ==================== Experiment List ====================

function ExperimentList({ onSelect }: { onSelect: (id: number) => void }) {
  const { t } = useTranslation();
  const [showCreate, setShowCreate] = useState(false);
  const { data, isPending } = useQuery({
    queryKey: ['abtest', 'experiments'],
    queryFn: () => abtestApi.listExperiments({ size: 100 }),
  });

  return (
    <div>
      <div className={styles.toolbar}>
        <button className={styles.btnPrimary} onClick={() => setShowCreate(true)}>
          {t('abtest.createExperiment')}
        </button>
      </div>

      {isPending ? (
        <div className={styles.loading}>{t('common.loading')}</div>
      ) : !data?.data?.length ? (
        <div className={styles.empty}>{t('abtest.noExperiments')}</div>
      ) : (
        <div className={styles.table}>
          <div className={styles.tableHead}>
            <span>{t('abtest.name')}</span>
            <span>{t('abtest.status')}</span>
            <span>{t('abtest.targetMetric')}</span>
            <span>{t('abtest.samples')}</span>
            <span>{t('abtest.winner')}</span>
            <span>{t('abtest.actions')}</span>
          </div>
          {data.data.map(exp => (
            <div key={exp.id} className={styles.tableRow}>
              <span className={styles.name}>{exp.experimentName}</span>
              <span>
                <span
                  className={styles.badge}
                  style={{ background: STATUS_COLORS[exp.status] ?? '#9ca3af' }}
                >
                  {exp.status}
                </span>
              </span>
              <span>{exp.targetMetric ?? '—'}</span>
              <span>{exp.sampleCount ?? 0}</span>
              <span>{exp.winner ?? '—'}</span>
              <span>
                <button
                  className={styles.btnLink}
                  onClick={() => onSelect(exp.id)}
                >
                  {t('abtest.viewDetails')}
                </button>
              </span>
            </div>
          ))}
        </div>
      )}

      {showCreate && (
        <CreateExperimentModal onClose={() => setShowCreate(false)} />
      )}
    </div>
  );
}

// ==================== Experiment Detail ====================

function ExperimentDetail({ experimentId, onBack }: { experimentId: number; onBack: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { showToast } = useToast();

  const { data: exp, isPending: expPending } = useQuery({
    queryKey: ['abtest', 'experiment', experimentId],
    queryFn: () => abtestApi.getExperiment(experimentId),
  });

  const { data: analysis } = useQuery({
    queryKey: ['abtest', 'analysis', experimentId],
    queryFn: () => abtestApi.getAnalysis(experimentId),
    enabled: exp?.data?.status === 'COMPLETED' || exp?.data?.status === 'STOPPED',
    retry: false,
  });

  const startMut = useMutation({
    mutationFn: () => abtestApi.startExperiment(experimentId),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['abtest'] }); showToast(t('abtest.started'), 'success'); },
    onError: () => showToast(t('abtest.startError'), 'error'),
  });

  const pauseMut = useMutation({
    mutationFn: () => abtestApi.pauseExperiment(experimentId),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['abtest'] }); showToast(t('abtest.paused'), 'success'); },
    onError: () => showToast(t('abtest.pauseError'), 'error'),
  });

  const stopMut = useMutation({
    mutationFn: () => abtestApi.stopExperiment(experimentId),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['abtest'] }); showToast(t('abtest.stopped'), 'success'); },
    onError: () => showToast(t('abtest.stopError'), 'error'),
  });

  if (expPending) return <div className={styles.loading}>{t('common.loading')}</div>;
  if (!exp) return <div className={styles.empty}>{t('abtest.notFound')}</div>;

  return (
    <div>
      <button className={styles.btnBack} onClick={onBack}>
        ← {t('abtest.back')}
      </button>

      <div className={styles.header}>
        <div>
          <h2>{exp.data.experimentName}</h2>
          <p className={styles.desc}>{exp.data.description}</p>
        </div>
        <span
          className={styles.badge}
          style={{ background: STATUS_COLORS[exp.data.status] ?? '#9ca3af' }}
        >
          {exp.data.status}
        </span>
      </div>

      {/* Actions */}
      <div className={styles.actions}>
        {exp.data.status === 'DRAFT' && (
          <button className={styles.btnPrimary} onClick={() => startMut.mutate()}>
            {t('abtest.start')}
          </button>
        )}
        {exp.data.status === 'RUNNING' && (
          <button className={styles.btnSecondary} onClick={() => pauseMut.mutate()}>
            {t('abtest.pause')}
          </button>
        )}
        {exp.data.status === 'PAUSED' && (
          <button className={styles.btnPrimary} onClick={() => startMut.mutate()}>
            {t('abtest.resume')}
          </button>
        )}
        {(exp.data.status === 'RUNNING' || exp.data.status === 'PAUSED') && (
          <button className={styles.btnDanger} onClick={() => stopMut.mutate()}>
            {t('abtest.stop')}
          </button>
        )}
      </div>

      {/* Stats Summary */}
      <div className={styles.statsGrid}>
        <StatCard label={t('abtest.targetMetric')} value={exp.data.targetMetric ?? '—'} />
        <StatCard label={t('abtest.samples')} value={String(exp.data.sampleCount ?? 0)} />
        <StatCard label={t('abtest.winner')} value={exp.data.winner ?? t('abtest.noWinner')} />
        <StatCard label={t('abtest.confidence')} value={analysis ? `${(analysis.data.confidenceLevel * 100).toFixed(1)}%` : '—'} />
      </div>

      {/* Analysis Charts */}
      {(exp.data.status === 'COMPLETED' || exp.data.status === 'STOPPED') && analysis && (
        <div className={styles.section}>
          <h3>{t('abtest.analysis')}</h3>

          {analysis.data.isSignificant ? (
            <div className={styles.significanceBadge}>
              ✓ {t('abtest.statisticallySignificant')} — {analysis.data.recommendation}
            </div>
          ) : (
            <div className={styles.notSignificant}>
              ✗ {t('abtest.notSignificant')} — {analysis.data.recommendation}
            </div>
          )}

          {analysis.data.variantStats && Object.keys(analysis.data.variantStats).length > 0 && (
            <div className={styles.chartContainer}>
              <ResponsiveContainer width="100%" height={300}>
                <BarChart data={Object.entries(analysis.data.variantStats).map(([name, stats]) => ({
                  name,
                  samples: stats.sampleSize,
                  mean: stats.meanValue,
                  conversion: stats.conversionRate != null ? (stats.conversionRate * 100).toFixed(1) : undefined,
                }))}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="name" />
                  <YAxis />
                  <Tooltip formatter={(v, key) => [v, key]} />
                  <Legend />
                  <Bar dataKey="samples" fill="#6366f1" name={t('abtest.samples')} />
                  <Bar dataKey="mean" fill="#22c55e" name={t('abtest.meanValue')} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}

          {/* Variant Table */}
          {analysis.data.variantStats && (
            <div className={styles.table}>
              <div className={styles.tableHead}>
                <span>{t('abtest.variant')}</span>
                <span>{t('abtest.samples')}</span>
                <span>{t('abtest.meanValue')}</span>
                <span>{t('abtest.stdDeviation')}</span>
                <span>{t('abtest.conversionRate')}</span>
                <span>{t('abtest.confidenceInterval')}</span>
              </div>
              {Object.entries(analysis.data.variantStats).map(([name, stats]) => (
                <div key={name} className={styles.tableRow}>
                  <span className={styles.name}>{name}</span>
                  <span>{stats.sampleSize}</span>
                  <span>{stats.meanValue.toFixed(4)}</span>
                  <span>{stats.stdDeviation.toFixed(4)}</span>
                  <span>{stats.conversionRate != null ? `${(stats.conversionRate * 100).toFixed(2)}%` : '—'}</span>
                  <span>
                    {stats.confidenceInterval
                      ? `[${stats.confidenceInterval[0].toFixed(4)}, ${stats.confidenceInterval[1].toFixed(4)}]`
                      : '—'}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ==================== Create Experiment Modal ====================

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <div className={styles.statCard}>
      <div className={styles.statLabel}>{label}</div>
      <div className={styles.statValue}>{value}</div>
    </div>
  );
}

function CreateExperimentModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { showToast } = useToast();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [targetMetric, setTargetMetric] = useState('retrieval_precision');
  const [variantA, setVariantA] = useState('control');
  const [variantB, setVariantB] = useState('variant_b');
  const [splitA, setSplitA] = useState('50');
  const [splitB, setSplitB] = useState('50');

  const createMut = useMutation({
    mutationFn: (data: CreateExperimentRequest) => abtestApi.createExperiment(data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['abtest'] });
      showToast(t('abtest.created'), 'success');
      onClose();
    },
    onError: () => showToast(t('abtest.createError'), 'error'),
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;
    createMut.mutate({
      experimentName: name.trim(),
      description: description.trim() || undefined,
      targetMetric: targetMetric || undefined,
      trafficSplit: { [variantA]: Number(splitA) / 100, [variantB]: Number(splitB) / 100 },
      minSampleSize: 100,
    });
  };

  return (
    <div className={styles.modalOverlay} onClick={onClose}>
      <div className={styles.modal} onClick={e => e.stopPropagation()}>
        <h2>{t('abtest.createExperiment')}</h2>
        <form onSubmit={handleSubmit}>
          <div className={styles.formGroup}>
            <label>{t('abtest.name')}</label>
            <input value={name} onChange={e => setName(e.target.value)} required placeholder={t('abtest.namePlaceholder')} />
          </div>
          <div className={styles.formGroup}>
            <label>{t('abtest.description')}</label>
            <textarea value={description} onChange={e => setDescription(e.target.value)} rows={2} />
          </div>
          <div className={styles.formGroup}>
            <label>{t('abtest.targetMetric')}</label>
            <select value={targetMetric} onChange={e => setTargetMetric(e.target.value)}>
              <option value="retrieval_precision">{t('abtest.metricPrecision')}</option>
              <option value="retrieval_recall">{t('abtest.metricRecall')}</option>
              <option value="user_satisfaction">{t('abtest.metricSatisfaction')}</option>
            </select>
          </div>
          <div className={styles.formRow}>
            <div className={styles.formGroup}>
              <label>{t('abtest.variant')} A</label>
              <input value={variantA} onChange={e => setVariantA(e.target.value)} />
            </div>
            <div className={styles.formGroup} style={{ width: 80 }}>
              <label>{t('abtest.traffic')}%</label>
              <input type="number" min="0" max="100" value={splitA} onChange={e => setSplitA(e.target.value)} />
            </div>
          </div>
          <div className={styles.formRow}>
            <div className={styles.formGroup}>
              <label>{t('abtest.variant')} B</label>
              <input value={variantB} onChange={e => setVariantB(e.target.value)} />
            </div>
            <div className={styles.formGroup} style={{ width: 80 }}>
              <label>{t('abtest.traffic')}%</label>
              <input type="number" min="0" max="100" value={splitB} onChange={e => setSplitB(e.target.value)} />
            </div>
          </div>
          <div className={styles.modalActions}>
            <button type="button" className={styles.btnSecondary} onClick={onClose}>{t('common.cancel')}</button>
            <button type="submit" className={styles.btnPrimary} disabled={createMut.isPending}>
              {createMut.isPending ? t('common.loading') : t('abtest.create')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
