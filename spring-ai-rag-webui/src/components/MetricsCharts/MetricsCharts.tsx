import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  LineChart,
  Line,
} from 'recharts';
import { useChartTheme } from '../../hooks/useChartTheme';
import styles from './MetricsCharts.module.css';

interface MetricsChartsProps {
  data: {
    totalRetrievals?: number;
    totalLlmCalls?: number;
    totalLlmTokens?: number;
    avgRetrievalLatencyMs?: number;
    cacheHitRate?: number;
    modelMetrics?: Array<{
      provider: string;
      totalCalls: number;
      totalTokens: number;
      avgLatencyMs: number;
    }>;
  } | null;
}

type ChartType = 'bar' | 'line';

export function MetricsCharts({ data }: MetricsChartsProps) {
  const [chartType, setChartType] = useState<ChartType>('bar');
  const palette = useChartTheme();
  const { t } = useTranslation();

  if (!data) {
    return <div className={styles.loading}>{t('common.loading')}</div>;
  }

  // Batch 931. Every metric used to read `?? 0`, so a response that simply did
  // not carry a field drew a bar at zero — and on the 0–100 cache axis that is
  // a green "0% hit rate", which is an operational claim rather than a missing
  // measurement. A dashboard is read by someone deciding whether to act, and
  // "no traffic" and "the metrics endpoint did not say" call for opposite
  // responses. Unreported metrics are left out and counted instead.
  const volumeMetrics = [
    { label: t('metrics.retrievals'), value: data.totalRetrievals },
    { label: t('metrics.llmCalls'), value: data.totalLlmCalls },
    { label: t('metrics.tokens'), value: data.totalLlmTokens },
  ];
  const reportedVolume = volumeMetrics.filter(
    (metric): metric is { label: string; value: number } =>
      typeof metric.value === 'number',
  );
  const omitted = volumeMetrics.length - reportedVolume.length
    + (typeof data.avgRetrievalLatencyMs === 'number' ? 0 : 1)
    + (typeof data.cacheHitRate === 'number' ? 0 : 1);
  const mainMetricsData = reportedVolume.map(metric => ({
    name: metric.label,
    value: metric.value,
  }));

  // Latency data
  const latencyData = typeof data.avgRetrievalLatencyMs === 'number'
    ? [{ name: t('metrics.avgLatency'), value: data.avgRetrievalLatencyMs }]
    : [];

  // Cache hit rate (as percentage)
  const cacheData = typeof data.cacheHitRate === 'number'
    ? [{ name: t('metrics.cacheHitRate'), value: Math.round(data.cacheHitRate * 100) }]
    : [];

  // Model metrics comparison
  const modelData =
    data.modelMetrics?.map(m => ({
      name: m.provider,
      calls: m.totalCalls,
      tokens: m.totalTokens,
      latency: m.avgLatencyMs,
    })) ?? [];

  const axisStyle = {
    fill: palette.axisText,
    fontSize: 12,
  };

  const gridStyle = {
    stroke: palette.gridStroke,
  };

  const tooltipStyle = {
    backgroundColor: palette.tooltipBackground,
    border: `1px solid ${palette.tooltipBorder}`,
    borderRadius: 8,
  };

  return (
    <div className={styles.container}>
      {omitted > 0 && (
        <p className={styles.unreported}>
          {t('metrics.omittedCount', { count: omitted })}
        </p>
      )}
      {/* Chart Type Toggle */}
      <div className={styles.toggle}>
        <button
          className={chartType === 'bar' ? styles.active : ''}
          onClick={() => setChartType('bar')}
        >
          {t('metrics.bar')}
        </button>
        <button
          className={chartType === 'line' ? styles.active : ''}
          onClick={() => setChartType('line')}
        >
          {t('metrics.line')}
        </button>
      </div>

      {/* Main Metrics Chart */}
      <div className={styles.chartSection}>
        <h3 className={styles.chartTitle}>{t('metrics.callVolume')}</h3>
        {mainMetricsData.length > 0 ? (
          <ResponsiveContainer width="100%" height={250}>
            {chartType === 'bar' ? (
              <BarChart data={mainMetricsData}>
                <CartesianGrid {...gridStyle} />
                <XAxis dataKey="name" {...axisStyle} />
                <YAxis {...axisStyle} />
                <Tooltip
                  contentStyle={tooltipStyle}
                />
                <Bar dataKey="value" fill={palette.primary} radius={[4, 4, 0, 0]} />
              </BarChart>
            ) : (
              <LineChart data={mainMetricsData}>
                <CartesianGrid {...gridStyle} />
                <XAxis dataKey="name" {...axisStyle} />
                <YAxis {...axisStyle} />
                <Tooltip
                  contentStyle={tooltipStyle}
                />
                <Line type="monotone" dataKey="value" stroke={palette.primary} strokeWidth={2} dot={{ r: 4 }} />
              </LineChart>
            )}
          </ResponsiveContainer>
        ) : (
          <p className={styles.unreported}>{t('metrics.notReported')}</p>
        )}
      </div>

      {/* Latency Chart */}
      <div className={styles.chartSection}>
        <h3 className={styles.chartTitle}>{t('metrics.avgRetrievalLatency')}</h3>
        {latencyData.length > 0 ? (
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={latencyData}>
              <CartesianGrid {...gridStyle} />
              <XAxis dataKey="name" {...axisStyle} />
              <YAxis {...axisStyle} />
              <Tooltip
                contentStyle={tooltipStyle}
              />
              <Bar dataKey="value" fill={palette.warning} radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        ) : (
          <p className={styles.unreported}>{t('metrics.notReported')}</p>
        )}
      </div>

      {/* Cache Hit Rate */}
      <div className={styles.chartSection}>
        <h3 className={styles.chartTitle}>{t('metrics.cacheHitRatePercent')}</h3>
        {cacheData.length > 0 ? (
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={cacheData}>
              <CartesianGrid {...gridStyle} />
              <XAxis dataKey="name" {...axisStyle} />
              <YAxis domain={[0, 100]} {...axisStyle} />
              <Tooltip
                contentStyle={tooltipStyle}
                formatter={(value) => [`${value}%`, t('metrics.cacheHitRate')]}
              />
              <Bar dataKey="value" fill={palette.success} radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        ) : (
          <p className={styles.unreported}>{t('metrics.notReported')}</p>
        )}
      </div>

      {/* Model Comparison */}
      {modelData.length > 0 && (
        <div className={styles.chartSection}>
          <h3 className={styles.chartTitle}>{t('metrics.modelComparison')}</h3>
          <ResponsiveContainer width="100%" height={250}>
            <BarChart data={modelData}>
              <CartesianGrid {...gridStyle} />
              <XAxis dataKey="name" {...axisStyle} />
              <YAxis {...axisStyle} />
              <Tooltip
                contentStyle={tooltipStyle}
              />
              <Bar dataKey="calls" fill={palette.primary} name={t('metrics.llmCalls')} radius={[4, 4, 0, 0]} />
              <Bar dataKey="tokens" fill={palette.success} name={t('metrics.tokens')} radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}
