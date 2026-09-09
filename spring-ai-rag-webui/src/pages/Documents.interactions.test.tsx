import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
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


describe('Documents edit selects and relocate cancel', () => {
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

  const LOCAL_DOC = {
    id: 3,
    title: 'Local Doc',
    content: '',
    contentHash: 'hash-3',
    documentType: 'text',
    documentRevision: 3,
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };

  it('fires onChange for the edit dialog collection and policy selects', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [LOCAL_DOC], total: 1 },
    } as never);
    vi.mocked(documentsApi.get).mockResolvedValue({
      data: {
        ...LOCAL_DOC,
        content: 'loaded content',
      },
    } as never);
    vi.mocked(documentsApi.update).mockResolvedValue({
      data: { ...LOCAL_DOC } as never,
    } as never);

    renderDocuments();
    await screen.findByText('Local Doc');

    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(screen.getByRole('menuitem', { name: 'documents.edit' }));
    const dialog = await screen.findByRole('dialog', {
      name: 'documents.editDocument',
    });

    // 触发集合与嵌入策略下拉的 onChange。
    const selects = within(dialog).getAllByRole('combobox');
    for (const select of selects) {
      await user.selectOptions(select, (select as HTMLSelectElement).value);
    }

    await user.click(
      within(dialog).getByRole('button', { name: 'common.save' }),
    );
    await waitFor(() => expect(documentsApi.update).toHaveBeenCalled());
  });

  it('closes the relocate dialog via cancel without calling the api', async () => {
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

    renderDocuments();
    await screen.findByText('External Doc');

    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(
      screen.getByRole('menuitem', { name: 'documents.relocate' }),
    );
    const dialog = await screen.findByRole('dialog', {
      name: 'documents.relocateTitle',
    });
    await user.click(
      within(dialog).getByRole('button', { name: 'common.cancel' }),
    );

    expect(screen.queryByRole('dialog', {
      name: 'documents.relocateTitle',
    })).not.toBeInTheDocument();
    expect(documentsApi.relocate).not.toHaveBeenCalled();
  });
});

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
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [{ ...LOCAL_DOC, content: '' }], total: 1 },
    } as never);
    vi.mocked(documentsApi.get).mockRejectedValue(new Error('get failed'));

    renderDocuments();
    await user.click(await screen.findByText('Local Doc'));

    await waitFor(() => {
      expect(documentsApi.get).toHaveBeenCalledWith(1);
    });
    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('Local Doc');
  });
});

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

    await waitFor(() => {
      expect(documentsApi.get).toHaveBeenCalledWith(1);
    });

    // 等 handleEdit 的 setState 生效（表单回填 detail 值）
    await waitFor(() => {
      expect(screen.getByDisplayValue('Local Doc')).toBeInTheDocument();
    });

    await waitFor(() => {
      expect(screen.getByDisplayValue('loaded content')).toBeInTheDocument();
    });

    const titleInput = screen.getByDisplayValue('Local Doc');
    await user.clear(titleInput);
    await user.type(titleInput, 'Renamed Doc');

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
        }),
      );
    });
  });

  it('closes the edit dialog via Escape without calling update', async () => {
    const user = userEvent.setup();
    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(
      screen.getByRole('button', { name: 'documents.openActions' }),
    );
    await user.click(screen.getByRole('menuitem', { name: 'documents.edit' }));
    const dialog = await screen.findByRole('dialog', {
      name: 'documents.editDocument',
    });

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('dialog', {
      name: 'documents.editDocument',
    })).not.toBeInTheDocument();
    expect(documentsApi.update).not.toHaveBeenCalled();
    void dialog;
  });

  it('closes the relocate dialog via Escape without calling the api', async () => {
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

    renderDocuments();
    await screen.findByText('External Doc');
    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(
      screen.getByRole('menuitem', { name: 'documents.relocate' }),
    );
    const dialog = await screen.findByRole('dialog', {
      name: 'documents.relocateTitle',
    });

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('dialog', {
      name: 'documents.relocateTitle',
    })).not.toBeInTheDocument();
    expect(documentsApi.relocate).not.toHaveBeenCalled();
    void dialog;
  });

  it('closes the edit dialog via cancel without calling update', async () => {
    const user = userEvent.setup();
    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(
      screen.getByRole('button', { name: 'documents.openActions' }),
    );
    await user.click(screen.getByRole('menuitem', { name: 'documents.edit' }));

    const cancel = await screen.findByRole('button', { name: 'common.cancel' });
    await user.click(cancel);

    expect(documentsApi.update).not.toHaveBeenCalled();
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

// ─── Mutation error toasts, guards and dialog dismissal (Batch 159) ──

describe('Documents error fallbacks, revision guard and dialog dismissal', () => {
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
    vi.mocked(collectionsApi.list).mockResolvedValue({
      data: {
        collections: [
          { id: 10, collectionKey: 'target-col', name: 'Target', enabled: true },
        ],
        total: 1,
      },
    } as never);
  });

  async function openEditAndSubmit(user: ReturnType<typeof userEvent.setup>) {
    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(
      screen.getByRole('button', { name: 'documents.openActions' }),
    );
    await user.click(screen.getByRole('menuitem', { name: 'documents.edit' }));
    await waitFor(() => {
      expect(screen.getByDisplayValue('Local Doc')).toBeInTheDocument();
    });
    await user.click(
      within(screen.getByRole('dialog', { name: 'documents.editDocument' }))
        .getByRole('button', { name: 'common.save' }),
    );
  }

  it('surfaces the fallback update error toast for non-conflict failures', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.get).mockResolvedValue({
      data: { ...LOCAL_DOC, content: 'loaded content' },
    } as never);
    vi.mocked(documentsApi.update).mockRejectedValue(
      new Error('storage unavailable'),
    );

    await openEditAndSubmit(user);

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'documents.updateError',
        'error',
      );
    });
  });

  it('reports a missing revision guard through the update error toast', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: {
        documents: [{ ...LOCAL_DOC, documentRevision: undefined }],
        total: 1,
      },
    } as never);
    vi.mocked(documentsApi.get).mockResolvedValue({
      data: { ...LOCAL_DOC, documentRevision: undefined, content: 'x' },
    } as never);

    await openEditAndSubmit(user);

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'documents.updateError',
        'error',
      );
    });
    expect(documentsApi.update).not.toHaveBeenCalled();
  });

  it('surfaces the restore error toast when restore fails', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [{ ...LOCAL_DOC, enabled: false }], total: 1 },
    } as never);
    vi.mocked(documentsApi.restore).mockRejectedValue(new Error('busy'));

    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(screen.getByRole('menuitem', { name: 'documents.restore' }));

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'documents.restoreError',
        'error',
      );
    });
  });

  it('surfaces the delete error toast when delete fails', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.delete).mockRejectedValue(new Error('referenced'));

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
      await within(dialog).findByRole('button', {
        name: 'documents.permanentDelete',
      }),
    );

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'documents.deleteError',
        'error',
      );
    });
  });

  it('surfaces the embedding retry error toast when embed fails', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: {
        documents: [{ ...LOCAL_DOC, embeddingFresh: false }],
        total: 1,
      },
    } as never);
    vi.mocked(documentsApi.embed).mockRejectedValue(new Error('queue down'));

    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(
      screen.getByRole('menuitem', { name: 'documents.retryEmbedding' }),
    );

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'documents.embeddingRetryError',
        'error',
      );
    });
  });

  it('reports a detail load failure when opening the edit dialog', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.get).mockRejectedValue(new Error('gone'));

    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(
      screen.getByRole('button', { name: 'documents.openActions' }),
    );
    await user.click(screen.getByRole('menuitem', { name: 'documents.edit' }));

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'documents.loadDetailError',
        'error',
      );
    });
  });

  it('includes source and content edits in the update payload', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.get).mockResolvedValue({
      data: {
        ...LOCAL_DOC,
        content: 'loaded content',
        source: 'manual://v1',
        collectionKey: 'target-col',
      },
    } as never);
    vi.mocked(documentsApi.update).mockResolvedValue({} as never);

    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(
      screen.getByRole('button', { name: 'documents.openActions' }),
    );
    await user.click(screen.getByRole('menuitem', { name: 'documents.edit' }));
    await waitFor(() => {
      expect(screen.getByDisplayValue('manual://v1')).toBeInTheDocument();
    });

    const source = screen.getByDisplayValue('manual://v1');
    await user.clear(source);
    await user.type(source, 'manual://v2');
    const content = screen.getByDisplayValue('loaded content');
    await user.clear(content);
    await user.type(content, 'rewritten body');

    await user.click(
      within(screen.getByRole('dialog', { name: 'documents.editDocument' }))
        .getByRole('button', { name: 'common.save' }),
    );

    await waitFor(() => {
      expect(documentsApi.update).toHaveBeenCalledWith(
        1,
        expect.objectContaining({
          source: 'manual://v2',
          content: 'rewritten body',
          collectionKey: 'target-col',
        }),
      );
    });
  });
});

