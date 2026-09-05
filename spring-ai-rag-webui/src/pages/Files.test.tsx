import { beforeEach, describe, expect, it, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { Files } from './Files';
import { filesApi } from '../api/files';

const mockUseQuery = vi.fn();
const mockShowToast = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
  useQueryClient: () => ({
    invalidateQueries: vi.fn().mockResolvedValue(undefined),
  }),
}));

vi.mock('../api/files', () => ({
  filesApi: {
    listTree: vi.fn(),
    importPdf: vi.fn(),
    triggerEmbedding: vi.fn(),
    getRawFile: vi.fn(),
    getPreviewHtml: vi.fn(),
  },
}));

vi.mock('../api/collections', () => ({
  collectionsApi: {
    list: vi.fn(),
  },
}));

vi.mock('../components/Toast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}));

vi.mock('../components/FilePreview/FilePreview', () => ({
  FilePreview: ({ entry }: { entry: { path: string } }) => (
    <div data-testid="file-preview">{entry.path}</div>
  ),
}));

const LocationProbe = () => {
  const location = useLocation();
  return <output data-testid="location-search">{location.search}</output>;
};

const renderFiles = (
  initialEntry = window.location.pathname + window.location.search,
) => render(
  <MemoryRouter initialEntries={[initialEntry]}>
    <Files />
    <LocationProbe />
  </MemoryRouter>,
);

describe('Files', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.sessionStorage.clear();
    window.history.replaceState({}, '', '/webui/files');
    (filesApi.triggerEmbedding as ReturnType<typeof vi.fn>).mockResolvedValue({
      documentId: 1,
      title: 'Sample PDF',
      newlyCreated: true,
      embedStatus: 'COMPLETED',
      embedMessage: null,
      chunksCreated: 3,
      uuid: 'sample-pdf',
      entryMarkdown: 'sample-pdf/document.md',
    });
    mockUseQuery.mockImplementation((options: { queryKey: unknown[] }) => {
      if (options.queryKey[0] === 'files-collections') {
        return {
          data: {
            data: {
              collections: [
                {
                  id: 10,
                  collectionKey: 'customer:manual',
                  name: 'Knowledge Base',
                  documentCount: 0,
                },
              ],
            },
          },
        };
      }
      const path = String(options.queryKey[1] ?? '');
      return {
        data: {
          data: {
            path,
            entries: path
              ? path === 'sample-pdf/'
                ? [
                    {
                      name: 'default.md',
                      path: 'sample-pdf/default.md',
                      type: 'file',
                      mimeType: 'text/markdown',
                      size: 100,
                      createdAt: '2026-08-15T09:00:00Z',
                    },
                  ]
                : []
              : [
                  {
                    name: 'older-pdf',
                    path: 'older-pdf/',
                    type: 'directory',
                    mimeType: null,
                    size: 0,
                    createdAt: '2026-08-14T09:00:00Z',
                  },
                    {
                      name: 'sample-pdf',
                      path: 'sample-pdf/',
                      type: 'directory',
                      mimeType: null,
                      size: 0,
                      createdAt: '2026-08-15T09:00:00Z',
                      displayName: 'Readable manual.pdf',
                      originalFilename: 'Readable manual.pdf',
                      importId: 'sample-pdf',
                      sourceType: 'PDF',
                    },
                  {
                    name: 'newest-pdf',
                    path: 'newest-pdf/',
                    type: 'directory',
                    mimeType: null,
                    size: 0,
                    createdAt: '2026-08-16T09:00:00Z',
                  },
                ],
            total: path ? 0 : 3,
          },
        },
        isPending: false,
        error: null,
        refetch: vi.fn(),
      };
    });
  });

  it('passes the selected collection key when embedding an imported PDF', async () => {
    renderFiles();

    fireEvent.click(screen.getByText('sample-pdf'));
    fireEvent.change(screen.getByTestId('files-rag-collection-select'), {
      target: { value: 'customer:manual' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'files.addToRag' }));

    await waitFor(() => {
      expect(filesApi.triggerEmbedding).toHaveBeenCalledWith(
        'sample-pdf',
        undefined,
        false,
        'customer:manual',
      );
    });
  });

  it('sorts imports newest first by default and toggles to oldest first', () => {
    renderFiles();

    const paths = () => screen.getAllByTestId('file-tree-entry')
      .map(entry => entry.getAttribute('data-entry-path'));

    expect(paths()).toEqual(['newest-pdf/', 'sample-pdf/', 'older-pdf/']);

    fireEvent.click(screen.getByTestId('files-import-time-sort'));

    expect(paths()).toEqual(['older-pdf/', 'sample-pdf/', 'newest-pdf/']);
  });

  it('restores ascending import-time sorting from a direct URL', () => {
    renderFiles('/webui/files?sort=asc');

    const paths = screen.getAllByTestId('file-tree-entry')
      .map(entry => entry.getAttribute('data-entry-path'));

    expect(paths).toEqual(['older-pdf/', 'sample-pdf/', 'newest-pdf/']);
  });

  it('opens a safe file deep link and previews the indexed file', async () => {
    window.history.replaceState(
      {},
      '',
      '/webui/files?path=sample-pdf%2F&file=sample-pdf%2Fdefault.md',
    );

    renderFiles();

    expect(await screen.findByTestId('file-preview'))
      .toHaveTextContent('sample-pdf/default.md');
    expect(screen.getByTitle('sample-pdf/')).toBeInTheDocument();
  });

  it('updates the preview when the file changes within the same directory', async () => {
    window.history.replaceState(
      {},
      '',
      '/webui/files?path=sample-pdf%2F',
    );

    renderFiles();

    await screen.findByTitle('default.md');
    fireEvent.click(screen.getByTitle('default.md'));

    expect(await screen.findByTestId('file-preview'))
      .toHaveTextContent('sample-pdf/default.md');
  });

  it('rejects unsafe deep links and falls back to the root directory', () => {
    window.history.replaceState(
      {},
      '',
      '/webui/files?path=..%2Fsecret%2F&file=..%2Fsecret%2Fdefault.md',
    );

    renderFiles();

    expect(screen.getByTitle('files.root')).toBeInTheDocument();
    expect(screen.queryByTestId('file-preview')).not.toBeInTheDocument();
  });

  it('shows a readable imported name, keeps the UUID visible, and filters both', () => {
    renderFiles('/webui/files?q=readable');

    expect(screen.getByText('Readable manual.pdf')).toBeVisible();
    expect(screen.getByText('sample-pdf')).toBeVisible();
    expect(screen.getAllByTestId('file-tree-entry')).toHaveLength(1);
  });

  it('restores the selected RAG collection after the page is remounted', () => {
    const first = renderFiles('/webui/files?path=sample-pdf%2F');
    fireEvent.change(screen.getByTestId('files-rag-collection-select'), {
      target: { value: 'customer:manual' },
    });
    first.unmount();

    renderFiles('/webui/files?path=sample-pdf%2F');
    expect(screen.getByTestId('files-rag-collection-select'))
      .toHaveValue('customer:manual');
  });

  it('keeps the command bar mounted while directory context changes', () => {
    renderFiles();

    const commandBar = screen.getByTestId('files-command-bar');
    expect(screen.queryByTestId('files-rag-actions')).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('sample-pdf'));

    expect(screen.getByTestId('files-command-bar')).toBe(commandBar);
    expect(screen.getByTestId('location-search')).toHaveTextContent(/^$/);
    expect(screen.getByTestId('files-rag-actions')).toBeVisible();
    expect(screen.getByTestId('files-rag-collection-select')).toBeEnabled();
    expect(screen.getByRole('button', { name: 'files.addToRag' })).toBeEnabled();
  });

  it('selects a folder on click and opens it on double-click or Enter', () => {
    renderFiles();

    const folder = screen.getByTitle('Readable manual.pdf');
    fireEvent.click(folder);
    expect(screen.getByTestId('location-search')).toHaveTextContent(/^$/);
    expect(screen.getByText('files.openFolder')).toBeVisible();

    fireEvent.keyDown(folder, { key: 'Enter' });
    expect(screen.getByTestId('location-search')).toHaveTextContent(
      '?path=sample-pdf%2F',
    );
  });

  it('keeps the parent entry available when a folder is empty or filtered out', () => {
    renderFiles('/webui/files?path=sample-pdf%2F&q=missing');

    expect(screen.getByTestId('files-parent-entry')).toBeVisible();
    expect(screen.getByText('files.noMatches')).toBeVisible();
  });

  it('does not navigate during Chinese IME composition and commits after it ends', async () => {
    renderFiles();
    const input = screen.getByLabelText('files.searchLabel');

    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: '中文' } });
    await new Promise(resolve => window.setTimeout(resolve, 300));

    expect(input).toHaveValue('中文');
    expect(screen.getByTestId('location-search')).toHaveTextContent(/^$/);

    fireEvent.compositionEnd(input, { data: '中文' });

    await waitFor(() => {
      expect(screen.getByTestId('location-search'))
        .toHaveTextContent('?q=%E4%B8%AD%E6%96%87');
    });
  });

  it('resizes the directory panel with the keyboard and remembers the width', async () => {
    renderFiles();
    const splitter = screen.getByRole('separator', {
      name: 'files.resizeDirectoryList',
    });

    expect(splitter).toHaveAttribute('aria-valuenow', '320');
    fireEvent.keyDown(splitter, { key: 'ArrowRight' });
    expect(splitter).toHaveAttribute('aria-valuenow', '336');

    await waitFor(() => {
      expect(window.sessionStorage.getItem('spring-ai-rag:webui:v1:files-layout'))
        .toBe(JSON.stringify({ treePanelWidth: 336 }));
    });
  });
});

