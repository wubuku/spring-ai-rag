import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MetricsCharts } from './MetricsCharts';

// Mock recharts — use module-level state to track chart type for toggle tests
let mockChartType: 'bar' | 'line' = 'bar';

vi.mock('recharts', () => ({
  BarChart: ({ children, data }: { children: React.ReactNode; data: unknown[] }) =>
    mockChartType === 'bar' ? (
      <div data-testid="bar-chart" data-length={data?.length ?? 0}>{children}</div>
    ) : null,
  Bar: () => <div data-testid="bar" />,
  LineChart: ({ children, data }: { children: React.ReactNode; data: unknown[] }) =>
    mockChartType === 'line' ? (
      <div data-testid="line-chart" data-length={data?.length ?? 0}>{children}</div>
    ) : null,
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
    mockChartType = 'bar';
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
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('renders loading state when data is undefined', () => {
    // @ts-expect-error — testing runtime behavior with undefined
    render(<MetricsCharts data={undefined} />);
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('renders bar chart with all sections when data is provided', () => {
    render(<MetricsCharts data={mockData} />);

    // Chart type toggle buttons
    expect(screen.getByRole('button', { name: 'Bar' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Line' })).toBeInTheDocument();

    // Section titles
    expect(screen.getByText('Call Volume')).toBeInTheDocument();
    expect(screen.getByText('Avg Retrieval Latency (ms)')).toBeInTheDocument();
    expect(screen.getByText('Cache Hit Rate (%)')).toBeInTheDocument();
    expect(screen.getByText('Model Comparison')).toBeInTheDocument();

    // Responsive containers (one per chart section)
    const containers = screen.getAllByTestId('responsive-container');
    expect(containers.length).toBe(4);
  });

  it('renders Line button alongside Bar button', () => {
    render(<MetricsCharts data={mockData} />);
    expect(screen.getByRole('button', { name: 'Line' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Bar' })).toBeInTheDocument();
  });

  it('renders bar chart by default (initial state)', () => {
    render(<MetricsCharts data={mockData} />);
    // Initially renders bar charts
    const barCharts = screen.getAllByTestId('bar-chart');
    expect(barCharts.length).toBeGreaterThan(0);
    // No line charts initially
    expect(screen.queryAllByTestId('line-chart').length).toBe(0);
  });

  it('renders with default/zero values when data fields are missing', () => {
    const minimalData = {};

    render(<MetricsCharts data={minimalData} />);

    // Still renders chart sections with zero values
    expect(screen.getByText('Call Volume')).toBeInTheDocument();
    expect(screen.getByText('Avg Retrieval Latency (ms)')).toBeInTheDocument();
    expect(screen.getByText('Cache Hit Rate (%)')).toBeInTheDocument();
    expect(screen.getAllByTestId('responsive-container').length).toBe(3);
  });

  it('hides Model Comparison section when modelMetrics is empty', () => {
    const dataWithoutModels = { ...mockData, modelMetrics: [] };

    render(<MetricsCharts data={dataWithoutModels} />);

    expect(screen.getByText('Call Volume')).toBeInTheDocument();
    expect(screen.getByText('Avg Retrieval Latency (ms)')).toBeInTheDocument();
    expect(screen.getByText('Cache Hit Rate (%)')).toBeInTheDocument();
    expect(screen.queryByText('Model Comparison')).not.toBeInTheDocument();

    const containers = screen.getAllByTestId('responsive-container');
    expect(containers.length).toBe(3);
  });

  it('shows Model Comparison when modelMetrics has one provider', () => {
    const dataWithOneModel = {
      ...mockData,
      modelMetrics: [{ provider: 'deepseek', totalCalls: 300, totalTokens: 30000, avgLatencyMs: 50 }],
    };

    render(<MetricsCharts data={dataWithOneModel} />);

    expect(screen.getByText('Model Comparison')).toBeInTheDocument();
    const containers = screen.getAllByTestId('responsive-container');
    expect(containers.length).toBe(4);
  });

  it('renders with missing optional fields using nullish coalescing', () => {
    const partialData = {
      totalRetrievals: undefined,
      totalLlmCalls: undefined,
      totalLlmTokens: undefined,
      avgRetrievalLatencyMs: undefined,
      cacheHitRate: undefined,
      modelMetrics: undefined,
    };

    render(<MetricsCharts data={partialData} />);

    expect(screen.getByText('Call Volume')).toBeInTheDocument();
    expect(screen.getAllByTestId('responsive-container').length).toBe(3);
  });
});
