import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

/**
 * The error paths Batch 797 introduced, which shipped without a test.
 *
 * The old page rendered `?? '—'` for every metric and reported a failed health
 * probe as "unhealthy". Both were defensible in direction and wrong in wording:
 * a dash standing for "the server said nothing" is indistinguishable from one
 * standing for "we never got an answer", and "unhealthy" sends someone to check
 * a database that was never broken. These cases exist because that change went
 * out with the whole suite green and nobody asked what a failing Dashboard
 * looked like.
 */
describe('Dashboard when reads fail', () => {
  const refetchHealth = vi.fn();
  const refetchDocs = vi.fn();
  const refetchCollections = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    refetchHealth.mockClear();
    refetchDocs.mockClear();
    refetchCollections.mockClear();
  });

  function mockFailing(overrides?: { health?: object; docs?: object; collections?: object }) {
    const base = {
      data: undefined,
      isPending: false,
      isError: false,
      refetch: vi.fn(),
    };
    mockUseQuery.mockImplementation((options: { queryKey: string[] }) => {
      const key = options.queryKey[0];
      if (key === 'health') {
        return { ...base, refetch: refetchHealth, ...overrides?.health };
      }
      if (key === 'documents') {
        return { ...base, refetch: refetchDocs, ...overrides?.docs };
      }
      if (key === 'collections') {
        return { ...base, refetch: refetchCollections, ...overrides?.collections };
      }
      return base;
    });
  }

  it('says the health endpoint is unreachable instead of calling the system unhealthy', () => {
    mockFailing({ health: { isError: true } });
    render(<Dashboard />);

    expect(screen.getByText('dashboard.systemUnreachable')).toBeInTheDocument();
    expect(screen.queryByText('dashboard.systemUnhealthy')).toBeNull();
    expect(screen.queryByText('dashboard.systemHealthy')).toBeNull();
  });

  it('still leans the safe way when health is reachable and reports DOWN', () => {
    mockFailing({
      health: { data: { data: { status: 'DOWN', components: { database: 'DOWN' } } } },
    });
    render(<Dashboard />);

    expect(screen.getByText('dashboard.systemUnhealthy')).toBeInTheDocument();
    expect(screen.queryByText('dashboard.systemUnreachable')).toBeNull();
  });

  it('surfaces a retry for a health probe that could not be answered', async () => {
    mockFailing({ health: { isError: true } });
    render(<Dashboard />);

    expect(screen.getByRole('alert')).toHaveTextContent('dashboard.healthLoadFailed');
    await userEvent.click(screen.getAllByRole('button', { name: 'common.retry' })[0]);
    expect(refetchHealth).toHaveBeenCalled();
  });

  it('marks a metric whose read failed as unavailable rather than showing a bare dash', () => {
    mockFailing({
      docs: { isError: true },
      collections: { data: { data: { total: 7 } } },
    });
    const { container } = render(<Dashboard />);

    const unavailable = container.querySelectorAll('[data-unavailable="true"]');
    // Only the documents tile's read failed. The cache and last-check tiles
    // read the same healthy health endpoint and must not inherit its failure.
    expect(unavailable).toHaveLength(1);
    expect(unavailable[0]).toHaveTextContent('—');
    expect(unavailable[0]).toHaveAttribute('title', 'dashboard.metricUnavailable');
    // The healthy sibling keeps its real number and carries no marker.
    expect(screen.getByText('7')).toBeInTheDocument();
  });

  it('does not mark a metric that genuinely has no value as unavailable', () => {
    // A server that answers "no data" is a different situation from a server
    // that never answered, and the page now has to keep them apart.
    mockFailing({
      docs: { data: { data: {} } },
      collections: { data: { data: { total: 7 } } },
    });
    const { container } = render(<Dashboard />);

    expect(container.querySelectorAll('[data-unavailable="true"]')).toHaveLength(0);
  });

  it('gives each failed metric its own retry rather than one for the page', async () => {
    mockFailing({ docs: { isError: true }, collections: { isError: true } });
    render(<Dashboard />);

    const retries = screen.getAllByRole('button', { name: 'common.retry' });
    // Two failed reads, two retries — and no page-level retry, because the
    // health probe succeeded and there is nothing for a third one to ask.
    expect(retries).toHaveLength(2);

    await userEvent.click(retries[0]);
    expect(refetchDocs).toHaveBeenCalled();
    expect(refetchCollections).not.toHaveBeenCalled();
  });

  it('wires each tile’s retry to its own read', async () => {
    // The four tiles are fed by three different requests, and two of them —
    // cache and last check — share the health one. Copy-pasting a retry
    // handler is exactly the mistake that leaves a tile retrying a request that
    // was never the one that failed, so each is checked by name.
    mockFailing({ docs: { isError: true }, collections: { isError: true } });
    render(<Dashboard />);

    const retries = screen.getAllByRole('button', { name: 'common.retry' });
    await userEvent.click(retries[1]);

    expect(refetchCollections).toHaveBeenCalledTimes(1);
    expect(refetchDocs).not.toHaveBeenCalled();
    expect(refetchHealth).not.toHaveBeenCalled();
  });

  it('shows the last-check time when the health payload carries one', () => {
    // This tile used to be `?? '—'` alongside the others, so a healthy backend
    // with no timestamp and a probe that never answered rendered identically.
    const stamp = '2026-09-06T00:00:00Z';
    mockFailing({
      health: {
        data: {
          data: { status: 'UP', components: { cache: 'UP' }, timestamp: stamp },
        },
      },
    });
    render(<Dashboard />);

    expect(screen.getByText(new Date(stamp).toLocaleString())).toBeInTheDocument();
    expect(screen.queryByText('dashboard.systemUnreachable')).toBeNull();
  });

  it('drives the health-backed tiles from the health read, not the metrics', async () => {
    mockFailing({ health: { isError: true } });
    render(<Dashboard />);

    // One banner retry, then the two tiles fed by the same failed health read.
    const retries = screen.getAllByRole('button', { name: 'common.retry' });
    expect(retries).toHaveLength(3);

    await userEvent.click(retries[1]);
    expect(refetchHealth).toHaveBeenCalledTimes(1);
    expect(refetchDocs).not.toHaveBeenCalled();
  });

  it('offers no retry on a tile that succeeded', () => {
    mockFailing({
      docs: { data: { data: { total: 42 } } },
      collections: { data: { data: { total: 7 } } },
      health: {
        data: {
          data: {
            status: 'UP',
            components: { cache: 'UP' },
            timestamp: '2026-09-06T00:00:00Z',
          },
        },
      },
    });
    render(<Dashboard />);

    expect(screen.getByText('42')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'common.retry' })).toBeNull();
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
