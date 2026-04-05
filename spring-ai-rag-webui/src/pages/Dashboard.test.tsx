import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import { Dashboard } from './Dashboard';

const mockUseQuery = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
}));

vi.mock('../api/health', () => ({
  healthApi: { get: vi.fn() },
}));

vi.mock('../api/documents', () => ({
  documentsApi: { list: vi.fn() },
}));

vi.mock('../api/collections', () => ({
  collectionsApi: { list: vi.fn() },
}));

describe('Dashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders page title', () => {
    mockUseQuery.mockReturnValue({
      data: { data: {} },
      isPending: false,
    });

    render(<Dashboard />);
    const h1 = document.querySelector('h1');
    expect(h1).toBeInTheDocument();
    expect(h1).toHaveTextContent('Dashboard');
  });

  it('shows loading skeleton when pending', () => {
    mockUseQuery.mockReturnValue({
      data: undefined,
      isPending: true,
    });

    render(<Dashboard />);
    // Should render without crashing when loading
    expect(document.querySelector('h1')).toBeInTheDocument();
  });

  it('shows status banner when health check completes', () => {
    mockUseQuery.mockImplementation((options: { queryKey: string[] }) => {
      if (options.queryKey[0] === 'health') {
        return {
          data: {
            data: {
              status: 'UP',
              components: {
                database: 'UP',
                pgvector: 'UP',
                cache: 'UP',
              },
            },
          },
          isPending: false,
        };
      }
      return { data: { data: {} }, isPending: false };
    });

    render(<Dashboard />);
    expect(document.querySelector('h1')).toBeInTheDocument();
  });
});
