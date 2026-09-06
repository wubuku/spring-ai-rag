import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { Documents } from './Documents';
import { documentsApi } from '../api/documents';
import { collectionsApi } from '../api/collections';

vi.mock('../api/documents', () => ({
  documentsApi: {
    list: vi.fn(),
    get: vi.fn(),
    update: vi.fn(),
    disable: vi.fn(),
    restore: vi.fn(),
    delete: vi.fn(),
    embed: vi.fn(),
    getVersions: vi.fn(),
    getEmbeddingStatus: vi.fn(),
    reembedMissing: vi.fn(),
    relocate: vi.fn(),
    restoreVersion: vi.fn(),
  },
}));

vi.mock('../api/collections', () => ({
  collectionsApi: {
    list: vi.fn().mockResolvedValue({ data: { collections: [], total: 0 } }),
  },
}));

vi.mock('../api/files', () => ({
  filesApi: {
    getPreviewHtml: vi.fn(),
    getRawFile: vi.fn(),
  },
}));

const mockShowToast = vi.fn();

vi.mock('../components/Toast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}));

const uploadHandlers: Array<Record<string, unknown>> = [];

vi.mock('../hooks/useFileUpload', () => ({
  useFileUpload: (options: Record<string, unknown>) => {
    uploadHandlers.push(options);
    return {
      uploadFiles: vi.fn(),
      isUploading: false,
      uploads: [],
    };
  },
}));

vi.mock('../hooks/useBlobUrlOpener', () => ({
  useBlobUrlOpener: () => vi.fn(),
}));

const LOCAL_DOC = {
  id: 1,
  title: 'Local Doc',
  content: 'Local content',
  contentHash: 'hash-1',
  documentType: 'text',
  documentRevision: 3,
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

function renderDocuments() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/documents']}>
        <Documents />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('Documents deep interactions (real react-query)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [LOCAL_DOC], total: 1 },
    } as never);
    vi.mocked(documentsApi.disable).mockResolvedValue({} as never);
    vi.mocked(documentsApi.getEmbeddingStatus).mockResolvedValue({
      data: {
        totalDocuments: 1,
        withEmbeddings: 1,
        withoutEmbeddings: 0,
        hasMissing: false,
      },
    } as never);
  });

  it('runs the disable flow through its confirmation dialog to the api', async () => {
    const user = userEvent.setup();
    renderDocuments();

    await screen.findByText('Local Doc');
    await user.click(
      screen.getByRole('button', { name: 'documents.openActions' }),
    );
    await user.click(
      screen.getByRole('menuitem', { name: 'documents.disable' }),
    );

    const dialog = await screen.findByRole('dialog', {
      name: 'documents.disable',
    });
    expect(dialog).toHaveTextContent('documents.disableConfirm');

    await user.click(
      await within(dialog).findByRole('button', { name: 'documents.disable' }),
    );

    await waitFor(() => {
      expect(documentsApi.disable).toHaveBeenCalledWith(1, 3);
    });
  });

  it('opens the version history modal from the row menu', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.getVersions).mockResolvedValue({
      data: {
        documentId: 1,
        totalVersions: 1,
        page: 0,
        size: 20,
        versions: [{
          id: 9,
          documentId: 1,
          versionNumber: 1,
          contentHash: 'abcdef12',
          size: 10,
          changeType: 'CREATE',
          changeDescription: 'Initial',
          createdAt: '2026-01-01T00:00:00Z',
        }],
      },
    } as never);

    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(
      screen.getByRole('button', { name: 'documents.openActions' }),
    );
    await user.click(
      screen.getByRole('menuitem', { name: 'versions.button' }),
    );

    // The uninitialized i18n mock resolves t() to the raw key; the dialog
    // title is "versions.title — Local Doc".
    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('versions.title');
    expect(dialog.textContent).toContain('Local Doc');
  });
});

// ─── Preview dialog & relocate flow (Batch 53) ──────────────────────

