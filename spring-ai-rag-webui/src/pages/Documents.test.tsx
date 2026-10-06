import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
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
    // Batch 930. This fixture carries no `lifecycle` object, and the assertion
    // used to read NOT_REQUESTED off that absence. "Nothing was requested" is a
    // claim about the server's work, and the server said nothing at all here, so
    // the badge now says the state was not reported. Everything this test is
    // actually named for — external identity, the retry action, the absence of
    // edit, the externally-managed badge — is unchanged.
    expect(screen.getByText('documents.lifecycle.UNKNOWN')).toBeInTheDocument();
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

  it('explains a failed document even when the server reported no reason', async () => {
    mockUseQuery.mockReturnValue({
      data: {
        data: {
          documents: [{
            id: 9,
            title: 'Corrupt Derivation Doc',
            content: 'Content whose vectors disagree with its state row',
            contentHash: 'corrupt123',
            documentType: 'JSON_RECORD',
            documentRevision: 1,
            createdAt: '2024-01-01T00:00:00Z',
            updatedAt: '2024-01-01T00:00:00Z',
            embeddingFresh: false,
            enabled: true,
            // This is the shape the server produces for a derivation the
            // integrity repository calls CORRUPT: the state row says COMPLETED
            // and the vectors are present, so nothing failed and there is no
            // error to relay. Measured, not invented — see Batch 928.
            lifecycle: {
              documentState: 'ACTIVE',
              searchability: 'FAILED',
              localIndexStatus: 'READY',
              embeddingStatus: 'FAILED',
              lastError: null,
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

    const status = screen.getByText('documents.lifecycle.FAILED');
    // A red badge that says nothing is the defect: before this, `title` was
    // absent entirely, because the tooltip and the inline error block are both
    // gated on the two error fields this document leaves null.
    expect(status).toHaveAttribute('title', 'documents.failedWithoutReasonHint');
    // It must not invent a cause. The hint names both possibilities instead.
    expect(screen.queryByText('provider unavailable')).not.toBeInTheDocument();
  });

  it('prefers a real error over the no-reason hint when the server sends one', async () => {
    mockUseQuery.mockReturnValue({
      data: {
        data: {
          documents: [{
            id: 10,
            title: 'Provider Failure Doc',
            content: 'Content whose provider call failed',
            contentHash: 'provider123',
            documentType: 'TEXT',
            documentRevision: 1,
            createdAt: '2024-01-01T00:00:00Z',
            updatedAt: '2024-01-01T00:00:00Z',
            embeddingFresh: false,
            enabled: true,
            lifecycle: {
              documentState: 'ACTIVE',
              searchability: 'FAILED',
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

    const status = screen.getByText('documents.lifecycle.FAILED');
    expect(status).toHaveAttribute('title', 'provider unavailable');
    expect(screen.getByText('provider unavailable')).toBeInTheDocument();
  });

  it('does not claim a document was not indexed when the server reported nothing', async () => {
    mockUseQuery.mockReturnValue({
      data: {
        data: {
          documents: [{
            id: 11,
            title: 'No Lifecycle Doc',
            content: 'Content from a response without a lifecycle object',
            contentHash: 'unknown123',
            documentType: 'TEXT',
            documentRevision: 1,
            createdAt: '2024-01-01T00:00:00Z',
            updatedAt: '2024-01-01T00:00:00Z',
            embeddingFresh: false,
            enabled: true,
            // No `lifecycle` key at all. Claiming NOT_REQUESTED here asserts
            // something nobody reported; the badge has to say it does not know.
            lifecycle: undefined,
          }],
          total: 1,
        },
      },
      isPending: false,
      error: null,
    });

    renderDocuments();

    const status = screen.getByText('documents.lifecycle.UNKNOWN');
    expect(status).toHaveAttribute('title', 'documents.unknownStateHint');
    expect(screen.queryByText('documents.lifecycle.NOT_REQUESTED'))
      .not.toBeInTheDocument();
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

    // Batch 940. These cases were deterministic before the search box was debounced:
    // committing from `onChange` was synchronous, so a test could assert straight after
    // `fireEvent`. A 250 ms pause made every one of them depend on the wall clock
    // instead, and on a loaded machine one stopped passing — the assertion was never
    // wrong, only late. The fix is to drive the clock, not to widen the `waitFor`
    // timeout: a longer timeout only makes the same flake rarer.
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    /** Let the search box's pause elapse, then assert synchronously. */
    const settle = async (assertion: () => void) => {
      await act(async () => { await vi.advanceTimersByTimeAsync(300); });
      assertion();
    };

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
      await settle(() => {
        expect(currentKeyword()).toBe('中文');
      });
    });

    it('组合进行中失焦不会把半成品提交出去', async () => {
      emptyList();
      renderDocuments();

      const input = searchInput();
      fireEvent.compositionStart(input);
      fireEvent.change(input, { target: { value: '中文' } });
      fireEvent.blur(input);

      // 用户在组合中途切走焦点：草稿留在输入框里，查询条件保持原样。
      // 时钟要照走一遍再断言：blur 的冲刷在组合中会被 `isBlocked` 拦下并把待提交值
      // 留在原地，所以"没有提交"不是"还没到时间"，必须真的等过了才能这么说。
      await settle(() => {
        expect(input).toHaveValue('中文');
        expect(currentKeyword()).toBe('');
      });
    });

    it('组合进行中外部 URL 变化不覆盖正在输入的草稿', async () => {
      emptyList();
      renderDocumentsWithExternalUrlControl();

      const input = searchInput();
      fireEvent.compositionStart(input);
      fireEvent.change(input, { target: { value: '中文' } });

      // 模拟用户正在拼字时点了浏览器后退：URL 变了，但草稿不能被冲掉。
      // `fireEvent` 而不是 `userEvent`：这一条关心的是"URL 从外部变了"，不是指针
      // 事件序列，而 `userEvent` 的内部等待与假定时器互相等待，整条会 5 秒超时。
      fireEvent.click(screen.getByRole('button', { name: 'external-url-change' }));

      await settle(() => {
        expect(currentKeyword()).toBe('外部关键词');
      });
      expect(input).toHaveValue('中文');

      // 组合结束后，草稿重新成为权威值并覆盖掉外部来的 URL。
      fireEvent.compositionEnd(input, { data: '中文' });
      await settle(() => {
        expect(currentKeyword()).toBe('中文');
      });
    });

    it('规范化关键词：去空白并截断到 256 字符', async () => {
      emptyList();
      renderDocuments();

      const input = searchInput();
      fireEvent.change(input, { target: { value: '  报表  ' } });
      await settle(() => {
        expect(currentKeyword()).toBe('报表');
      });

      fireEvent.change(input, { target: { value: 'x'.repeat(300) } });
      await settle(() => {
        expect(currentKeyword()).toBe('x'.repeat(256));
      });
    });

    it('减回空查询时把 keyword 从 URL 上拿掉', async () => {
      // 清空和填非空走的是同一段代码，所以也一起钉住：URL 上留一个空的 `keyword=`，
      // 下次点进来草稿初值就多出一个无意义的查询条件。
      emptyList();
      renderDocuments();

      const input = searchInput();
      fireEvent.change(input, { target: { value: '报表' } });
      await settle(() => {
        expect(currentKeyword()).toBe('报表');
      });

      fireEvent.change(input, { target: { value: '' } });
      await settle(() => {
        expect(currentKeyword()).toBe('');
      });
    });
  });
});
