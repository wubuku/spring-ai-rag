import { useState } from 'react';
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

  if (!data) {
    return <div className={styles.loading}>Loading...</div>;
  }

  // Prepare chart data for main metrics
  const mainMetricsData = [
    { name: 'Retrievals', value: data.totalRetrievals ?? 0 },
    { name: 'LLM Calls', value: data.totalLlmCalls ?? 0 },
    { name: 'Tokens', value: data.totalLlmTokens ?? 0 },
  ];

  // Latency data
  const latencyData = [
    { name: 'Avg Latency', value: data.avgRetrievalLatencyMs ?? 0 },
  ];

  // Cache hit rate (as percentage)
  const cacheData = [
    { name: 'Cache Hit Rate', value: Math.round((data.cacheHitRate ?? 0) * 100) },
  ];

  // Model metrics comparison
  const modelData =
    data.modelMetrics?.map(m => ({
      name: m.provider,
      calls: m.totalCalls,
      tokens: m.totalTokens,
      latency: m.avgLatencyMs,
    })) ?? [];

  const isDark =
    document.documentElement.getAttribute('data-theme') === 'dark';

  const axisStyle = {
    fill: isDark ? '#9ca3af' : '#6b7280',
    fontSize: 12,
  };

  const gridStyle = {
    stroke: isDark ? '#2d2d2d' : '#e5e7eb',
  };

  return (
    <div className={styles.container}>
      {/* Chart Type Toggle */}
      <div className={styles.toggle}>
        <button
          className={chartType === 'bar' ? styles.active : ''}
          onClick={() => setChartType('bar')}
        >
          Bar
        </button>
        <button
          className={chartType === 'line' ? styles.active : ''}
          onClick={() => setChartType('line')}
        >
          Line
        </button>
      </div>

      {/* Main Metrics Chart */}
      <div className={styles.chartSection}>
        <h3 className={styles.chartTitle}>Call Volume</h3>
        <ResponsiveContainer width="100%" height={250}>
          {chartType === 'bar' ? (
            <BarChart data={mainMetricsData}>
              <CartesianGrid {...gridStyle} />
              <XAxis dataKey="name" {...axisStyle} />
              <YAxis {...axisStyle} />
              <Tooltip
                contentStyle={{
                  backgroundColor: isDark ? '#1a1a1a' : '#fff',
                  border: `1px solid ${isDark ? '#2d2d2d' : '#e5e7eb'}`,
                  borderRadius: 8,
                }}
              />
              <Bar dataKey="value" fill="#3b82f6" radius={[4, 4, 0, 0]} />
            </BarChart>
          ) : (
            <LineChart data={mainMetricsData}>
              <CartesianGrid {...gridStyle} />
              <XAxis dataKey="name" {...axisStyle} />
              <YAxis {...axisStyle} />
              <Tooltip
                contentStyle={{
                  backgroundColor: isDark ? '#1a1a1a' : '#fff',
                  border: `1px solid ${isDark ? '#2d2d2d' : '#e5e7eb'}`,
                  borderRadius: 8,
                }}
              />
              <Line type="monotone" dataKey="value" stroke="#3b82f6" strokeWidth={2} dot={{ r: 4 }} />
            </LineChart>
          )}
        </ResponsiveContainer>
      </div>

      {/* Latency Chart */}
      <div className={styles.chartSection}>
        <h3 className={styles.chartTitle}>Avg Retrieval Latency (ms)</h3>
        <ResponsiveContainer width="100%" height={200}>
          <BarChart data={latencyData}>
            <CartesianGrid {...gridStyle} />
            <XAxis dataKey="name" {...axisStyle} />
            <YAxis {...axisStyle} />
            <Tooltip
              contentStyle={{
                backgroundColor: isDark ? '#1a1a1a' : '#fff',
                border: `1px solid ${isDark ? '#2d2d2d' : '#e5e7eb'}`,
                borderRadius: 8,
              }}
            />
            <Bar dataKey="value" fill="#f59e0b" radius={[4, 4, 0, 0]} />
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
              contentStyle={{
                backgroundColor: isDark ? '#1a1a1a' : '#fff',
                border: `1px solid ${isDark ? '#2d2d2d' : '#e5e7eb'}`,
                borderRadius: 8,
              }}
              formatter={(value) => [`${value}%`, 'Cache Hit Rate']}
            />
            <Bar dataKey="value" fill="#22c55e" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>

      {/* Model Comparison */}
      {modelData.length > 0 && (
        <div className={styles.chartSection}>
          <h3 className={styles.chartTitle}>Model Comparison</h3>
          <ResponsiveContainer width="100%" height={250}>
            <BarChart data={modelData}>
              <CartesianGrid {...gridStyle} />
              <XAxis dataKey="name" {...axisStyle} />
              <YAxis {...axisStyle} />
              <Tooltip
                contentStyle={{
                  backgroundColor: isDark ? '#1a1a1a' : '#fff',
                  border: `1px solid ${isDark ? '#2d2d2d' : '#e5e7eb'}`,
                  borderRadius: 8,
                }}
              />
              <Bar dataKey="calls" fill="#3b82f6" name="Calls" radius={[4, 4, 0, 0]} />
              <Bar dataKey="tokens" fill="#22c55e" name="Tokens" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}