// ─── Upload zone, filters, pagination and dialog dismissal (Batch 161) ──

describe('Documents upload zone, filter clearing and dialog dismissal', () => {
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
    vi.mocked(collectionsApi.list).mockResolvedValue({
      data: {
        collections: [
          { id: 10, collectionKey: 'kb', name: 'Knowledge Base', enabled: true },
        ],
        total: 1,
      },
    } as never);
  });

  it('forwards selected files from the input through handleFiles', () => {
    renderDocuments();
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['hello'], 'a.txt', { type: 'text/plain' });
    Object.defineProperty(input, 'files', {
      value: [file],
      configurable: true,
    });

    const zone = document.querySelector('[class*="uploadZone"]') as HTMLElement;
    fireEvent.dragOver(zone);
    expect(zone.className).toContain('dragOver');
    fireEvent.dragLeave(zone);

    fireEvent.change(input);
    // 不抛异常且上传区样式复位即视为通过（uploadFiles 由 hook mock 承接）。
    expect(zone.className).not.toContain('dragOver');
  });

  it('clears the collection filter back to all collections', async () => {
    const user = userEvent.setup();
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

    await user.selectOptions(
      screen.getByTestId('documents-collection-filter'),
      '',
    );
    await waitFor(() => {
      expect(documentsApi.list).toHaveBeenCalledWith(
        expect.objectContaining({ collectionKey: undefined }),
      );
    });
  });

  it('navigates back to the first page via the previous control', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [LOCAL_DOC], total: 25 },
    } as never);

    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(screen.getByRole('button', { name: /common\.next/i }));
    await waitFor(() => {
      expect(documentsApi.list).toHaveBeenCalledWith(
        expect.objectContaining({ page: 1 }),
      );
    });

    await user.click(screen.getByRole('button', { name: /common\.previous/i }));
    await waitFor(() => {
      expect(documentsApi.list).toHaveBeenCalledWith(
        expect.objectContaining({ page: 0 }),
      );
    });
  });

  it('closes the preview dialog via its close button', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.get).mockResolvedValue({
      data: { ...LOCAL_DOC, content: 'preview body' },
    } as never);

    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(
      screen.getByRole('button', { name: 'documents.openActions' }),
    );
    await user.click(
      screen.getByRole('menuitem', { name: 'documents.preview' }),
    );
    expect(await screen.findByText('preview body')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByText('preview body')).not.toBeInTheDocument();
  });

  it('closes the versions modal via its close button', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.getVersions).mockResolvedValue({
      data: {
        documentId: 1,
        totalVersions: 1,
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
    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(screen.getByRole('menuitem', { name: 'versions.button' }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('versions.title');
    await user.click(screen.getByRole('button', { name: 'Close' }));

    await waitFor(() => {
      expect(
        screen.queryByRole('dialog', { name: /versions\.title/ }),
      ).not.toBeInTheDocument();
    });
  });

  it('cancels the relocate dialog without calling relocate', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [EXTERNAL_DOC], total: 1 },
    } as never);
    vi.mocked(documentsApi.get).mockResolvedValue({
      data: { ...EXTERNAL_DOC, content: 'ext content' },
    } as never);

    renderDocuments();
    await screen.findByText('External Doc');
    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(screen.getByRole('menuitem', { name: 'documents.relocate' }));
    await waitFor(() => {
      expect(documentsApi.get).toHaveBeenCalledWith(2);
    });

    const dialog = await screen.findByRole('dialog', {
      name: 'documents.relocateTitle',
    });
    await user.click(
      await within(dialog).findByRole('button', { name: 'common.cancel' }),
    );

    await waitFor(() => {
      expect(
        screen.queryByRole('dialog', { name: 'documents.relocateTitle' }),
      ).not.toBeInTheDocument();
    });
    expect(documentsApi.relocate).not.toHaveBeenCalled();
  });

  it('surfaces the fallback relocate toast when the guard rejects', async () => {
    const user = userEvent.setup();
    vi.mocked(collectionsApi.list).mockResolvedValue({
      data: {
        collections: [
          { id: 10, collectionKey: 'target-col', name: 'Target', enabled: true },
        ],
        total: 1,
      },
    } as never);
    const incomplete = { ...EXTERNAL_DOC, sourceRevision: undefined };
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [incomplete], total: 1 },
    } as never);
    vi.mocked(documentsApi.get).mockResolvedValue({
      data: { ...incomplete, content: 'ext content' },
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
      // 守卫抛错无 response.code → 走 relocationErrors.DEFAULT 兜底。
      expect(mockShowToast).toHaveBeenCalledWith(
        'documents.relocationErrors.DEFAULT',
        'error',
      );
    });
    expect(documentsApi.relocate).not.toHaveBeenCalled();
  });

  it('reports a detail load failure when opening the relocate dialog', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [EXTERNAL_DOC], total: 1 },
    } as never);
    vi.mocked(documentsApi.get).mockRejectedValue(new Error('gone'));

    renderDocuments();
    await screen.findByText('External Doc');
    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(screen.getByRole('menuitem', { name: 'documents.relocate' }));

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'documents.loadDetailError',
        'error',
      );
    });
  });
});

