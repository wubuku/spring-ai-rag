import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Dashboard } from './Dashboard';
import { healthApi } from '../api/health';
import { documentsApi } from '../api/documents';
import { collectionsApi } from '../api/collections';

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
  it('wires each query function to its API client', async () => {
    vi.mocked(healthApi.get).mockResolvedValue({ data: {} } as never);
    vi.mocked(documentsApi.list).mockResolvedValue({ data: {} } as never);
    vi.mocked(collectionsApi.list).mockResolvedValue({ data: {} } as never);

    mockQueries();
    render(<Dashboard />);

    const calls = mockUseQuery.mock.calls as Array<
      [{ queryKey: string[]; queryFn: () => Promise<unknown> }]
    >;
    expect(calls).toHaveLength(3);

    for (const [{ queryFn }] of calls) {
      await queryFn();
    }

    expect(healthApi.get).toHaveBeenCalledTimes(1);
    expect(documentsApi.list).toHaveBeenCalledWith({ page: 0, size: 1 });
    expect(collectionsApi.list).toHaveBeenCalledWith({ page: 0, size: 1 });
  });
});

describe('Dashboard metric card skeletons', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  function localMockQueries(overrides?: {
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

  it('shows metric card skeletons while docs and collections are pending', () => {
    localMockQueries({
      docs: { isPending: true },
      collections: { isPending: true },
    });
    const { container } = render(<Dashboard />);

    // 两张卡（文档/集合）以 60px 宽骨架占位；其余卡仍渲染各自的指标。
    const skeletons = container.querySelectorAll('div[style*="60px"]');
    expect(skeletons.length).toBe(2);
  });

  it('renders the dash placeholder when only the collections query is pending', () => {
    localMockQueries({
      collections: { isPending: true },
      docs: {
        data: { data: { total: 12 } },
        isPending: false,
      },
    });
    render(<Dashboard />);

    // 文档卡显示数值，集合卡在 pending 时不出占位符冲突。
    expect(screen.getByText('12')).toBeInTheDocument();
  });
});
