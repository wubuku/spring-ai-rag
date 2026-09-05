import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { ABTest } from './ABTest';
import type { Experiment, ExperimentAnalysis } from '../api/abtest';

const mockUseQuery = vi.fn();
const mockUseMutation = vi.fn();
const mockInvalidate = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: (options: { queryKey: readonly unknown[] }) => mockUseQuery(options),
  useMutation: () => mockUseMutation(),
  useQueryClient: () => ({ invalidateQueries: mockInvalidate }),
}));

vi.mock('../components/Toast', () => ({
  useToast: () => ({ showToast: vi.fn() }),
}));

vi.mock('recharts', () => ({
  BarChart: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="bar-chart">{children}</div>
  ),
  Bar: () => <div data-testid="bar" />,
  XAxis: () => <div data-testid="x-axis" />,
  YAxis: () => <div data-testid="y-axis" />,
  CartesianGrid: () => <div data-testid="cartesian-grid" />,
  Tooltip: () => <div data-testid="tooltip" />,
  Legend: () => <div data-testid="legend" />,
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="responsive-container">{children}</div>
  ),
}));

const queryStore = new Map<string, unknown>();
const mutationSpies: Array<{ mutate: ReturnType<typeof vi.fn>; isPending: boolean }> = [];

function storeQuery(key: readonly unknown[], axiosData: unknown) {
  queryStore.set(JSON.stringify(key), { data: axiosData });
}

function makeExperiment(overrides: Partial<Experiment> = {}): Experiment {
  return {
    id: 5,
    experimentName: 'rerank-a-b',
    description: 'compare rerank',
    status: 'RUNNING',
    targetMetric: 'retrieval_precision',
    sampleCount: 420,
    createdAt: '2026-08-01T00:00:00Z',
    ...overrides,
  };
}

const controlStats = {
  variantName: 'control',
  sampleSize: 200,
  meanValue: 0.82,
  stdDeviation: 0.05,
  conversionRate: 0.5,
  confidenceInterval: [0.8, 0.84] as [number, number],
};