// ─── Version restore failure and revision guard (Batch 187) ──────────

describe('Documents revision guard and version restore failure', () => {
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

  it('surfaces the version restore error toast when restore fails', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.getVersions).mockResolvedValue({
      data: {
        documentId: 1,
        totalVersions: 1,
        versions: [{
          id: 5, documentId: 1, versionNumber: 2, contentHash: 'hash-2',
          size: 100, changeType: 'UPDATE', changeDescription: 'older',
          snapshotCompleteness: 'FULL', createdAt: '2026-01-02T00:00:00Z',
        }],
      },
    } as never);
    vi.mocked(documentsApi.restoreVersion).mockRejectedValue(
      new Error('restore conflict'),
    );

    renderDocuments();
    await screen.findByText('Local Doc');
    await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
    await user.click(screen.getByRole('menuitem', { name: 'versions.button' }));

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
      expect(mockShowToast).toHaveBeenCalledWith(
        'versions.restoreError',
        'error',
      );
    });
  });

  it('reports the revision guard through the delete error toast', async () => {
    const user = userEvent.setup();
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: {
        documents: [{ ...LOCAL_DOC, documentRevision: undefined }],
        total: 1,
      },
    } as never);

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
      await within(dialog).findByRole('button', {
        name: 'documents.permanentDelete',
      }),
    );

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'documents.deleteError',
        'error',
      );
    });
    expect(documentsApi.delete).not.toHaveBeenCalled();
  });
});