const EXTERNAL_DOC = {
  id: 2,
  title: 'External Doc',
  content: '',
  contentHash: 'hash-2',
  documentType: 'text',
  externalId: 'cms:article:1',
  sourceNamespace: 'crm',
  sourceRevision: 'etag:2',
  collectionKey: 'source-col',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

describe('Documents preview and relocate flows', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(documentsApi.getEmbeddingStatus).mockResolvedValue({
      data: {
        totalDocuments: 2,
        withEmbeddings: 2,
        withoutEmbeddings: 0,
        hasMissing: false,
      },
    } as never);
  });

  it('opens the preview dialog and fetches the full document', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [{ ...LOCAL_DOC, content: '' }], total: 1 },
    } as never);
    vi.mocked(documentsApi.get).mockResolvedValue({
      data: { ...LOCAL_DOC, content: 'full content from get' },
    } as never);

    renderDocuments();

    await user.click(await screen.findByText('Local Doc'));

    expect(documentsApi.get).toHaveBeenCalledWith(1);
    expect(await screen.findByText('full content from get')).toBeInTheDocument();
  });

  it('runs the relocate flow for an externally managed document', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [EXTERNAL_DOC], total: 1 },
    } as never);
    vi.mocked(documentsApi.get).mockResolvedValue({
      data: {
        ...EXTERNAL_DOC,
        content: 'ext content',
        collectionKey: 'source-col',
        sourceNamespace: 'crm',
        sourceRevision: 'etag:2',
        externalId: 'cms:article:1',
      },
    } as never);
    vi.mocked(collectionsApi.list).mockResolvedValue({
      data: {
        collections: [
          { id: 10, collectionKey: 'source-col', name: 'Source', enabled: true },
          { id: 11, collectionKey: 'target-col', name: 'Target', enabled: true },
        ],
        total: 2,
      },
    } as never);
    vi.mocked(documentsApi.relocate).mockResolvedValue({
      data: { documentId: 2 } as never,
    } as never);

    renderDocuments();
    await screen.findByText('External Doc');

    // 打开行菜单并选择 relocate
    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(
      screen.getByRole('menuitem', { name: 'documents.relocate' }),
    );

    // documentsApi.get(id) 先取详情
    await waitFor(() => {
      expect(documentsApi.get).toHaveBeenCalledWith(2);
    });

    // 选择目标集合并提交：relocate 是 form（aria-label=relocateTitle）的 submit
    const targetSelect = screen.getAllByRole('combobox').at(-1)!;
    await user.selectOptions(targetSelect, 'target-col');
    await user.click(screen.getByRole('button', { name: 'documents.relocateConfirm' }));

    await waitFor(() => {
      expect(documentsApi.relocate).toHaveBeenCalledWith(
        {
          sourceCollectionKey: 'source-col',
          targetCollectionKey: 'target-col',
          sourceNamespace: 'crm',
          externalId: 'cms:article:1',
          expectedSourceRevision: 'etag:2',
        },
        expect.any(String),
      );
    });
  });
});

// ─── Preview degrade path (Batch 55) ────────────────────────────────

describe('Documents preview degrade path', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(documentsApi.getEmbeddingStatus).mockResolvedValue({
      data: {
        totalDocuments: 1,
        withEmbeddings: 1,
        withoutEmbeddings: 0,
        hasMissing: false,
      },
    } as never);
  });

  it('keeps the preview open with list data when the detail fetch fails', async () => {
    const user = userEvent.setup();
    // 列表返回的 content 为空（列表 API 不回传正文），detail get 失败。
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [{ ...LOCAL_DOC, content: '' }], total: 1 },
    } as never);
    vi.mocked(documentsApi.get).mockRejectedValue(new Error('get failed'));

    renderDocuments();
    await user.click(await screen.findByText('LOCAL_DOC' in {} ? 'Local Doc' : 'Local Doc'));

    // 预览仍以列表数据打开，且 get 被尝试过一次
    await waitFor(() => {
      expect(documentsApi.get).toHaveBeenCalledWith(1);
    });
    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('Local Doc');
  });
});

// ─── Edit save flow (Batch 62) ──────────────────────────────────────

describe('Documents edit save flow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [LOCAL_DOC], total: 1 },
    } as never);
    vi.mocked(documentsApi.get).mockResolvedValue({
      data: { ...LOCAL_DOC, content: 'loaded content', source: 'loaded-src' },
    } as never);
    vi.mocked(documentsApi.update).mockResolvedValue({
      data: {
        documentId: 1,
        action: 'UPDATED',
        documentRevision: 4,
        versionNumber: 2,
        contentChanged: true,
        metadataChanged: false,
        scopeChanged: false,
      },
    } as never);
  });

  it('loads the document on edit and submits revision-guarded update', async () => {
    const user = userEvent.setup();
    renderDocuments();
    await screen.findByText('Local Doc');

    await user.click(
      screen.getByRole('button', { name: 'documents.openActions' }),
    );
    await user.click(screen.getByRole('menuitem', { name: 'documents.edit' }));

    // handleEdit 先经 documentsApi.get 加载详情
    await waitFor(() => {
      expect(documentsApi.get).toHaveBeenCalledWith(1);
    });

    // 修改标题与正文后提交
    const titleInput = screen.getByDisplayValue('Local Doc');
    await user.clear(titleInput);
    await user.type(titleInput, 'Renamed Doc');
    const contentArea = screen.getByDisplayValue('loaded content');
    await user.clear(contentArea);
    await user.type(contentArea, 'new content');

    const dialog = screen.getByRole('dialog', {
      name: 'documents.editDocument',
    });
    await user.click(
      within(dialog).getByRole('button', { name: 'common.save' }),
    );

    await waitFor(() => {
      expect(documentsApi.update).toHaveBeenCalledWith(
        1,
        expect.objectContaining({
          expectedDocumentRevision: 3,
          title: 'Renamed Doc',
          content: 'new content',
        }),
      );
    });
  });
});

