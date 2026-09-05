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

vi.mock('../components/Toast', () => ({
  useToast: () => ({ showToast: vi.fn() }),
}));

vi.mock('../hooks/useFileUpload', () => ({
  useFileUpload: () => ({
    uploadFiles: vi.fn(),
    isUploading: false,
    uploads: [],
  }),
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

async function openRowMenu(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByText('Local Doc');
  await user.click(screen.getByRole('button', { name: 'documents.openActions' }));
  return screen.getByRole('menu');
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
