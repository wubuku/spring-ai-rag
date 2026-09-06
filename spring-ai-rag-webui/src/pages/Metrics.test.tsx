import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Metrics } from './Metrics';

const mockUseQuery = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
}));

describe('Metrics', () => {
  beforeEach(() => {
    mockUseQuery.mockReset();
  });

  function mockQueries(
    metrics: unknown,
    usage: unknown = {
      data: {
        data: {
          recordingEnabled: true,
          localLostEventsSinceStart: 0,
          scope: { type: 'SELF', principalId: 'db:test' },
          from: '2026-08-01',
          to: '2026-08-27',
          totals: {
            logicalExecutionCount: 1,
            invocationCount: 1,
            succeededCount: 1,
            failedCount: 0,
            cancelledCount: 0,
            promptTokens: 10,
            completionTokens: 5,
            totalTokens: 15,
            usageAvailableCount: 1,
            usageUnavailableCount: 0,
            pricingUnavailableCount: 0,
            costUnavailableCount: 0,
          },
          costs: [{
            unit: 'USD_ESTIMATE',
            configuredCost: '0.00015000',
            invocationCount: 1,
            costAvailableCount: 1,
          }],
          byModel: [{ modelRef: 'test/model', totals: {
            logicalExecutionCount: 1,
            invocationCount: 1,
            succeededCount: 1,
            failedCount: 0,
            cancelledCount: 0,
            promptTokens: 10,
            completionTokens: 5,
            totalTokens: 15,
            usageAvailableCount: 1,
            usageUnavailableCount: 0,
            pricingUnavailableCount: 0,
            costUnavailableCount: 0,
          } }],
          byPurpose: [],
          byMode: [],
          byDay: [],
        },
      },
      isPending: false,
      isError: false,
    },
  ) {
    mockUseQuery
      .mockReturnValueOnce(metrics)
      .mockReturnValueOnce(usage);
  }

  it('renders page title', () => {
    mockQueries({
      data: { data: {} },
      isPending: false,
    });

    render(<Metrics />);
    const h1 = document.querySelector('h1');
    expect(h1).toBeInTheDocument();
    expect(h1).toHaveTextContent('metrics.title');
  });

  it('shows loading state when pending', () => {
    mockQueries({
      data: undefined,
      isPending: true,
    }, {
      data: undefined,
      isPending: true,
    });

    render(<Metrics />);
    expect(screen.getByText('common.loading')).toBeInTheDocument();
  });

  it('shows metrics data when loaded', () => {
    mockQueries({
      data: {
        data: {
          totalRetrievals: 100,
          totalLlmCalls: 50,
          totalLlmTokens: 5000,
          avgRetrievalLatencyMs: 150,
          cacheHitRate: 0.82,
        },
      },
      isPending: false,
    });

    render(<Metrics />);
    expect(screen.getByText(/totalRetrievals/i)).toBeInTheDocument();
  });

  it('renders durable usage summaries and breakdowns', () => {
    mockQueries({ data: { data: {} }, isPending: false });

    render(<Metrics />);

    expect(mockUseQuery).toHaveBeenCalledTimes(2);
    expect(screen.getByText('metrics.durableUsage')).toBeInTheDocument();
    expect(screen.getByText('test/model')).toBeInTheDocument();
    expect(screen.getByText('USD_ESTIMATE')).toBeInTheDocument();
    expect(screen.getByText('metrics.costDisclaimer')).toBeInTheDocument();
  });

  it('renders the durable usage empty state', () => {
    mockQueries({ data: { data: {} }, isPending: false }, {
      data: {
        data: {
          recordingEnabled: true,
          localLostEventsSinceStart: 0,
          scope: { type: 'SELF', principalId: 'db:test' },
          from: '2026-08-01',
          to: '2026-08-27',
          totals: {
            logicalExecutionCount: 0,
            invocationCount: 0,
            succeededCount: 0,
            failedCount: 0,
            cancelledCount: 0,
            promptTokens: 0,
            completionTokens: 0,
            totalTokens: 0,
            usageAvailableCount: 0,
            usageUnavailableCount: 0,
            pricingUnavailableCount: 0,
            costUnavailableCount: 0,
          },
          costs: [],
          byModel: [],
          byPurpose: [],
          byMode: [],
          byDay: [],
        },
      },
      isPending: false,
      isError: false,
    });

    render(<Metrics />);

    expect(screen.getByText('metrics.noDurableUsage')).toBeInTheDocument();
  });

  it('renders a durable usage error without hiding legacy metrics', () => {
    mockQueries({
      data: { data: { totalRetrievals: 1 } },
      isPending: false,
    }, {
      data: undefined,
      isPending: false,
      isError: true,
    });

    render(<Metrics />);

    expect(screen.getByText('metrics.usageLoadFailed')).toBeInTheDocument();
    expect(screen.getByText(/totalRetrievals/i)).toBeInTheDocument();
  });
  function totals(overrides: Record<string, unknown> = {}) {
    return {
      logicalExecutionCount: 1,
      invocationCount: 1,
      succeededCount: 1,
      failedCount: 0,
      cancelledCount: 0,
      promptTokens: 10,
      completionTokens: 5,
      totalTokens: 15,
      usageAvailableCount: 1,
      usageUnavailableCount: 0,
      pricingUnavailableCount: 0,
      costUnavailableCount: 0,
      ...overrides,
    };
  }

  it('formats huge integer token counts through the BigInt path', () => {
    const huge = '123456789012345678901234567890';
    // 按 queryKey 分发，避免 Once 队列顺序问题。
    mockUseQuery.mockImplementation((options: { queryKey: unknown[] }) => ({
      data: options.queryKey[0] === 'metrics'
        ? { data: { status: 'UP', components: {} } }
        : { data: {
            recordingEnabled: true,
            localLostEventsSinceStart: 0,
            scope: { type: 'SELF', principalId: 'db:test' },
            from: '2026-08-01',
            to: '2026-08-27',
            totals: totals({ totalTokens: huge }),
            costs: [], byModel: [], byPurpose: [], byMode: [], byDay: [],
          } },
      isPending: false,
      isError: false,
    }));

    render(<Metrics />);

    expect(screen.getAllByText(
      huge.replace(/\B(?=(\d{3})+(?!\d))/g, ','),
    ).length).toBeGreaterThan(0);
  });

  it('renders non-numeric token values as raw text', () => {
    mockQueries(
      { data: { data: { status: 'UP', components: {} } }, isPending: false },
      { data: { data: {
        recordingEnabled: true,
        localLostEventsSinceStart: 0,
        scope: { type: 'SELF', principalId: 'db:test' },
        from: '2026-08-01',
        to: '2026-08-27',
        totals: totals({ totalTokens: 'not-a-number' }),
        costs: [],
        byModel: [], byPurpose: [], byMode: [], byDay: [],
      } }, isPending: false },
    );

    render(<Metrics />);

    expect(screen.getByText('not-a-number')).toBeInTheDocument();
  });

  it('renders purpose, mode and day breakdown tables when rows exist', () => {
    mockQueries(
      { data: { data: { status: 'UP', components: {} } }, isPending: false },
      { data: { data: {
        recordingEnabled: true,
        localLostEventsSinceStart: 0,
        scope: { type: 'SELF', principalId: 'db:test' },
        from: '2026-08-01',
        to: '2026-08-27',
        totals: totals(),
        costs: [{ unit: 'USD', configuredCost: '0.25', invocationCount: 2,
          costAvailableCount: 2 }],
        byModel: [{ modelRef: 'm1', totals: totals() }],
        byPurpose: [{ purpose: 'CHAT', totals: totals() }],
        byMode: [{ mode: 'AGENT', totals: totals() }],
        byDay: [{ day: '2026-08-15', totals: totals() }],
      } }, isPending: false },
    );

    render(<Metrics />);

    for (const label of ['metrics.models', 'metrics.purposes',
      'metrics.modes', 'metrics.utcDay']) {
      expect(screen.getByRole('table', { name: label })).toBeInTheDocument();
    }
    expect(screen.getByText('CHAT')).toBeInTheDocument();
    expect(screen.getByText('AGENT')).toBeInTheDocument();
    expect(screen.getByText('2026-08-15')).toBeInTheDocument();
  });

  it('renders zero for missing numeric fields and formats fractional cost', () => {
    mockQueries(
      { data: { data: { status: 'UP', components: {} } }, isPending: false },
      { data: { data: {
        recordingEnabled: true,
        localLostEventsSinceStart: 0,
        scope: { type: 'SELF', principalId: 'db:test' },
        from: '2026-08-01',
        to: '2026-08-27',
        totals: totals({ promptTokens: undefined }),
        costs: [{ unit: 'USD', configuredCost: '0.25', invocationCount: 2,
          costAvailableCount: 2 }],
        byModel: [], byPurpose: [], byMode: [], byDay: [],
      } }, isPending: false },
    );

    render(<Metrics />);

    expect(screen.getAllByText('0').length).toBeGreaterThan(0);
    expect(screen.getByText('0.25')).toBeInTheDocument();
  });

});