// ─── Embedding outcomes, raw opening, upload and pointer resize (Batch 78) ──

describe('Files embedding outcomes and raw opening', () => {
  // 进入导入目录但未选中文件，命令栏的 Add to RAG 面板才会渲染。
  const deepLinkIntoPdf = () => {
    window.history.replaceState(
      {},
      '',
      '/webui/files?path=sample-pdf%2F',
    );
  };

  beforeEach(() => {
    vi.clearAllMocks();
    window.sessionStorage.clear();
    window.history.replaceState({}, '', '/webui/files');
    mockUseQuery.mockImplementation((options: { queryKey: unknown[] }) => {
      if (options.queryKey[0] === 'files-collections') {
        return { data: { data: { collections: [] } } };
      }
      const path = String(options.queryKey[1] ?? '');
      return {
        data: {
          data: {
            path,
            entries: path === 'sample-pdf/'
              ? [{
                  name: 'default.md',
                  path: 'sample-pdf/default.md',
                  type: 'file',
                  mimeType: 'text/markdown',
                  size: 100,
                  createdAt: '2026-08-15T09:00:00Z',
                }]
              : [],
            total: 1,
          },
        },
        isPending: false,
        error: null,
        refetch: vi.fn(),
      };
    });
  });

  async function openAddToRag() {
    deepLinkIntoPdf();
    renderFiles();
    const button = await screen.findByRole('button', { name: 'files.addToRag' });
    await userEvent.setup().click(button);
    return button;
  }

  it('reports a cached embedding as an info toast', async () => {
    (filesApi.triggerEmbedding as ReturnType<typeof vi.fn>).mockResolvedValue({
      embedStatus: 'CACHED',
      embedMessage: 'already indexed',
      chunksCreated: 0,
    });

    await openAddToRag();

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith('files.embedCached', 'info');
    });
  });

  it('reports a failed embedding with its message', async () => {
    (filesApi.triggerEmbedding as ReturnType<typeof vi.fn>).mockResolvedValue({
      embedStatus: 'FAILED',
      embedMessage: 'provider down',
      chunksCreated: null,
    });

    await openAddToRag();

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'files.embedFailed',
        'error',
      );
    });
    expect(screen.getByText('provider down')).toBeInTheDocument();
  });

  it('reports an embedding request failure through the alert role', async () => {
    (filesApi.triggerEmbedding as ReturnType<typeof vi.fn>)
      .mockRejectedValue(new Error('network gone'));

    await openAddToRag();

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'files.embedError',
        'error',
      );
    });
    expect(screen.getByRole('alert')).toHaveTextContent('network gone');
  });

  it('opens the raw file as a blob and surfaces failures as a toast', async () => {
    const objectUrl = 'blob:mock-url';
    const createObjectURL = vi.fn().mockReturnValue(objectUrl);
    URL.createObjectURL = createObjectURL as unknown as typeof URL.createObjectURL;
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    const user = userEvent.setup();

    // 进入目录后点击文件打开预览面板。
    window.history.replaceState({}, '', '/webui/files?path=sample-pdf%2F');
    renderFiles();
    fireEvent.click(await screen.findByTitle('default.md'));
    await screen.findByTestId('file-preview');
    (filesApi.getRawFile as ReturnType<typeof vi.fn>).mockResolvedValue(
      new Blob(['raw']),
    );

    await user.click(screen.getByRole('button', { name: 'files.openRaw' }));

    await waitFor(() => {
      expect(createObjectURL).toHaveBeenCalled();
      expect(openSpy).toHaveBeenCalledWith(
        objectUrl, '_blank', 'noopener,noreferrer',
      );
    });

    (filesApi.getRawFile as ReturnType<typeof vi.fn>)
      .mockRejectedValue(new Error('binary missing'));
    await user.click(screen.getByRole('button', { name: 'files.openRaw' }));

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'files.previewError',
        'error',
      );
    });
    openSpy.mockRestore();
  });
});

