import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { VersionHistoryModal } from './VersionHistoryModal';
import { documentsApi } from '../../api/documents';

vi.mock('../../api/documents', () => ({
  documentsApi: {
    getVersions: vi.fn(),
    getVersion: vi.fn(),
  },
}));

function Wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

const mockVersions = [
  {
    id: 1,
    documentId: 42,
    versionNumber: 3,
    contentHash: 'abc12345',
    size: 1024,
    changeType: 'UPDATE',
    changeDescription: 'Updated content',
    createdAt: '2026-04-08T05:00:00Z',
  },
  {
    id: 2,
    documentId: 42,
    versionNumber: 2,
    contentHash: 'def67890',
    size: 900,
    changeType: 'CREATE',
    changeDescription: 'Initial version',
    createdAt: '2026-04-07T05:00:00Z',
  },
  {
    id: 3,
    documentId: 42,
    versionNumber: 1,
    contentHash: 'ghi11111',
    size: 800,
    changeType: 'CREATE',
    changeDescription: 'First draft',
    createdAt: '2026-04-06T05:00:00Z',
  },
];

describe('VersionHistoryModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders version history modal with title', async () => {
    vi.mocked(documentsApi.getVersions).mockResolvedValueOnce({
      data: { documentId: 42, totalVersions: 3, page: 0, size: 20, versions: mockVersions },
    } as any);

    render(
      <VersionHistoryModal documentId={42} documentTitle="Test Doc" onClose={vi.fn()} />,
      { wrapper: Wrapper }
    );

    // Modal renders with the document title (text is nested inside h2 alongside i18n key)
    expect(screen.getByText(/Test Doc/)).toBeTruthy();
  });

  it('shows loading state initially', async () => {
    vi.mocked(documentsApi.getVersions).mockImplementation(
      () => new Promise(() => {}) as any
    );

    render(
      <VersionHistoryModal documentId={42} documentTitle="Test Doc" onClose={vi.fn()} />,
      { wrapper: Wrapper }
    );

    expect(screen.getByText(/versions\.title/i)).toBeTruthy();
  });

  it('renders version list when loaded', async () => {
    vi.mocked(documentsApi.getVersions).mockResolvedValueOnce({
      data: { documentId: 42, totalVersions: 3, page: 0, size: 20, versions: mockVersions },
    } as any,
    );

    render(
      <VersionHistoryModal documentId={42} documentTitle="Test Doc" onClose={vi.fn()} />,
      { wrapper: Wrapper }
    );

    await waitFor(() => {
      expect(screen.getByText('v3')).toBeTruthy();
      expect(screen.getByText('v2')).toBeTruthy();
      expect(screen.getByText('v1')).toBeTruthy();
    });
  });

  it('shows error state when API fails', async () => {
    vi.mocked(documentsApi.getVersions).mockRejectedValueOnce(new Error('Network error'));

    render(
      <VersionHistoryModal documentId={42} documentTitle="Test Doc" onClose={vi.fn()} />,
      { wrapper: Wrapper }
    );

    await waitFor(() => {
      expect(screen.getByText(/versions\.loadError/i)).toBeTruthy();
    });
  });

  it('renders empty state when no versions', async () => {
    vi.mocked(documentsApi.getVersions).mockResolvedValueOnce({
      data: { documentId: 42, totalVersions: 0, page: 0, size: 20, versions: [] },
    } as any,
    );

    render(
      <VersionHistoryModal documentId={42} documentTitle="Test Doc" onClose={vi.fn()} />,
      { wrapper: Wrapper }
    );

    await waitFor(() => {
      expect(screen.getByText(/versions\.noVersions/i)).toBeTruthy();
    });
  });

  it('closes on overlay click', async () => {
    vi.mocked(documentsApi.getVersions).mockResolvedValueOnce({
      data: { documentId: 42, totalVersions: 0, page: 0, size: 20, versions: [] },
    } as any,
    );

    const onClose = vi.fn();
    render(
      <VersionHistoryModal documentId={42} documentTitle="Test Doc" onClose={onClose} />,
      { wrapper: Wrapper }
    );

    await waitFor(() => {
      const overlay = document.querySelector('[class*="overlay"]');
      if (overlay) fireEvent.click(overlay);
    });

    expect(onClose).toHaveBeenCalled();
  });

  it('selects two versions and shows compare info', async () => {
    vi.mocked(documentsApi.getVersions).mockResolvedValueOnce({
      data: { documentId: 42, totalVersions: 3, page: 0, size: 20, versions: mockVersions },
    } as any,
    );

    render(
      <VersionHistoryModal documentId={42} documentTitle="Test Doc" onClose={vi.fn()} />,
      { wrapper: Wrapper }
    );

    await waitFor(() => {
      expect(screen.getByText('v3')).toBeTruthy();
    });

    const items = document.querySelectorAll('[class*="versionItem"]');
    fireEvent.click(items[0]);
    fireEvent.click(items[1]);

    await waitFor(() => {
      // After selecting two versions, compare button appears
      expect(screen.getByText(/versions\.compare/i)).toBeTruthy();
    });
  });

  it('switches to diff tab after comparing versions', async () => {
    vi.mocked(documentsApi.getVersions).mockResolvedValueOnce({
      data: { documentId: 42, totalVersions: 2, page: 0, size: 20, versions: mockVersions.slice(0, 2) },
    } as any);

    vi.mocked(documentsApi.getVersion)
      .mockResolvedValueOnce({
        id: 1,
        documentId: 42,
        versionNumber: 1,
        contentHash: 'abc12345',
        size: 100,
        changeType: 'CREATE',
        changeDescription: '',
        createdAt: '2026-04-06T05:00:00Z',
        contentSnapshot: 'Hello world',
      } as any)
      .mockResolvedValueOnce({
        id: 2,
        documentId: 42,
        versionNumber: 2,
        contentHash: 'def67890',
        size: 120,
        changeType: 'UPDATE',
        changeDescription: '',
        createdAt: '2026-04-07T05:00:00Z',
        contentSnapshot: 'Hello world updated',
      } as any);

    render(
      <VersionHistoryModal documentId={42} documentTitle="Test Doc" onClose={vi.fn()} />,
      { wrapper: Wrapper }
    );

    await waitFor(() => {
      expect(screen.getByText('v2')).toBeTruthy();
    });

    const items = document.querySelectorAll('[class*="versionItem"]');
    fireEvent.click(items[0]);
    fireEvent.click(items[1]);

    await waitFor(() => {
      expect(screen.getByText(/versions\.compare/i)).toBeTruthy();
    });

    const compareBtn = screen.getByRole('button', { name: /versions\.compare/i });
    fireEvent.click(compareBtn);

    await waitFor(() => {
      expect(screen.getByText(/versions\.diffTab/i)).toBeTruthy();
    });
  });
});
