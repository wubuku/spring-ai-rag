import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Alerts } from './Alerts';

const mockUseQuery = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
}));

describe('Alerts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders page title', () => {
    mockUseQuery.mockReturnValue({
      data: { data: [] },
      isPending: false,
    });

    render(<Alerts />);
    const h1 = document.querySelector('h1');
    expect(h1).toBeInTheDocument();
    expect(h1).toHaveTextContent('Alerts');
  });

  it('shows loading state when pending', () => {
    mockUseQuery.mockReturnValue({
      data: undefined,
      isPending: true,
    });

    render(<Alerts />);
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('shows empty state when no alerts', () => {
    mockUseQuery.mockReturnValue({
      data: { data: [] },
      isPending: false,
    });

    render(<Alerts />);
    expect(screen.getByText('No active alerts')).toBeInTheDocument();
  });

  it('shows alert items when alerts exist', () => {
    mockUseQuery.mockReturnValue({
      data: {
        data: [
          {
            id: 1,
            alertName: 'High Latency',
            severity: 'WARNING',
            message: 'Average latency exceeded 1s',
            triggeredAt: '2024-01-01T12:00:00Z',
          },
        ],
      },
      isPending: false,
    });

    render(<Alerts />);
    expect(screen.getByText('High Latency')).toBeInTheDocument();
    expect(screen.getByText('WARNING')).toBeInTheDocument();
    expect(screen.getByText('Average latency exceeded 1s')).toBeInTheDocument();
  });
});
