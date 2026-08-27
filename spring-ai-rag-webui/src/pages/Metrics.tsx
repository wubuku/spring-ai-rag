import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { metricsApi } from '../api/metrics';
import { MetricsCharts } from '../components/MetricsCharts';
import type {
  LlmUsageResponse,
  LlmUsageTotals,
  UsageNumericValue,
} from '../types/api';
import styles from './Metrics.module.css';

function formatInteger(value: UsageNumericValue | undefined): string {
  if (value === undefined || value === null) return '0';
  const text = String(value);
  if (/^\d+$/.test(text)) {
    try {
      return new Intl.NumberFormat().format(BigInt(text));
    } catch {
      return text;
    }
  }
  const numeric = Number(value);
  return Number.isFinite(numeric) ? new Intl.NumberFormat().format(numeric) : text;
}

function formatCost(value: UsageNumericValue): string {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return String(value);
  return new Intl.NumberFormat(undefined, {
    minimumFractionDigits: 0,
    maximumFractionDigits: 8,
  }).format(numeric);
}

function UsageTable({
  label,
  rows,
}: {
  label: string;
  rows: Array<{ key: string; totals: LlmUsageTotals }>;
}) {
  const { t } = useTranslation();
  if (rows.length === 0) return null;

  return (
    <div className={styles.tableWrap}>
      <table className={styles.table} aria-label={label}>
        <thead>
          <tr>
            <th>{label}</th>
            <th>{t('metrics.invocations')}</th>
            <th>{t('metrics.tokens')}</th>
            <th>{t('metrics.succeeded')}</th>
            <th>{t('metrics.failed')}</th>
            <th>{t('metrics.cancelled')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(row => (
            <tr key={row.key}>
              <th scope="row">{row.key}</th>
              <td>{formatInteger(row.totals.invocationCount)}</td>
              <td>{formatInteger(row.totals.totalTokens)}</td>
              <td>{formatInteger(row.totals.succeededCount)}</td>
              <td>{formatInteger(row.totals.failedCount)}</td>
              <td>{formatInteger(row.totals.cancelledCount)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function DurableUsage({ usage }: { usage: LlmUsageResponse }) {
  const { t } = useTranslation();
  const isEmpty = usage.totals.invocationCount === 0;

  return (
    <section className={styles.usageSection} aria-labelledby="durable-usage-title">
      <div className={styles.sectionHeader}>
        <div>
          <h2 id="durable-usage-title">{t('metrics.durableUsage')}</h2>
          <p className={styles.range}>
            {t('metrics.utcRange', { from: usage.from, to: usage.to })}
          </p>
        </div>
        <span
          className={styles.recordingStatus}
          data-enabled={usage.recordingEnabled}
        >
          {usage.recordingEnabled
            ? t('metrics.recordingActive')
            : t('metrics.recordingPaused')}
        </span>
      </div>

      {usage.localLostEventsSinceStart > 0 && (
        <div className={styles.warning} role="status">
          {t('metrics.lostEvents', {
            count: usage.localLostEventsSinceStart,
          })}
        </div>
      )}

      {isEmpty ? (
        <div className={styles.empty}>{t('metrics.noDurableUsage')}</div>
      ) : (
        <>
          <div className={styles.summaryGrid}>
            {[
              ['logicalExecutions', usage.totals.logicalExecutionCount],
              ['invocations', usage.totals.invocationCount],
              ['tokens', usage.totals.totalTokens],
              ['failed', usage.totals.failedCount],
            ].map(([key, value]) => (
              <div key={String(key)} className={styles.summaryItem}>
                <span>{t(`metrics.${key}`)}</span>
                <strong>{formatInteger(value as UsageNumericValue)}</strong>
              </div>
            ))}
          </div>

          <div className={styles.availability}>
            <span>
              {t('metrics.usageMissing')}: {formatInteger(usage.totals.usageUnavailableCount)}
            </span>
            <span>
              {t('metrics.pricingMissing')}: {formatInteger(usage.totals.pricingUnavailableCount)}
            </span>
            <span>
              {t('metrics.costMissing')}: {formatInteger(usage.totals.costUnavailableCount)}
            </span>
          </div>

          <div className={styles.costs} aria-label={t('metrics.configuredCosts')}>
            <h3>{t('metrics.configuredCosts')}</h3>
            {usage.costs.length === 0 ? (
              <p className={styles.muted}>{t('metrics.noCostData')}</p>
            ) : (
              <ul>
                {usage.costs.map(cost => (
                  <li key={cost.unit}>
                    <strong>{formatCost(cost.configuredCost)}</strong>
                    <span>{cost.unit}</span>
                    <small>
                      {t('metrics.costCoverage', {
                        available: cost.costAvailableCount,
                        total: cost.invocationCount,
                      })}
                    </small>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className={styles.breakdowns}>
            <UsageTable
              label={t('metrics.models')}
              rows={usage.byModel.map(item => ({
                key: item.modelRef,
                totals: item.totals,
              }))}
            />
            <UsageTable
              label={t('metrics.purposes')}
              rows={usage.byPurpose.map(item => ({
                key: item.purpose,
                totals: item.totals,
              }))}
            />
            <UsageTable
              label={t('metrics.modes')}
              rows={usage.byMode.map(item => ({
                key: item.mode,
                totals: item.totals,
              }))}
            />
            <UsageTable
              label={t('metrics.utcDay')}
              rows={usage.byDay.map(item => ({
                key: item.day,
                totals: item.totals,
              }))}
            />
          </div>
        </>
      )}

      <p className={styles.disclaimer}>{t('metrics.costDisclaimer')}</p>
    </section>
  );
}

export function Metrics() {
  const { t } = useTranslation();

  const metricsQuery = useQuery({
    queryKey: ['metrics'],
    queryFn: () => metricsApi.get(),
    refetchInterval: 30_000,
  });
  const usageQuery = useQuery({
    queryKey: ['llm-usage'],
    queryFn: () => metricsApi.usage(),
    refetchInterval: 30_000,
  });

  return (
    <div>
      <h1 className="page-title">{t('metrics.title')}</h1>
      {metricsQuery.isPending ? (
        <div className={styles.loading}>{t('common.loading')}</div>
      ) : metricsQuery.data?.data ? (
        <>
          <MetricsCharts data={metricsQuery.data.data} />
          <details className={styles.raw}>
            <summary>Raw JSON</summary>
            <pre className={styles.pre}>
              {JSON.stringify(metricsQuery.data.data, null, 2)}
            </pre>
          </details>
        </>
      ) : (
        <div className={styles.empty}>{t('metrics.noMetrics')}</div>
      )}

      {usageQuery.isPending ? (
        <div className={styles.usageLoading} role="status">
          {t('metrics.loadingDurableUsage')}
        </div>
      ) : usageQuery.isError ? (
        <div className={styles.error} role="alert">
          {t('metrics.usageLoadFailed')}
        </div>
      ) : usageQuery.data?.data ? (
        <DurableUsage usage={usageQuery.data.data} />
      ) : null}
    </div>
  );
}