// ─── Mutation error paths and pagination (Batch 66) ──────────────────

describe('Documents mutation error paths and pagination', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [LOCAL_DOC], total: 1 },
    } as never);
    vi.mocked(documentsApi.getEmbeddingStatus).mockResolvedValue({
      data: {
        totalDocuments: 1, withEmbeddings: 1, withoutEmbeddings: 0, hasMissing: false,
      },
    } as never);
  });

  it('shows revision conflict toast on 409 during disable', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.disable).mockRejectedValue(
      Object.assign(new Error('Conflict'), { response: { status: 409 }, status: 409 }),
    );

    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(
      screen.getByRole('button', { name: 'documents.openActions' }),
    );
    await user.click(screen.getByRole('menuitem', { name: 'documents.disable' }));
    await user.click(
      within(
        screen.getByRole('dialog', { name: 'documents.disable' }),
      ).getByRole('button', { name: 'documents.disable' }),
    );

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'documents.revisionConflict', 'error',
      );
    });
  });

  it('navigates to next page via pagination controls', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: {
        documents: [LOCAL_DOC],
        total: 25,
      },
    } as never);

    renderDocuments();
    await screen.findByText('Local Doc');

    const nextBtn = screen.getByRole('button', { name: /common\.next/i });
    expect(nextBtn).toBeEnabled();
    await user.click(nextBtn);

    await waitFor(() => {
      expect(documentsApi.list).toHaveBeenCalledWith(
        expect.objectContaining({ page: 1 }),
      );
    });
  });

  it('triggers embed retry from the row menu', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: {
        documents: [{ ...LOCAL_DOC, embeddingFresh: false }],
        total: 1,
      },
    } as never);

    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(
      screen.getByRole('button', { name: 'documents.openActions' }),
    );
    await user.click(
      screen.getByRole('menuitem', { name: 'documents.retryEmbedding' }),
    );

    await waitFor(() => {
      expect(documentsApi.embed).toHaveBeenCalledWith(
        expect.anything(),
        expect.anything(),
      );
    });
  });
});

// ─── Restore, delete and search flows (Batch 77) ────────────────────

