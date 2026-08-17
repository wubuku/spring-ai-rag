import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { Documents } from './Documents';

// Mock functions at module level
const mockUseQuery = vi.fn();
const mockUseMutation = vi.fn(() => ({
  mutate: vi.fn(),
  isPending: false,
}));
const mockUseQueryClient = vi.fn(() => ({
  invalidateQueries: vi.fn(),
}));
const mockUploadFiles = vi.fn();
const mockShowToast = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
  useMutation: (...args: unknown[]) => mockUseMutation(...args),
  useQueryClient: (...args: unknown[]) => mockUseQueryClient(...args),
}));

vi.mock('../components/Toast', () => ({
  useToast: vi.fn(() => ({
    showToast: mockShowToast,
  })),
}));

vi.mock('../hooks/useFileUpload', () => ({
  useFileUpload: vi.fn(() => ({
    uploadFiles: mockUploadFiles,
    isUploading: false,
    uploads: [],
  })),
}));

vi.mock('../api/documents', () => ({
  documentsApi: {
    list: vi.fn(),
    delete: vi.fn(),
    embed: vi.fn(),
  },
}));

describe('Documents', () => {
  const renderDocuments = () => render(
    <MemoryRouter>
      <Documents />
    </MemoryRouter>,
  );

  beforeEach(() => {
    vi.clearAllMocks();
    mockUseMutation.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    });
  });

  it('renders page title', () => {
    mockUseQuery.mockReturnValue({
      // TanStack Query wraps axios response: { data: AxiosResponse<DocumentListResponse> }
      // AxiosResponse.data = { offset, documents, total }
      data: { data: { documents: [], total: 0 } },
      isPending: false,
      error: null,
    });

    renderDocuments();
    const h1 = document.querySelector('h1');
    expect(h1).toBeInTheDocument();
    expect(h1).toHaveTextContent('documents.title');
  });

  it('shows upload zone', () => {
    mockUseQuery.mockReturnValue({
      data: { data: { documents: [], total: 0 } },
      isPending: false,
      error: null,
    });

    renderDocuments();
    expect(screen.getByText(/documents.uploadHint/)).toBeInTheDocument();
  });

  it('shows table when documents exist', () => {
    mockUseQuery.mockReturnValue({
      data: {
        data: {
          documents: [
            {
              id: 1,
              title: 'Test Doc',
              content: 'Content',
              contentHash: 'abc123',
              documentType: 'txt',
              createdAt: '2024-01-01T00:00:00Z',
              updatedAt: '2024-01-01T00:00:00Z',
            },
          ],
          total: 1,
        },
      },
      isPending: false,
      error: null,
    });

    renderDocuments();
    expect(screen.getByText('Test Doc')).toBeInTheDocument();
  });

  it('shows empty state when no documents', () => {
    mockUseQuery.mockReturnValue({
      data: { data: { documents: [], total: 0 } },
      isPending: false,
      error: null,
    });

    renderDocuments();
    expect(screen.getByText(/documents.noDocuments/)).toBeInTheDocument();
  });

  it('shows pagination controls', () => {
    mockUseQuery.mockReturnValue({
      data: { data: { documents: [], total: 50 } },
      isPending: false,
      error: null,
    });

    renderDocuments();
    expect(screen.getByText(/Page 1 — documents\.totalDocuments: 50/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /common.previous/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /common.next/ })).toBeInTheDocument();
  });

  it('Previous button is disabled on first page', () => {
    mockUseQuery.mockReturnValue({
      data: { data: { documents: [], total: 50 } },
      isPending: false,
      error: null,
    });

    renderDocuments();
    expect(screen.getByRole('button', { name: /common.previous/ })).toBeDisabled();
  });

  it('shows external identity and retry action in the row menu for stale embeddings', async () => {
    const user = userEvent.setup();
    mockUseQuery.mockReturnValue({
      data: {
        data: {
          documents: [{
            id: 1,
            title: 'External Doc',
            content: 'Content',
            contentHash: 'abc123',
            documentType: 'txt',
            createdAt: '2024-01-01T00:00:00Z',
            updatedAt: '2024-01-01T00:00:00Z',
            externalId: 'cms:article:1',
            sourceRevision: 'etag:2',
            embeddingFresh: false,
            enabled: true,
            processingError: 'provider unavailable',
          }],
          total: 1,
        },
      },
      isPending: false,
      error: null,
    });

    renderDocuments();

    expect(screen.getByText('cms:article:1')).toBeInTheDocument();
    expect(screen.getByText('etag:2')).toBeInTheDocument();
    expect(screen.getByText('documents.embeddingStale')).toBeInTheDocument();
    expect(screen.getByText('provider unavailable')).toBeInTheDocument();
    await user.click(screen.getByRole('button', {
      name: 'documents.openActions',
    }));
    expect(screen.getByRole('menuitem', { name: 'documents.retryEmbedding' }))
      .toBeInTheDocument();
  });

  it('offers PDF source traceability actions only for a safe imported PDF source', async () => {
    const user = userEvent.setup();
    mockUseQuery.mockReturnValue({
      data: {
        data: {
          documents: [{
            id: 7,
            title: 'Imported Manual',
            content: 'Content',
            source: 'pdf-import:uuid-7/default.md',
            contentHash: 'abc123',
            documentType: 'PDF',
            createdAt: '2024-01-01T00:00:00Z',
            updatedAt: '2024-01-01T00:00:00Z',
            embeddingFresh: true,
            enabled: true,
          }],
          total: 1,
        },
      },
      isPending: false,
      error: null,
    });

    renderDocuments();

    await user.click(screen.getByRole('button', {
      name: 'documents.openActions',
    }));
    const traceability = screen.getByRole('menuitem', {
      name: /documents.sourceTraceability/,
    });
    expect(traceability).toBeEnabled();

    await user.click(traceability);
    expect(screen.getByRole('menuitem', {
      name: 'documents.viewFileDirectory',
    })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', {
      name: 'documents.viewIndexedFile',
    })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', {
      name: 'documents.openOriginalPdf',
    })).toBeInTheDocument();
  });
});
