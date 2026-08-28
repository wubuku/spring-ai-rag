import { beforeEach, describe, expect, it, vi } from 'vitest';
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

describe('Files', () => {
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
