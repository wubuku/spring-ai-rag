import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Files } from './Files';
import { filesApi } from '../api/files';

const mockUseQuery = vi.fn();
const mockShowToast = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
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
  beforeEach(() => {
    vi.clearAllMocks();
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
    render(<Files />);

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
    render(<Files />);

    const paths = () => screen.getAllByTestId('file-tree-entry')
      .map(entry => entry.getAttribute('data-entry-path'));

    expect(paths()).toEqual(['newest-pdf/', 'sample-pdf/', 'older-pdf/']);

    fireEvent.click(screen.getByTestId('files-import-time-sort'));

    expect(paths()).toEqual(['older-pdf/', 'sample-pdf/', 'newest-pdf/']);
  });

  it('opens a safe file deep link and previews the indexed file', async () => {
    window.history.replaceState(
      {},
      '',
      '/webui/files?path=sample-pdf%2F&file=sample-pdf%2Fdefault.md',
    );

    render(<Files />);

    expect(await screen.findByTestId('file-preview'))
      .toHaveTextContent('sample-pdf/default.md');
    expect(screen.getByTitle('sample-pdf/')).toBeInTheDocument();
  });

  it('rejects unsafe deep links and falls back to the root directory', () => {
    window.history.replaceState(
      {},
      '',
      '/webui/files?path=..%2Fsecret%2F&file=..%2Fsecret%2Fdefault.md',
    );

    render(<Files />);

    expect(screen.getByTitle('files.root')).toBeInTheDocument();
    expect(screen.queryByTestId('file-preview')).not.toBeInTheDocument();
  });
});
