import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { evaluationApi } from '../api/evaluation';
import { Card } from '../components/Card';
import styles from './Evaluation.module.css';
import { PageHeader, Tabs, tabDomIds } from '../components/ui';

type Tab = 'report' | 'history' | 'feedback' | 'judge' | 'suites' | 'runs' | 'citations';

function fmt(n: unknown): string {
  if (typeof n === 'number' && Number.isFinite(n)) {
    return n.toFixed(3);
  }
  return '—';
}

const EVALUATION_TABS = [
  ['report', 'evaluation.tabReport'],
  ['history', 'evaluation.tabHistory'],
  ['feedback', 'evaluation.tabFeedback'],
  ['judge', 'evaluation.tabJudge'],
  ['suites', 'evaluation.tabSuites'],
  ['runs', 'evaluation.tabRuns'],
  ['citations', 'evaluation.tabCitations'],
] as const;

export function Evaluation() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const tabParam = searchParams.get('tab');
  const tab: Tab =
    tabParam === 'history' ||
    tabParam === 'feedback' ||
    tabParam === 'judge' ||
    tabParam === 'suites' ||
    tabParam === 'runs' ||
    tabParam === 'citations'
      ? tabParam
      : 'report';

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
      <PageHeader title={t('evaluation.title')} />

      <Tabs
        idPrefix="evaluation-tabs"
        ariaLabel={t('evaluation.title')}
        items={EVALUATION_TABS.map(([id, labelKey]) => ({ id, label: t(labelKey) }))}
        activeId={tab}
        onChange={next => setSearchParams(next === 'report' ? {} : { tab: next })}
      />

      <div
        role="tabpanel"
        id={tabDomIds('evaluation-tabs', tab).panelId}
        aria-labelledby={tabDomIds('evaluation-tabs', tab).tabId}
        tabIndex={0}
      >
        {tab === 'report' && (
          <section className={styles.section}>
            {reportQ.isPending ? (
              <div className={styles.muted}>{t('common.loading')}</div>
            ) : reportQ.isError ? (
              // 之前这里直接落进 else：用 data ?? {} 渲染出一张全是 — 的
              // "正常"报告，看起来像真数据。失败必须自己说出来。
              <div className={styles.error} role="alert">
                {t('evaluation.reportLoadFailed')}
              </div>
            ) : (
              <div className={styles.cards}>
                {cards.map(c => (
                  <Card key={c.label}>
                    <div className={styles.cardLabel}>{c.label}</div>
                    <div className={styles.cardValue}>{c.value}</div>
                  </Card>
                ))}
              </div>
            )}

            {feedbackStatsQ.isError && (
              <div className={styles.error} role="alert">
                {t('evaluation.feedbackStatsLoadFailed')}
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
            ) : historyQ.isError ? (
              // 失败时原来会显示"暂无历史记录"，把请求失败说成了没有数据。
              <div className={styles.error} role="alert">
                {t('evaluation.historyLoadFailed')}
              </div>
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
            {feedbackStatsQ.isError && (
              <div className={styles.error} role="alert">
                {t('evaluation.feedbackStatsLoadFailed')}
              </div>
            )}
            {feedbackStatsQ.data && (
              <pre className={styles.pre}>{JSON.stringify(feedbackStatsQ.data, null, 2)}</pre>
            )}
            {feedbackHistoryQ.isError && (
              <div className={styles.error} role="alert">
                {t('evaluation.feedbackHistoryLoadFailed')}
              </div>
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

        {tab === 'suites' && <SuitesPanel />}
        {tab === 'runs' && <RunsPanel />}
        {tab === 'citations' && <CitationsPanel />}
      </div>
    </div>
  );
}

function SuitesPanel() {
  const { t } = useTranslation();
  const [suiteKey, setSuiteKey] = useState('');
  const [name, setName] = useState('');
  const [definition, setDefinition] = useState('{"cases":[]}');
  const suitesQ = useQuery({
    queryKey: ['evaluation-suites'],
    queryFn: async () => (await evaluationApi.listSuites()).data,
  });
  const createM = useMutation({
    mutationFn: () => evaluationApi.createSuite({ suiteKey, name }),
  });
  const versionM = useMutation({
    mutationFn: () => evaluationApi.createVersion(suiteKey, JSON.parse(definition)),
  });
  return (
    <section className={styles.section} aria-label={t('evaluation.tabSuites')}>
      {suitesQ.isError && (
        <div className={styles.error} role="alert">
          {t('evaluation.suitesFailed')}
        </div>
      )}
      <div className={styles.muted}>{t('evaluation.suitesHint')}</div>
      <ul>
        {(Array.isArray(suitesQ.data) ? suitesQ.data : []).map(suite => (
          <li key={suite.id}>
            {suite.suiteKey} — {suite.name}
          </li>
        ))}
      </ul>
      <div className={styles.form}>
        <label>
          {t('evaluation.suiteKey')}
          <input value={suiteKey} onChange={e => setSuiteKey(e.target.value)} />
        </label>
        <label>
          {t('evaluation.suiteName')}
          <input value={name} onChange={e => setName(e.target.value)} />
        </label>
        <button
          type="button"
          className={styles.primaryBtn}
          onClick={() => createM.mutate()}
          disabled={createM.isPending}
        >
          {t('evaluation.createSuite')}
        </button>
        {createM.isError && (
          <div className={styles.error} role="alert">
            {t('evaluation.createSuiteFailed')}
          </div>
        )}
        <label>
          {t('evaluation.definition')}
          <textarea rows={8} value={definition} onChange={e => setDefinition(e.target.value)} />
        </label>
        <button
          type="button"
          className={styles.primaryBtn}
          onClick={() => versionM.mutate()}
          disabled={versionM.isPending}
        >
          {t('evaluation.importVersion')}
        </button>
        {versionM.isError && (
          <div className={styles.error} role="alert">
            {t('evaluation.importVersionFailed')}
          </div>
        )}
      </div>
    </section>
  );
}

function RunsPanel() {
  const { t } = useTranslation();
  const [suiteKey, setSuiteKey] = useState('');
  const [runId, setRunId] = useState('');
  const startM = useMutation({
    mutationFn: () => evaluationApi.createRun({ suiteKey }),
  });
  const runQ = useQuery({
    queryKey: ['evaluation-run', runId],
    queryFn: async () => (await evaluationApi.getRun(runId)).data,
    enabled: runId.length > 0,
  });
  return (
    <section className={styles.section} aria-label={t('evaluation.tabRuns')}>
      <div className={styles.form}>
        <label>
          {t('evaluation.suiteKey')}
          <input value={suiteKey} onChange={e => setSuiteKey(e.target.value)} />
        </label>
        <button
          type="button"
          className={styles.primaryBtn}
          onClick={() => startM.mutate()}
          disabled={startM.isPending}
        >
          {t('evaluation.startRun')}
        </button>
        {startM.isError && (
          <div className={styles.error} role="alert">
            {t('evaluation.startRunFailed')}
          </div>
        )}
        {startM.data?.data?.id && (
          <div>
            {t('evaluation.runStatus')}: {startM.data.data.status}
          </div>
        )}
        <label>
          {t('evaluation.runId')}
          <input value={runId} onChange={e => setRunId(e.target.value)} />
        </label>
        {runQ.isError && (
          <div className={styles.error} role="alert">
            {t('evaluation.runLoadFailed')}
          </div>
        )}
        {runQ.data && <pre className={styles.pre}>{JSON.stringify(runQ.data, null, 2)}</pre>}
      </div>
    </section>
  );
}

function CitationsPanel() {
  const { t } = useTranslation();
  const tracesQ = useQuery({
    queryKey: ['retrieval-traces-citations'],
    queryFn: async () => (await evaluationApi.listCitationTraces()).data,
  });
  return (
    <section className={styles.section} aria-label={t('evaluation.tabCitations')}>
      <p className={styles.muted}>{t('evaluation.citationsHint')}</p>
      {tracesQ.isPending && <div className={styles.muted}>{t('common.loading')}</div>}
      {tracesQ.isError && (
        <div className={styles.error} role="alert">
          {t('evaluation.citationsFailed')}
        </div>
      )}
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>{t('evaluation.traceId')}</th>
              <th>{t('evaluation.citationStatus')}</th>
              <th>{t('evaluation.outcome')}</th>
            </tr>
          </thead>
          <tbody>
            {(tracesQ.data?.items ?? []).map(item => (
              <tr key={item.traceId}>
                <td>{item.traceId}</td>
                <td>{item.citationStatus ?? '—'}</td>
                <td>{item.outcomeCode ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
