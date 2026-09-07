import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ABTest } from './ABTest';
import type { Experiment } from '../api/abtest';

const mocks = vi.hoisted(() => ({
  listExperiments: vi.fn(),
  getExperiment: vi.fn(),
  createExperiment: vi.fn(),
  startExperiment: vi.fn(),
  pauseExperiment: vi.fn(),
  stopExperiment: vi.fn(),
  getAnalysis: vi.fn(),
  showToast: vi.fn(),
}));

vi.mock('../api/abtest', () => ({
  abtestApi: {
    listExperiments: mocks.listExperiments,
    getExperiment: mocks.getExperiment,
    createExperiment: mocks.createExperiment,
    startExperiment: mocks.startExperiment,
    pauseExperiment: mocks.pauseExperiment,
    stopExperiment: mocks.stopExperiment,
    getAnalysis: mocks.getAnalysis,
  },
}));

vi.mock('../components/Toast', () => ({
  useToast: () => ({ showToast: mocks.showToast }),
}));

vi.mock('recharts', () => ({
  BarChart: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="bar-chart">{children}</div>
  ),
  Bar: () => null,
  XAxis: () => null,
  YAxis: () => null,
  CartesianGrid: () => null,
  // 渲染期直接调用 formatter，覆盖组件里 tooltip 文案映射逻辑。
  Tooltip: ({ formatter }: { formatter?: (v: unknown, k: unknown) => unknown }) => (
    <div data-testid="tooltip">
      {formatter ? JSON.stringify(formatter(42, 'samples')) : ''}
    </div>
  ),
  Legend: () => null,
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="responsive-container">{children}</div>
  ),
}));

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

function renderAbTest(initialPath = '/abtest') {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/abtest" element={<ABTest />} />
          <Route path="/abtest/:experimentId" element={<ABTest />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { invalidateSpy };
}

function experimentRoute(id: number, overrides: Partial<Experiment> = {}) {
  mocks.listExperiments.mockResolvedValue({ data: [makeExperiment(overrides)] });
  mocks.getExperiment.mockResolvedValue({ data: makeExperiment({ id, ...overrides }) });
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('ABTest with real query/mutation wiring', () => {
  it('fetches the experiment list through the real query with size 100', async () => {
    mocks.listExperiments.mockResolvedValue({ data: [makeExperiment()] });

    renderAbTest();

    expect(mocks.listExperiments).toHaveBeenCalledWith({ size: 100 });
    expect(await screen.findByText('rerank-a-b')).toBeInTheDocument();
  });

  it('renders the detail loading state while the experiment query is pending', () => {
    experimentRoute(5);
    mocks.getExperiment.mockReturnValue(new Promise(() => {}));

    renderAbTest('/abtest/5');

    expect(screen.getByText('common.loading')).toBeInTheDocument();
  });

  it('starts a draft experiment and confirms with a success toast', async () => {
    const user = userEvent.setup();
    experimentRoute(5, { status: 'DRAFT' });
    mocks.startExperiment.mockResolvedValue({ data: makeExperiment({ status: 'RUNNING' }) });

    const { invalidateSpy } = renderAbTest('/abtest/5');
    await user.click(await screen.findByRole('button', { name: 'abtest.start' }));

    await waitFor(() => {
      expect(mocks.startExperiment).toHaveBeenCalledWith(5);
      expect(mocks.showToast).toHaveBeenCalledWith('abtest.started', 'success');
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['abtest'] });
    });
  });

  it('surfaces an error toast when starting fails', async () => {
    const user = userEvent.setup();
    experimentRoute(5, { status: 'DRAFT' });
    mocks.startExperiment.mockRejectedValue(new Error('conflict'));

    renderAbTest('/abtest/5');
    await user.click(await screen.findByRole('button', { name: 'abtest.start' }));

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalledWith('abtest.startError', 'error');
    });
  });

  it('pauses a running experiment and confirms with a success toast', async () => {
    const user = userEvent.setup();
    experimentRoute(5, { status: 'RUNNING' });
    mocks.pauseExperiment.mockResolvedValue({ data: makeExperiment({ status: 'PAUSED' }) });

    renderAbTest('/abtest/5');
    await user.click(await screen.findByRole('button', { name: 'abtest.pause' }));

    await waitFor(() => {
      expect(mocks.pauseExperiment).toHaveBeenCalledWith(5);
      expect(mocks.showToast).toHaveBeenCalledWith('abtest.paused', 'success');
    });
  });

  it('stops a running experiment and confirms with a success toast', async () => {
    const user = userEvent.setup();
    experimentRoute(5, { status: 'RUNNING' });
    mocks.stopExperiment.mockResolvedValue({ data: makeExperiment({ status: 'STOPPED' }) });

    renderAbTest('/abtest/5');
    await user.click(await screen.findByRole('button', { name: 'abtest.stop' }));

    await waitFor(() => {
      expect(mocks.stopExperiment).toHaveBeenCalledWith(5);
      expect(mocks.showToast).toHaveBeenCalledWith('abtest.stopped', 'success');
    });
  });

  it('surfaces error toasts for pause and stop failures', async () => {
    const user = userEvent.setup();
    experimentRoute(5, { status: 'RUNNING' });
    mocks.pauseExperiment.mockRejectedValue(new Error('pause failed'));
    mocks.stopExperiment.mockRejectedValue(new Error('stop failed'));

    renderAbTest('/abtest/5');
    await user.click(await screen.findByRole('button', { name: 'abtest.pause' }));
    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalledWith('abtest.pauseError', 'error');
    });

    await user.click(screen.getByRole('button', { name: 'abtest.stop' }));
    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalledWith('abtest.stopError', 'error');
    });
  });

  it('loads the analysis through the real query for a completed experiment', async () => {
    experimentRoute(5, { status: 'COMPLETED', winner: 'variant_b' });
    mocks.getAnalysis.mockResolvedValue({
      data: {
        experimentId: 5,
        status: 'COMPLETED',
        variantStats: {
          control: {
            variantName: 'control',
            sampleSize: 200,
            meanValue: 0.82,
            stdDeviation: 0.05,
            conversionRate: 0.5,
            confidenceInterval: [0.8, 0.84],
          },
          // 无转化率与置信区间的变体走占位符分支。
          variant_b: {
            variantName: 'variant_b',
            sampleSize: 180,
            meanValue: 0.78,
            stdDeviation: 0.06,
          },
        },
        winner: 'variant_b',
        confidenceLevel: 0.95,
        isSignificant: true,
        recommendation: 'Ship variant B',
        analyzedAt: '2026-08-02T00:00:00Z',
      },
    });

    renderAbTest('/abtest/5');

    await waitFor(() => {
      expect(mocks.getAnalysis).toHaveBeenCalledWith(5);
    });
    expect(
      await screen.findByText(/abtest\.statisticallySignificant/),
    ).toHaveTextContent('Ship variant B');
    // Tooltip mock 渲染期调用 formatter，确认 (value, key) 顺序映射。
    expect(screen.getByTestId('tooltip')).toHaveTextContent('[42,"samples"]');
    // 有置信区间的变体渲染区间，缺失的渲染占位符。
    expect(screen.getByText('[0.8000, 0.8400]')).toBeInTheDocument();
    const dashCells = screen.getAllByText('—');
    expect(dashCells.length).toBeGreaterThanOrEqual(2);
  });
});

