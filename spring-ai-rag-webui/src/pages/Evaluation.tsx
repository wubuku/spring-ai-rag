import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { evaluationApi } from '../api/evaluation';
import styles from './Evaluation.module.css';

type Tab = 'report' | 'history' | 'feedback' | 'judge';

function fmt(n: unknown): string {
  if (typeof n === 'number' && Number.isFinite(n)) {
    return n.toFixed(3);
  }
  return '—';
}

export function Evaluation() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [tab, setTab] = useState<Tab>('report');

  const reportQ = useQuery({
    queryKey: ['evaluation-report'],
    queryFn: async () => (await evaluationApi.getReport()).data,
  });

  const historyQ = useQuery({
    queryKey: ['evaluation-history'],
    queryFn: async () => (await evaluationApi.getHistory({ page: 0, size: 20 })).data,
    enabled: tab === 'history',
  });

  const feedbackStatsQ = useQuery({
    queryKey: ['feedback-stats'],
    queryFn: async () => (await evaluationApi.getFeedbackStats()).data,
    enabled: tab === 'feedback' || tab === 'report',
  });

  const feedbackHistoryQ = useQuery({
    queryKey: ['feedback-history'],
    queryFn: async () => (await evaluationApi.getFeedbackHistory({ page: 0, size: 20 })).data,
    enabled: tab === 'feedback',
  });

  const [evalForm, setEvalForm] = useState({
    query: '',
    retrieved: '',
    relevant: '',
  });
  const evaluateM = useMutation({
    mutationFn: () =>
      evaluationApi.evaluate({
        query: evalForm.query,
        retrievedDocIds: evalForm.retrieved.split(/[,\s]+/).filter(Boolean),
        relevantDocIds: evalForm.relevant.split(/[,\s]+/).filter(Boolean),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['evaluation-report'] });
      qc.invalidateQueries({ queryKey: ['evaluation-history'] });
    },
  });

  const [judgeForm, setJudgeForm] = useState({ query: '', context: '', answer: '' });
  const judgeM = useMutation({
    mutationFn: () => evaluationApi.answerQuality(judgeForm),
  });

  const cards = useMemo(() => {
    const r = reportQ.data ?? {};
    return [
      { label: t('evaluation.avgMrr'), value: fmt(r.avgMrr ?? r.mrr) },
      { label: t('evaluation.avgNdcg'), value: fmt(r.avgNdcg ?? r.ndcg) },
      { label: t('evaluation.avgHitRate'), value: fmt(r.avgHitRate ?? r.hitRate) },
      { label: t('evaluation.avgPrecision'), value: fmt(r.avgPrecision ?? r.precisionAtK) },
      { label: t('evaluation.total'), value: String(r.totalEvaluations ?? r.total ?? '—') },
    ];
  }, [reportQ.data, t]);

  return (
    <div>
      <h1 className="page-title">{t('evaluation.title')}</h1>

      <div className={styles.tabs} role="tablist">
        {(
          [
            ['report', t('evaluation.tabReport')],
            ['history', t('evaluation.tabHistory')],
            ['feedback', t('evaluation.tabFeedback')],
            ['judge', t('evaluation.tabJudge')],
          ] as const
        ).map(([id, label]) => (
          <button
            key={id}
            type="button"
            role="tab"
            className={tab === id ? styles.tabActive : styles.tab}
            onClick={() => setTab(id)}
          >
            {label}
          </button>
        ))}
      </div>

      {tab === 'report' && (
        <section className={styles.section}>
          {reportQ.isPending ? (
            <div className={styles.muted}>{t('common.loading')}</div>
          ) : (
            <div className={styles.cards}>
              {cards.map(c => (
                <div key={c.label} className={styles.card}>
                  <div className={styles.cardLabel}>{c.label}</div>
                  <div className={styles.cardValue}>{c.value}</div>
                </div>
              ))}
            </div>
          )}

          {feedbackStatsQ.data && (
            <div className={styles.subSection}>
              <h2>{t('evaluation.feedbackStats')}</h2>
              <pre className={styles.pre}>{JSON.stringify(feedbackStatsQ.data, null, 2)}</pre>
            </div>
          )}

          <div className={styles.subSection}>
            <h2>{t('evaluation.manualEvaluate')}</h2>
            <div className={styles.form}>
              <label>
                {t('evaluation.query')}
                <input
                  value={evalForm.query}
                  onChange={e => setEvalForm({ ...evalForm, query: e.target.value })}
                />
              </label>
              <label>
                {t('evaluation.retrievedIds')}
                <input
                  value={evalForm.retrieved}
                  onChange={e => setEvalForm({ ...evalForm, retrieved: e.target.value })}
                  placeholder="doc1, doc2"
                />
              </label>
              <label>
                {t('evaluation.relevantIds')}
                <input
                  value={evalForm.relevant}
                  onChange={e => setEvalForm({ ...evalForm, relevant: e.target.value })}
                  placeholder="doc1"
                />
              </label>
              <button
                type="button"
                className={styles.primaryBtn}
                disabled={evaluateM.isPending || !evalForm.query.trim()}
                onClick={() => evaluateM.mutate()}
              >
                {evaluateM.isPending ? t('common.loading') : t('evaluation.runEvaluate')}
              </button>
              {evaluateM.isSuccess && (
                <pre className={styles.pre}>{JSON.stringify(evaluateM.data?.data, null, 2)}</pre>
              )}
              {evaluateM.isError && (
                <div className={styles.error}>{t('evaluation.evaluateFailed')}</div>
              )}
            </div>
          </div>
        </section>
      )}

      {tab === 'history' && (
        <section className={styles.section}>
          {historyQ.isPending ? (
            <div className={styles.muted}>{t('common.loading')}</div>
          ) : (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>{t('evaluation.query')}</th>
                    <th>MRR</th>
                    <th>nDCG</th>
                    <th>Hit</th>
                    <th>{t('evaluation.time')}</th>
                  </tr>
                </thead>
                <tbody>
                  {(historyQ.data ?? []).map((row, i) => (
                    <tr key={row.id ?? i}>
                      <td>{row.id ?? '—'}</td>
                      <td className={styles.ellipsis}>{row.query ?? '—'}</td>
                      <td>{fmt(row.mrr)}</td>
                      <td>{fmt(row.ndcg)}</td>
                      <td>{fmt(row.hitRate)}</td>
                      <td>{row.createdAt ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {(historyQ.data ?? []).length === 0 && (
                <div className={styles.muted}>{t('evaluation.emptyHistory')}</div>
              )}
            </div>
          )}
        </section>
      )}

      {tab === 'feedback' && (
        <section className={styles.section}>
          {feedbackStatsQ.data && (
            <pre className={styles.pre}>{JSON.stringify(feedbackStatsQ.data, null, 2)}</pre>
          )}
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>ID</th>
                  <th>{t('evaluation.query')}</th>
                  <th>{t('evaluation.feedbackType')}</th>
                  <th>{t('evaluation.time')}</th>
                </tr>
              </thead>
              <tbody>
                {(feedbackHistoryQ.data ?? []).map((row, i) => (
                  <tr key={row.id ?? i}>
                    <td>{row.id ?? '—'}</td>
                    <td className={styles.ellipsis}>{row.query ?? '—'}</td>
                    <td>{row.feedbackType ?? '—'}</td>
                    <td>{row.createdAt ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {tab === 'judge' && (
        <section className={styles.section}>
          <div className={styles.form}>
            <label>
              {t('evaluation.query')}
              <textarea
                rows={2}
                value={judgeForm.query}
                onChange={e => setJudgeForm({ ...judgeForm, query: e.target.value })}
              />
            </label>
            <label>
              {t('evaluation.context')}
              <textarea
                rows={4}
                value={judgeForm.context}
                onChange={e => setJudgeForm({ ...judgeForm, context: e.target.value })}
              />
            </label>
            <label>
              {t('evaluation.answer')}
              <textarea
                rows={4}
                value={judgeForm.answer}
                onChange={e => setJudgeForm({ ...judgeForm, answer: e.target.value })}
              />
            </label>
            <button
              type="button"
              className={styles.primaryBtn}
              disabled={judgeM.isPending || !judgeForm.query.trim()}
              onClick={() => judgeM.mutate()}
            >
              {judgeM.isPending ? t('common.loading') : t('evaluation.runJudge')}
            </button>
            {judgeM.isSuccess && (
              <pre className={styles.pre}>{JSON.stringify(judgeM.data?.data, null, 2)}</pre>
            )}
            {judgeM.isError && <div className={styles.error}>{t('evaluation.judgeFailed')}</div>}
          </div>
        </section>
      )}
    </div>
  );
}
