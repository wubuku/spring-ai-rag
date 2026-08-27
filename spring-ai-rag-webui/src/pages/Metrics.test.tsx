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
});
