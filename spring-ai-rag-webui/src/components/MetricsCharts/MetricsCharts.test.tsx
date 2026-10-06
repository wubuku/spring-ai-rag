import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MetricsCharts } from './MetricsCharts';

// Mock recharts — 组件内部 state 决定渲染 BarChart 还是 LineChart。
vi.mock('recharts', () => ({
  BarChart: ({ children, data }: { children: React.ReactNode; data: unknown[] }) => (
    <div data-testid="bar-chart" data-length={data?.length ?? 0}>{children}</div>
  ),
  Bar: () => <div data-testid="bar" />,
  LineChart: ({ children, data }: { children: React.ReactNode; data: unknown[] }) => (
    <div data-testid="line-chart" data-length={data?.length ?? 0}>{children}</div>
  ),
  Line: () => <div data-testid="line" />,
  XAxis: () => <div data-testid="x-axis" />,
  YAxis: () => <div data-testid="y-axis" />,
  CartesianGrid: () => <div data-testid="cartesian-grid" />,
  Tooltip: () => <div data-testid="tooltip" />,
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="responsive-container">{children}</div>
  ),
}));

describe('MetricsCharts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  const mockData = {
    totalRetrievals: 1000,
    totalLlmCalls: 500,
    totalLlmTokens: 50000,
    avgRetrievalLatencyMs: 45,
    cacheHitRate: 0.75,
    activeConversations: 20,
    modelMetrics: [
      { provider: 'deepseek', totalCalls: 300, totalTokens: 30000, avgLatencyMs: 50 },
      { provider: 'openai', totalCalls: 200, totalTokens: 20000, avgLatencyMs: 40 },
    ],
  };

  it('renders loading state when data is null', () => {
    render(<MetricsCharts data={null} />);
    expect(screen.getByText('common.loading')).toBeInTheDocument();
  });

  it('renders loading state when data is undefined', () => {
    // @ts-expect-error — testing runtime behavior with undefined
    render(<MetricsCharts data={undefined} />);
    expect(screen.getByText('common.loading')).toBeInTheDocument();
  });

  it('renders bar chart with all sections when data is provided', () => {
    render(<MetricsCharts data={mockData} />);

    // Chart type toggle buttons
    expect(screen.getByRole('button', { name: 'metrics.bar' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'metrics.line' })).toBeInTheDocument();

    // Section titles
    expect(screen.getByText('metrics.callVolume')).toBeInTheDocument();
    expect(screen.getByText('metrics.avgRetrievalLatency')).toBeInTheDocument();
    expect(screen.getByText('metrics.cacheHitRatePercent')).toBeInTheDocument();
    expect(screen.getByText('metrics.modelComparison')).toBeInTheDocument();

    // Responsive containers (one per chart section)
    const containers = screen.getAllByTestId('responsive-container');
    expect(containers.length).toBe(4);
  });

  it('renders Line button alongside Bar button', () => {
    render(<MetricsCharts data={mockData} />);
    expect(screen.getByRole('button', { name: 'metrics.line' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'metrics.bar' })).toBeInTheDocument();
  });

  it('renders bar chart by default (initial state)', () => {
    render(<MetricsCharts data={mockData} />);
    // Initially renders bar charts
    const barCharts = screen.getAllByTestId('bar-chart');
    expect(barCharts.length).toBeGreaterThan(0);
    // No line charts initially
    expect(screen.queryAllByTestId('line-chart').length).toBe(0);
  });

  it('does not draw a zero for a metric the server never reported', () => {
    // Batch 931. This test used to be called "renders with default/zero values
    // when data fields are missing" and asserted three charts full of zeros. A
    // zero on the 0–100 cache axis reads as "0% hit rate", which is a claim
    // about cache behaviour rather than a missing measurement, and the two call
    // for opposite responses from whoever is reading the dashboard.
    render(<MetricsCharts data={{}} />);

    // Section headings stay: the reader should see what exists and does not.
    expect(screen.getByText('metrics.callVolume')).toBeInTheDocument();
    expect(screen.getByText('metrics.avgRetrievalLatency')).toBeInTheDocument();
    expect(screen.getByText('metrics.cacheHitRatePercent')).toBeInTheDocument();

    // No chart at all, because every value is absent.
    expect(screen.queryAllByTestId('responsive-container').length).toBe(0);
    expect(screen.queryAllByTestId('bar-chart').length).toBe(0);

    // And it says so, once per section plus the count.
    expect(screen.getAllByText('metrics.notReported').length).toBe(3);
    expect(screen.getByText('metrics.omittedCount')).toBeInTheDocument();
  });

  it('draws only the metrics the server did report', () => {
    render(<MetricsCharts data={{ totalRetrievals: 12, cacheHitRate: 0.5 }} />);

    const containers = screen.getAllByTestId('responsive-container');
    // Call volume and cache hit rate are drawn; latency is absent, so it is not.
    expect(containers.length).toBe(2);
    // Three omitted in total: llm calls, tokens and the average latency.
    expect(screen.getByText('metrics.omittedCount')).toBeInTheDocument();
    expect(screen.getAllByText('metrics.notReported').length).toBe(1);
  });

  it('hides Model Comparison section when modelMetrics is empty', () => {
    const dataWithoutModels = { ...mockData, modelMetrics: [] };

    render(<MetricsCharts data={dataWithoutModels} />);

    expect(screen.getByText('metrics.callVolume')).toBeInTheDocument();
    expect(screen.getByText('metrics.avgRetrievalLatency')).toBeInTheDocument();
    expect(screen.getByText('metrics.cacheHitRatePercent')).toBeInTheDocument();
    expect(screen.queryByText('metrics.modelComparison')).not.toBeInTheDocument();

    const containers = screen.getAllByTestId('responsive-container');
    expect(containers.length).toBe(3);
  });

  it('shows Model Comparison when modelMetrics has one provider', () => {
    const dataWithOneModel = {
      ...mockData,
      modelMetrics: [{ provider: 'deepseek', totalCalls: 300, totalTokens: 30000, avgLatencyMs: 50 }],
    };

    render(<MetricsCharts data={dataWithOneModel} />);

    expect(screen.getByText('metrics.modelComparison')).toBeInTheDocument();
    const containers = screen.getAllByTestId('responsive-container');
    expect(containers.length).toBe(4);
  });

  it('treats a key present with an undefined value the same as an absent one', () => {
    // Batch 931. This used to be "renders with missing optional fields using
    // nullish coalescing" and expected three charts of zeros. The input is kept
    // because it is a different shape from an absent key — a response can carry
    // `"totalRetrievals": null` — and both have to read as "not reported"
    // rather than "zero".
    const partialData = {
      totalRetrievals: undefined,
      totalLlmCalls: undefined,
      totalLlmTokens: undefined,
      avgRetrievalLatencyMs: undefined,
      cacheHitRate: undefined,
      modelMetrics: undefined,
    };

    render(<MetricsCharts data={partialData} />);

    expect(screen.getByText('metrics.callVolume')).toBeInTheDocument();
    // queryAllBy, not getAllBy: getAllBy throws when nothing matches, so it
    // cannot be used to assert that nothing is there.
    expect(screen.queryAllByTestId('responsive-container').length).toBe(0);
    expect(screen.getAllByText('metrics.notReported').length).toBe(3);
  });
  it('toggles between line and bar charts from the type buttons', async () => {
    const user = userEvent.setup();
    render(<MetricsCharts data={mockData} />);
    expect(screen.queryAllByTestId('line-chart').length).toBe(0);

    // 只有 Call Volume 图随 toggle 切换；Latency/Cache/Model 恒为柱状图。
    await user.click(screen.getByRole('button', { name: 'metrics.line' }));
    expect(screen.getAllByTestId('line-chart').length).toBe(1);
    expect(screen.getAllByTestId('bar-chart').length).toBe(3);

    await user.click(screen.getByRole('button', { name: 'metrics.bar' }));
    expect(screen.getAllByTestId('bar-chart').length).toBe(4);
    expect(screen.queryAllByTestId('line-chart').length).toBe(0);
  });
});
