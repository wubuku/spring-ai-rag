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
  FilePreview: () => null,
}));

describe('Files', () => {
  beforeEach(() => {
    vi.clearAllMocks();
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
              ? []
              : [
                  {
                    name: 'sample-pdf',
                    path: 'sample-pdf/',
                    type: 'directory',
                    mimeType: null,
                    size: 0,
                  },
                ],
            total: path ? 0 : 1,
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
});