describe('Documents restore, delete and search flows', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [LOCAL_DOC], total: 1 },
    } as never);
    vi.mocked(documentsApi.getEmbeddingStatus).mockResolvedValue({
      data: {
        totalDocuments: 1,
        withEmbeddings: 1,
        withoutEmbeddings: 0,
        hasMissing: false,
      },
    } as never);
  });

  it('restores a disabled document from the row menu', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [{ ...LOCAL_DOC, enabled: false }], total: 1 },
    } as never);
    vi.mocked(documentsApi.restore).mockResolvedValue({} as never);

    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(screen.getByRole('menuitem', { name: 'documents.restore' }));

    await waitFor(() => {
      expect(documentsApi.restore).toHaveBeenCalledWith(1, 3, 'ASYNC');
    });
    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith('documents.restored', 'success');
    });
  });

  it('runs the permanent delete through its confirmation dialog', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.delete).mockResolvedValue({} as never);

    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(
      screen.getByRole('menuitem', { name: 'documents.permanentDelete' }),
    );

    const dialog = await screen.findByRole('dialog', {
      name: 'documents.permanentDelete',
    });
    expect(dialog).toHaveTextContent('documents.permanentDeleteConfirm');

    await user.click(
      await within(dialog).findByRole('button', {
        name: 'documents.permanentDelete',
      }),
    );

    await waitFor(() => {
      expect(documentsApi.delete).toHaveBeenCalledWith(1, 3);
    });
    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'documents.permanentlyDeleted',
        'success',
      );
    });
  });

  it('does not delete when the confirmation dialog is cancelled', async () => {
    const user = userEvent.setup();

    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(
      screen.getByRole('menuitem', { name: 'documents.permanentDelete' }),
    );

    const dialog = await screen.findByRole('dialog', {
      name: 'documents.permanentDelete',
    });
    await user.click(
      await within(dialog).findByRole('button', { name: 'common.cancel' }),
    );

    expect(documentsApi.delete).not.toHaveBeenCalled();
  });

  it('shows a mapped error toast when relocation fails', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [EXTERNAL_DOC], total: 1 },
    } as never);
    vi.mocked(documentsApi.get).mockResolvedValue({
      data: {
        ...EXTERNAL_DOC,
        content: 'ext content',
        collectionKey: 'source-col',
        sourceNamespace: 'crm',
        sourceRevision: 'etag:2',
        externalId: 'cms:article:1',
      },
    } as never);
    vi.mocked(collectionsApi.list).mockResolvedValue({
      data: {
        collections: [
          { id: 10, collectionKey: 'source-col', name: 'Source', enabled: true },
          { id: 11, collectionKey: 'target-col', name: 'Target', enabled: true },
        ],
        total: 2,
      },
    } as never);
    vi.mocked(documentsApi.relocate).mockRejectedValue({
      response: { data: { error: 'TARGET_COLLECTION_NOT_FOUND' } },
    } as never);

    renderDocuments();
    await screen.findByText('External Doc');
    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(screen.getByRole('menuitem', { name: 'documents.relocate' }));
    await waitFor(() => {
      expect(documentsApi.get).toHaveBeenCalledWith(2);
    });

    const targetSelect = screen.getAllByRole('combobox').at(-1)!;
    await user.selectOptions(targetSelect, 'target-col');
    await user.click(
      screen.getByRole('button', { name: 'documents.relocateConfirm' }),
    );

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'documents.relocationErrors.TARGET_COLLECTION_NOT_FOUND',
        'error',
      );
    });
  });

  it('pushes the keyword filter into the search params and clears it', async () => {
    const user = userEvent.setup();

    renderDocuments();
    await screen.findByText('Local Doc');

    await user.type(
      screen.getByRole('textbox', { name: 'documents.searchPlaceholder' }),
      'spec',
    );

    await waitFor(() => {
      expect(documentsApi.list).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'spec' }),
      );
    });

    // 输入非空 keyword 后出现清除按钮，点击后移除过滤并重新拉取。
    await user.click(screen.getByRole('button', { name: 'documents.clearSearch' }));

    await waitFor(() => {
      expect(documentsApi.list).toHaveBeenCalledWith(
        expect.objectContaining({ title: undefined }),
      );
    });
  });

  it('restores a version through the confirmation dialog', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.restoreVersion).mockResolvedValue({} as never);
    vi.mocked(documentsApi.getVersions).mockResolvedValue({
      data: {
        documentId: 1,
        totalVersions: 1,
        versions: [{
          id: 5,
          documentId: 1,
          versionNumber: 2,
          contentHash: 'hash-2',
          size: 100,
          changeType: 'UPDATE',
          changeDescription: 'older revision',
          snapshotCompleteness: 'FULL',
          createdAt: '2026-01-02T00:00:00Z',
        }],
      },
    } as never);

    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(screen.getByRole('menuitem', { name: 'versions.button' }));

    // 版本历史模态内点击版本行的恢复按钮 → 页面级确认对话框。
    const restoreButtons = await screen.findAllByRole('button', {
      name: 'versions.restore',
    });
    await user.click(restoreButtons[0]);

    const dialog = await screen.findByRole('dialog', {
      name: 'versions.restore',
    });
    await user.click(
      await within(dialog).findByRole('button', { name: 'versions.restore' }),
    );

    await waitFor(() => {
      expect(documentsApi.restoreVersion).toHaveBeenCalledWith(
        1, 2, 3, 'ASYNC', 'KEEP_CURRENT',
      );
    });
    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith('versions.restored', 'success');
    });
  });

  it('surfaces upload completion and failure through callbacks', async () => {
    renderDocuments();
    await screen.findByText('Local Doc');

    const options = uploadHandlers.at(-1) as {
      onComplete: (name: string) => void;
      onError: (name: string, message: string) => void;
    };
    options.onComplete('doc.pdf');
    expect(mockShowToast).toHaveBeenCalledWith(
      'doc.pdf documents.uploaded', 'success',
    );

    options.onError('doc.pdf', 'disk full');
    expect(mockShowToast).toHaveBeenCalledWith('doc.pdf: disk full', 'error');
  });

  it('pushes the collection filter into the document list query', async () => {
    const user = userEvent.setup();
    vi.mocked(collectionsApi.list).mockResolvedValue({
      data: {
        collections: [
          { id: 10, collectionKey: 'kb', name: 'Knowledge Base', enabled: true },
        ],
        total: 1,
      },
    } as never);

    renderDocuments();
    await screen.findByText('Local Doc');

    await user.selectOptions(
      screen.getByTestId('documents-collection-filter'),
      'kb',
    );

    await waitFor(() => {
      expect(documentsApi.list).toHaveBeenCalledWith(
        expect.objectContaining({ collectionKey: 'kb' }),
      );
    });
  });
})
