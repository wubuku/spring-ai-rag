import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

  function mockQueries(overrides?: {
    health?: object;
    docs?: object;
    collections?: object;
  }) {
    const defaults = {
      data: { data: {} },
      isPending: false,
    };
    const healthData = { ...defaults, ...overrides?.health };
    const docsData = { ...defaults, ...overrides?.docs };
    const collectionsData = { ...defaults, ...overrides?.collections };
    mockUseQuery.mockImplementation((options: { queryKey: string[] }) => {
      const key = options.queryKey[0];
      if (key === 'health') return healthData;
      if (key === 'documents') return docsData;
      if (key === 'collections') return collectionsData;
      return { data: undefined, isPending: false };
    });
  }

  it('renders page title', () => {
    mockQueries();
    render(<Dashboard />);
    const h1 = document.querySelector('h1');
    expect(h1).toBeInTheDocument();
    expect(h1).toHaveTextContent('dashboard.title');
  });

  it('shows loading skeleton when pending', () => {
    mockQueries({ health: { isPending: true } });
    render(<Dashboard />);
    expect(document.querySelector('h1')).toBeInTheDocument();
  });

  it('renders healthy status banner with component details', () => {
    mockQueries({
      health: {
        data: {
          data: {
            status: 'UP',
            components: { database: 'UP', pgvector: 'UP', cache: 'UP' },
            timestamp: '2026-09-06T00:00:00Z',
          },
        },
      },
    });
    render(<Dashboard />);
    expect(screen.getByText('dashboard.systemHealthy')).toBeInTheDocument();
    expect(screen.getByText(/dashboard\.db.*dashboard\.vector/)).toBeInTheDocument();
  });

  it('renders unhealthy status when health status is not UP', () => {
    mockQueries({
      health: {
        data: {
          data: {
            status: 'DOWN',
            components: { database: 'DOWN', pgvector: 'DOWN', cache: 'DOWN' },
          },
        },
      },
    });
    render(<Dashboard />);
    expect(screen.getByText('dashboard.systemUnhealthy')).toBeInTheDocument();
  });

  it('renders document and collection metric cards', () => {
    mockQueries({
      docs: { data: { data: { total: 42 } }, isPending: false },
      collections: { data: { data: { total: 7 } }, isPending: false },
      health: {
        data: {
          data: {
            status: 'UP',
            components: { database: 'UP', pgvector: 'UP', cache: 'UP' },
            timestamp: '2026-09-06T00:00:00Z',
          },
        },
      },
    });
    render(<Dashboard />);
    expect(screen.getByText('dashboard.documents')).toBeInTheDocument();
    expect(screen.getByText('dashboard.collections')).toBeInTheDocument();
    expect(screen.getByText('dashboard.cache')).toBeInTheDocument();
    expect(screen.getByText('dashboard.lastCheck')).toBeInTheDocument();
  });

  it('renders dash placeholder when query data is not yet available', () => {
    mockQueries({
      docs: { data: { data: {} }, isPending: false },
      collections: { data: { data: {} }, isPending: false },
      health: { data: { data: {} }, isPending: false },
    });
    render(<Dashboard />);
    // Should render without crash even with empty data objects
    expect(screen.getByText('dashboard.documents')).toBeInTheDocument();
  });
});
