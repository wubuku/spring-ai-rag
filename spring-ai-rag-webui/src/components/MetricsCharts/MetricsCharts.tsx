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
    activeConversations?: number;
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

  // Prepare chart data for main metrics
  const mainMetricsData = [
    { name: t('metrics.retrievals'), value: data.totalRetrievals ?? 0 },
    { name: t('metrics.llmCalls'), value: data.totalLlmCalls ?? 0 },
    { name: t('metrics.tokens'), value: data.totalLlmTokens ?? 0 },
  ];

  // Latency data
  const latencyData = [
    { name: t('metrics.avgLatency'), value: data.avgRetrievalLatencyMs ?? 0 },
  ];

  // Cache hit rate (as percentage)
  const cacheData = [
    { name: t('metrics.cacheHitRate'), value: Math.round((data.cacheHitRate ?? 0) * 100) },
  ];

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
      </div>

      {/* Latency Chart */}
      <div className={styles.chartSection}>
        <h3 className={styles.chartTitle}>{t('metrics.avgRetrievalLatency')}</h3>
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
      </div>

      {/* Cache Hit Rate */}
      <div className={styles.chartSection}>
        <h3 className={styles.chartTitle}>Cache Hit Rate (%)</h3>
        <ResponsiveContainer width="100%" height={200}>
          <BarChart data={cacheData}>
            <CartesianGrid {...gridStyle} />
            <XAxis dataKey="name" {...axisStyle} />
            <YAxis domain={[0, 100]} {...axisStyle} />
            <Tooltip
              contentStyle={tooltipStyle}
              formatter={(value) => [`${value}%`, 'Cache Hit Rate']}
            />
            <Bar dataKey="value" fill={palette.success} radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
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
