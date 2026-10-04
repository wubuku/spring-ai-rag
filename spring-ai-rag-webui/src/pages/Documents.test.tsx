import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useSearchParams } from 'react-router-dom';
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
    get: vi.fn(),
    update: vi.fn(),
    disable: vi.fn(),
    restore: vi.fn(),
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

  /**
   * 关键词最终会落到哪个查询条件上？页面用 queryKey 携带 keyword，
   * 所以直接读最后一次列表查询的实参，而不是另建一个 DOM 探针去镜像状态。
   */
  const currentKeyword = (): string | undefined => {
    const { calls } = mockUseQuery.mock;
    for (let i = calls.length - 1; i >= 0; i -= 1) {
      const key = (calls[i][0] as { queryKey?: unknown } | undefined)?.queryKey;
      if (Array.isArray(key) && key[0] === 'documents') {
        return key[2] as string;
      }
    }
    return undefined;
  };

  /** 模拟浏览器前进/后退造成的外部 URL 变化。 */
  const ExternalUrlChange = () => {
    const [params, setParams] = useSearchParams();
    return (
      <button
        type="button"
        onClick={() => {
          const next = new URLSearchParams(params);
          next.set('keyword', '外部关键词');
          setParams(next);
        }}
      >
        external-url-change
      </button>
    );
  };

  const renderDocumentsWithExternalUrlControl = () => render(
    <MemoryRouter>
      <ExternalUrlChange />
      <Documents />
    </MemoryRouter>,
  );

  const emptyList = () => {
    mockUseQuery.mockReturnValue({
      data: { data: { documents: [], total: 0 } },
      isPending: false,
      error: null,
    });
  };

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

  // Batch 872. `Documents` printed `error.message` unconditionally, so a
  // transport-level string appeared after the label as though the server had
  // said it. The fallback it already used for a non-Error value
  // (`common.unknownError`) is now what a message with no reason in it gets too.
  it('does not print a transport message as the load-failure reason', () => {
    mockUseQuery.mockReturnValue({
      data: undefined,
      isPending: false,
      error: new Error('Request failed with status code 500'),
    });

    renderDocuments();

    // The label and the reason are siblings inside one node, so a matcher has to
    // be a substring one — `getByText('exact')` would never match here.
    expect(screen.getByText(/documents\.loadError/)).toHaveTextContent(
      'common.unknownError',
    );
    expect(screen.queryByText(/Request failed with status code/)).toBeNull();
  });

  it('still prints a reason the server actually gave', () => {
    mockUseQuery.mockReturnValue({
      data: undefined,
      isPending: false,
      error: new Error('Storage backend refused the listing request'),
    });

    renderDocuments();

    expect(screen.getByText(/documents\.loadError/)).toHaveTextContent(
      'Storage backend refused the listing request',
    );
    expect(screen.queryByText(/common\.unknownError/)).toBeNull();
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
    expect(screen.getByText('documents.lifecycle.NOT_REQUESTED')).toBeInTheDocument();
    expect(screen.getByText('provider unavailable')).toBeInTheDocument();
    await user.click(screen.getByRole('button', {
      name: 'documents.openActions',
    }));
    expect(screen.getByRole('menuitem', { name: 'documents.retryEmbedding' }))
      .toBeInTheDocument();
    expect(screen.queryByRole('menuitem', { name: 'documents.edit' }))
      .not.toBeInTheDocument();
    expect(screen.getByText('documents.externallyManaged')).toBeInTheDocument();
  });

  it('offers local lifecycle mutations for a revisioned document', async () => {
    const user = userEvent.setup();
    mockUseQuery.mockReturnValue({
      data: {
        data: {
          documents: [{
            id: 2,
            title: 'Local Doc',
            content: 'Local content',
            contentHash: 'local123',
            documentType: 'TEXT',
            documentRevision: 4,
            createdAt: '2024-01-01T00:00:00Z',
            updatedAt: '2024-01-01T00:00:00Z',
            embeddingFresh: true,
            enabled: true,
            lifecycle: {
              documentState: 'ACTIVE',
              searchability: 'READY',
              localIndexStatus: 'READY',
              embeddingStatus: 'READY',
              retryable: false,
            },
          }],
          total: 1,
        },
      },
      isPending: false,
      error: null,
    });

    renderDocuments();

    expect(screen.getByText('documents.lifecycle.READY')).toBeInTheDocument();
    await user.click(screen.getByRole('button', {
      name: 'documents.openActions',
    }));
    expect(screen.getByRole('menuitem', { name: 'documents.edit' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'documents.disable' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'documents.permanentDelete' }))
      .toBeInTheDocument();
  });

  it('shows keyword-only lifecycle and keeps embedding retry available', async () => {
    const user = userEvent.setup();
    mockUseQuery.mockReturnValue({
      data: {
        data: {
          documents: [{
            id: 8,
            title: 'Keyword Only Doc',
            content: 'Keyword content',
            contentHash: 'keyword123',
            documentType: 'TEXT',
            documentRevision: 2,
            createdAt: '2024-01-01T00:00:00Z',
            updatedAt: '2024-01-01T00:00:00Z',
            embeddingFresh: false,
            enabled: true,
            lifecycle: {
              documentState: 'ACTIVE',
              searchability: 'KEYWORD_ONLY',
              localIndexStatus: 'READY',
              embeddingStatus: 'FAILED',
              lastError: 'provider unavailable',
              retryable: true,
            },
          }],
          total: 1,
        },
      },
      isPending: false,
      error: null,
    });

    renderDocuments();

    expect(screen.getByText('documents.lifecycle.KEYWORD_ONLY')).toBeInTheDocument();
    const status = screen.getByText('documents.lifecycle.KEYWORD_ONLY');
    expect(status).toHaveAttribute('title', 'documents.keywordOnlyHint');
    await user.click(screen.getByRole('button', {
      name: 'documents.openActions',
    }));
    expect(screen.getByRole('menuitem', { name: 'documents.retryEmbedding' }))
      .toBeInTheDocument();
  });

  it('offers restore instead of edit disable for a disabled local document', async () => {
    const user = userEvent.setup();
    mockUseQuery.mockReturnValue({
      data: {
        data: {
          documents: [{
            id: 3,
            title: 'Disabled Doc',
            content: 'Disabled content',
            contentHash: 'disabled123',
            documentType: 'TEXT',
            documentRevision: 5,
            createdAt: '2024-01-01T00:00:00Z',
            updatedAt: '2024-01-01T00:00:00Z',
            embeddingFresh: false,
            enabled: false,
          }],
          total: 1,
        },
      },
      isPending: false,
      error: null,
    });

    renderDocuments();

    expect(screen.getByText('documents.lifecycle.DISABLED')).toBeInTheDocument();
    await user.click(screen.getByRole('button', {
      name: 'documents.openActions',
    }));
    expect(screen.getByRole('menuitem', { name: 'documents.restore' })).toBeInTheDocument();
    expect(screen.queryByRole('menuitem', { name: 'documents.disable' }))
      .not.toBeInTheDocument();
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

  describe('中文输入法防线', () => {
    const searchInput = () => screen.getByLabelText('documents.searchPlaceholder');

    it('组合过程中只更新草稿，组合结束后才写进查询条件', async () => {
      emptyList();
      renderDocuments();

      const input = searchInput();
      fireEvent.compositionStart(input);
      fireEvent.change(input, { target: { value: '中文' } });

      // 组合中的中间拼音串不能进查询条件，否则每敲一个字母就查一次库。
      expect(input).toHaveValue('中文');
      expect(currentKeyword()).toBe('');

      fireEvent.compositionEnd(input, { data: '中文' });
      await waitFor(() => {
        expect(currentKeyword()).toBe('中文');
      });
    });

    it('组合进行中失焦不会把半成品提交出去', () => {
      emptyList();
      renderDocuments();

      const input = searchInput();
      fireEvent.compositionStart(input);
      fireEvent.change(input, { target: { value: '中文' } });
      fireEvent.blur(input);

      // 用户在组合中途切走焦点：草稿留在输入框里，查询条件保持原样。
      expect(input).toHaveValue('中文');
      expect(currentKeyword()).toBe('');
    });

    it('组合进行中外部 URL 变化不覆盖正在输入的草稿', async () => {
      emptyList();
      const user = userEvent.setup();
      renderDocumentsWithExternalUrlControl();

      const input = searchInput();
      fireEvent.compositionStart(input);
      fireEvent.change(input, { target: { value: '中文' } });

      // 模拟用户正在拼字时点了浏览器后退：URL 变了，但草稿不能被冲掉。
      await user.click(screen.getByRole('button', { name: 'external-url-change' }));

      await waitFor(() => {
        expect(currentKeyword()).toBe('外部关键词');
      });
      expect(input).toHaveValue('中文');

      // 组合结束后，草稿重新成为权威值并覆盖掉外部来的 URL。
      fireEvent.compositionEnd(input, { data: '中文' });
      await waitFor(() => {
        expect(currentKeyword()).toBe('中文');
      });
    });

    it('规范化关键词：去空白并截断到 256 字符', async () => {
      emptyList();
      renderDocuments();

      const input = searchInput();
      fireEvent.change(input, { target: { value: '  报表  ' } });
      await waitFor(() => {
        expect(currentKeyword()).toBe('报表');
      });

      fireEvent.change(input, { target: { value: 'x'.repeat(300) } });
      await waitFor(() => {
        expect(currentKeyword()).toBe('x'.repeat(256));
      });
    });
  });
});