describe('Files upload and pointer resize', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.sessionStorage.clear();
    window.history.replaceState({}, '', '/webui/files');
    mockUseQuery.mockImplementation((options: { queryKey: unknown[] }) => {
      if (options.queryKey[0] === 'files-collections') {
        return { data: { data: { collections: [] } } };
      }
      const path = String(options.queryKey[1] ?? '');
      return {
        data: {
          data: { path, entries: [], total: 0 },
        },
        isPending: false,
        error: null,
        refetch: vi.fn(),
      };
    });
  });

  it('rejects non-pdf uploads before calling the import api', async () => {
    renderFiles();
    await screen.findByText('files.title');

    // userEvent.upload 会遵守 accept=".pdf" 而跳过 .txt，这里直接派发 change。
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', {
      value: [new File(['x'], 'notes.txt')],
    });
    fireEvent.change(input);

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith('files.onlyPdf', 'error');
    });
    expect(filesApi.importPdf).not.toHaveBeenCalled();
  });

  it('imports a pdf and navigates into its new directory', async () => {
    (filesApi.importPdf as ReturnType<typeof vi.fn>).mockResolvedValue({
      uuid: 'fresh-import',
      filesStored: 4,
    });

    renderFiles();
    await screen.findByText('files.title');

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    await userEvent.setup().upload(input, new File(['pdf'], 'doc.pdf'));

    await waitFor(() => {
      expect(filesApi.importPdf).toHaveBeenCalledWith(expect.any(File));
    });
    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'files.importSuccess',
        'success',
      );
      expect(screen.getByTestId('location-search')).toHaveTextContent(
        'path=fresh-import%2F',
      );
    });
  });

  it('resizes the directory panel by pointer drag and stops on release', async () => {
    if (!HTMLElement.prototype.setPointerCapture) {
      HTMLElement.prototype.setPointerCapture = vi.fn();
      HTMLElement.prototype.releasePointerCapture = vi.fn();
      HTMLElement.prototype.hasPointerCapture = vi.fn().mockReturnValue(true);
    }

    renderFiles();
    await screen.findByText('files.title');

    const splitter = screen.getByRole('separator', {
      name: 'files.resizeDirectoryList',
    });
    expect(splitter).toHaveAttribute('aria-valuenow', '320');

    fireEvent.pointerDown(splitter, { button: 0, pointerId: 7, clientX: 300 });
    fireEvent.pointerMove(splitter, { pointerId: 7, clientX: 380 });
    expect(splitter).toHaveAttribute('aria-valuenow', '400');

    // 另一个 pointerId 的移动不生效。
    fireEvent.pointerMove(splitter, { pointerId: 9, clientX: 500 });
    expect(splitter).toHaveAttribute('aria-valuenow', '400');

    fireEvent.pointerUp(splitter, { pointerId: 7 });
    fireEvent.pointerMove(splitter, { pointerId: 7, clientX: 520 });
    expect(splitter).toHaveAttribute('aria-valuenow', '400');

    // Home 键回到最小宽度。
    fireEvent.keyDown(splitter, { key: 'Home' });
    expect(splitter).toHaveAttribute('aria-valuenow', '240');
  });
});
