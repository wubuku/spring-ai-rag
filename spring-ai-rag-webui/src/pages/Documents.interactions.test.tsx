import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { Documents } from './Documents';
import { documentsApi } from '../api/documents';

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
