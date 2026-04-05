import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Metrics } from './Metrics';

const mockUseQuery = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
}));

describe('Metrics', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders page title', () => {
    mockUseQuery.mockReturnValue({
      data: { data: {} },
      isPending: false,
    });

    render(<Metrics />);
    const h1 = document.querySelector('h1');
    expect(h1).toBeInTheDocument();
    expect(h1).toHaveTextContent('metrics.title');
  });

  it('shows loading state when pending', () => {
    mockUseQuery.mockReturnValue({
      data: undefined,
      isPending: true,
    });

    render(<Metrics />);
    expect(screen.getByText('common.loading')).toBeInTheDocument();
  });

  it('shows metrics data when loaded', () => {
    mockUseQuery.mockReturnValue({
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
});