function renderAbTest(initialPath = '/abtest') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/abtest" element={<ABTest />} />
        <Route path="/abtest/:experimentId" element={<ABTest />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ABTest', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    queryStore.clear();
    mutationSpies.length = 0;
    mockUseQuery.mockImplementation((options: { queryKey: readonly unknown[] }) => {
      const key = JSON.stringify(options.queryKey);
      return {
        data: queryStore.get(key),
        isPending: !queryStore.has(key),
        error: null,
      };
    });
    mockUseMutation.mockImplementation(() => {
      const mutation = { mutate: vi.fn(), isPending: false };
      mutationSpies.push(mutation);
      return mutation;
    });
  });

  it('shows the loading state while the experiment list is pending', () => {
    renderAbTest();
    expect(screen.getByText('common.loading')).toBeInTheDocument();
  });

  it('shows the empty state when no experiments exist', () => {
    storeQuery(['abtest', 'experiments'], []);
    renderAbTest();
    expect(screen.getByText('abtest.noExperiments')).toBeInTheDocument();
  });

  it('lists experiments and opens the detail view from the row action', async () => {
    const user = userEvent.setup();
    storeQuery(['abtest', 'experiments'], [makeExperiment()]);
    storeQuery(['abtest', 'experiment', 5], makeExperiment());

    renderAbTest();
    expect(screen.getByText('rerank-a-b')).toBeInTheDocument();
    expect(screen.getByText('RUNNING')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'abtest.viewDetails' }));

    expect(screen.getByRole('heading', { name: 'rerank-a-b' })).toBeInTheDocument();
    // Detail creates start/pause/stop mutations in order; RUNNING exposes pause + stop.
    expect(screen.getByRole('button', { name: 'abtest.pause' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'abtest.stop' })).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'abtest.start' }),
    ).not.toBeInTheDocument();
  });

  it('routes status to the matching lifecycle actions', async () => {
    const user = userEvent.setup();
    storeQuery(['abtest', 'experiments'], [makeExperiment()]);
    storeQuery(
      ['abtest', 'experiment', 5],
      makeExperiment({ status: 'DRAFT' }),
    );

    renderAbTest('/abtest/5');
    const start = screen.getByRole('button', { name: 'abtest.start' });
    expect(screen.queryByRole('button', { name: 'abtest.stop' })).not.toBeInTheDocument();

    await user.click(start);
    expect(mutationSpies[0].mutate).toHaveBeenCalledTimes(1);
  });

  it('pauses a running experiment through its mutation', async () => {
    const user = userEvent.setup();
    storeQuery(['abtest', 'experiments'], [makeExperiment()]);
    storeQuery(['abtest', 'experiment', 5], makeExperiment({ status: 'RUNNING' }));

    renderAbTest('/abtest/5');
    await user.click(screen.getByRole('button', { name: 'abtest.pause' }));

    expect(mutationSpies[1].mutate).toHaveBeenCalledTimes(1);
  });

  it('renders analysis results for a completed experiment', () => {
    const analysis: ExperimentAnalysis = {
      experimentId: 5,
      status: 'COMPLETED',
      variantStats: {
        control: controlStats,
        variant_b: {
          variantName: 'variant_b',
          sampleSize: 220,
          meanValue: 0.9,
          stdDeviation: 0.04,
          conversionRate: 0.62,
          confidenceInterval: [0.88, 0.92],
        },
      },
      winner: 'variant_b',
      confidenceLevel: 0.95,
      isSignificant: true,
      recommendation: 'Ship variant B',
      analyzedAt: '2026-08-02T00:00:00Z',
    };
    storeQuery(
      ['abtest', 'experiments'],
      [makeExperiment({ status: 'COMPLETED' })],
    );
    storeQuery(
      ['abtest', 'experiment', 5],
      makeExperiment({ status: 'COMPLETED' }),
    );
    storeQuery(['abtest', 'analysis', 5], analysis);

    renderAbTest('/abtest/5');

    expect(
      screen.getByText(/abtest\.statisticallySignificant/),
    ).toHaveTextContent('Ship variant B');
    expect(screen.getByTestId('responsive-container')).toBeInTheDocument();
    // The samples label appears in both the stats grid and the analysis table head.
    expect(screen.getAllByText('abtest.samples').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('variant_b')).toBeInTheDocument();
    expect(screen.getByText('95.0%')).toBeInTheDocument();
  });

  it('creates an experiment with normalized traffic split from the modal form', async () => {
    const user = userEvent.setup();
    storeQuery(['abtest', 'experiments'], []);

    renderAbTest();
    await user.click(
      screen.getByRole('button', { name: 'abtest.createExperiment' }),
    );

    const dialog = screen.getByRole('dialog', { name: 'abtest.createExperiment' });
    await user.type(
      within(dialog).getByPlaceholderText('abtest.namePlaceholder'),
      '  my-experiment  ',
    );
    fireEvent.submit(dialog.querySelector('form')!);

    // Typing re-renders the modal, so the live mutation instance is the latest spy.
    expect(mutationSpies.at(-1)!.mutate).toHaveBeenCalledWith(
      expect.objectContaining({
        experimentName: 'my-experiment',
        trafficSplit: { control: 0.5, variant_b: 0.5 },
        minSampleSize: 100,
      }),
    );
  });

  it('associates every create-form control with its label', async () => {
    const user = userEvent.setup();
    storeQuery(['abtest', 'experiments'], []);

    renderAbTest();
    await user.click(
      screen.getByRole('button', { name: 'abtest.createExperiment' }),
    );

    for (const label of [
      'abtest.name',
      'abtest.description',
      'abtest.targetMetric',
      `${'abtest.variant'} A`,
      `${'abtest.traffic'}%`,
    ]) {
      // Labels appear twice for variant/traffic (A and B rows).
      expect(screen.getAllByLabelText(label).length).toBeGreaterThanOrEqual(1);
    }
    expect(screen.getAllByLabelText('abtest.variant A')).toHaveLength(1);
    expect(screen.getAllByLabelText('abtest.variant B')).toHaveLength(1);
  });

  it('does not submit the create form when the name is blank', async () => {
    const user = userEvent.setup();
    storeQuery(['abtest', 'experiments'], []);

    renderAbTest();
    await user.click(
      screen.getByRole('button', { name: 'abtest.createExperiment' }),
    );
    const dialog = screen.getByRole('dialog', { name: 'abtest.createExperiment' });
    // Blank names are rejected by the component guard before the mutation runs.
    fireEvent.submit(dialog.querySelector('form')!);

    expect(mutationSpies.at(-1)!.mutate).not.toHaveBeenCalled();
  });

  it('sends the full create payload with metric, description and custom variants', async () => {
    const user = userEvent.setup();
    storeQuery(['abtest', 'experiments'], []);

    renderAbTest();
    await user.click(
      screen.getByRole('button', { name: 'abtest.createExperiment' }),
    );

    const dialog = screen.getByRole('dialog', { name: 'abtest.createExperiment' });
    await user.type(
      within(dialog).getByPlaceholderText('abtest.namePlaceholder'),
      'rerank-lift',
    );
    await user.type(
      within(dialog).getByLabelText('abtest.description'),
      'measure rerank lift',
    );
    await user.selectOptions(
      within(dialog).getByLabelText('abtest.targetMetric'),
      'user_satisfaction',
    );
    const variantA = within(dialog).getByLabelText('abtest.variant A') as HTMLInputElement;
    await user.clear(variantA);
    await user.type(variantA, 'baseline');

    // A/B 两个 split 输入共用同一 label 文本，按文档顺序区分。
    const splits = within(dialog).getAllByLabelText('abtest.traffic%') as HTMLInputElement[];
    await user.clear(splits[0]);
    await user.type(splits[0], '70');
    await user.clear(splits[1]);
    await user.type(splits[1], '30');

    fireEvent.submit(dialog.querySelector('form')!);

    // Typing re-renders the modal, so the live mutation instance is the latest spy.
    expect(mutationSpies.at(-1)!.mutate).toHaveBeenCalledWith({
      experimentName: 'rerank-lift',
      description: 'measure rerank lift',
      targetMetric: 'user_satisfaction',
      trafficSplit: { 'baseline': 0.7, variant_b: 0.3 },
      minSampleSize: 100,
    });
  });
});