describe('ABTest create modal with real create mutation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listExperiments.mockResolvedValue({ data: [] });
  });

  it('creates an experiment, closes the modal and toasts success', async () => {
    const user = userEvent.setup();
    mocks.createExperiment.mockResolvedValue({ data: makeExperiment() });

    renderAbTest();
    await user.click(
      await screen.findByRole('button', { name: 'abtest.createExperiment' }),
    );
    await user.type(
      screen.getByPlaceholderText('abtest.namePlaceholder'),
      'real-create',
    );
    fireEvent.submit(screen.getByRole('dialog', { name: 'abtest.createExperiment' }).querySelector('form')!);

    await waitFor(() => {
      expect(mocks.createExperiment).toHaveBeenCalledWith(
        expect.objectContaining({ experimentName: 'real-create' }),
      );
      expect(mocks.showToast).toHaveBeenCalledWith('abtest.created', 'success');
    });
    // onSuccess 关闭弹窗：dialog 从 DOM 中消失。
    await waitFor(() => {
      expect(
        screen.queryByRole('dialog', { name: 'abtest.createExperiment' }),
      ).not.toBeInTheDocument();
    });
  });

  it('keeps the modal open and toasts an error when creation fails', async () => {
    const user = userEvent.setup();
    mocks.createExperiment.mockRejectedValue(new Error('dup'));

    renderAbTest();
    await user.click(
      await screen.findByRole('button', { name: 'abtest.createExperiment' }),
    );
    await user.type(
      screen.getByPlaceholderText('abtest.namePlaceholder'),
      'dup-create',
    );
    fireEvent.submit(screen.getByRole('dialog', { name: 'abtest.createExperiment' }).querySelector('form')!);

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalledWith('abtest.createError', 'error');
    });
    expect(
      screen.getByRole('dialog', { name: 'abtest.createExperiment' }),
    ).toBeInTheDocument();
  });

  it('disables modal actions and shows progress while creation is pending', async () => {
    const user = userEvent.setup();
    let resolveCreate!: (value: { data: Experiment }) => void;
    mocks.createExperiment.mockReturnValue(
      new Promise<{ data: Experiment }>(resolve => {
        resolveCreate = resolve;
      }),
    );

    renderAbTest();
    await user.click(
      await screen.findByRole('button', { name: 'abtest.createExperiment' }),
    );
    await user.type(
      screen.getByPlaceholderText('abtest.namePlaceholder'),
      'pending-create',
    );
    fireEvent.submit(screen.getByRole('dialog', { name: 'abtest.createExperiment' }).querySelector('form')!);

    // 提交进行中：取消/提交均禁用，提交按钮切换为 loading 文案。
    const submit = await screen.findByRole('button', { name: 'common.loading' });
    expect(submit).toBeDisabled();
    expect(
      screen.getByRole('button', { name: 'common.cancel' }),
    ).toBeDisabled();

    resolveCreate({ data: makeExperiment() });
    await waitFor(() => {
      expect(
        screen.queryByRole('dialog', { name: 'abtest.createExperiment' }),
      ).not.toBeInTheDocument();
    });
  });

  it('sends a custom variant B name through the create form', async () => {
    const user = userEvent.setup();
    mocks.createExperiment.mockResolvedValue({ data: makeExperiment() });

    renderAbTest();
    await user.click(
      await screen.findByRole('button', { name: 'abtest.createExperiment' }),
    );
    await user.type(
      screen.getByPlaceholderText('abtest.namePlaceholder'),
      'custom-variants',
    );
    const variantB = screen.getByLabelText('abtest.variant B');
    await user.clear(variantB);
    await user.type(variantB, 'challenger');

    fireEvent.submit(screen.getByRole('dialog', { name: 'abtest.createExperiment' }).querySelector('form')!);

    await waitFor(() => {
      expect(mocks.createExperiment).toHaveBeenCalledWith(
        expect.objectContaining({
          trafficSplit: { control: 0.5, challenger: 0.5 },
        }),
      );
    });
  });
});
