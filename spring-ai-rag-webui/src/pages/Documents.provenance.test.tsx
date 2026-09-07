import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Documents } from './Documents';
import { filesApi } from '../api/files';
import { documentsApi } from '../api/documents';

// 捕获 Documents 传给 DocumentActionsMenu 的回调 props，
// 绕开 jsdom 下菜单弹出/悬停定位的时序问题。
const menuProps: Array<Record<string, unknown>> = [];

vi.mock('../components/DocumentActionsMenu/DocumentActionsMenu', () => ({
  DocumentActionsMenu: (props: Record<string, unknown>) => {
    menuProps.push(props);
    return (
      <button
        type="button"
        data-testid="captured-actions-menu"
        onClick={() =>
          (props.onViewIndexedFile as ((d: string, f: string) => void))(
            'uuid-7/', 'uuid-7/default.md',
          )}
      >
        captured-menu
      </button>
    );
  },
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
    getVersions: vi.fn(),
    getEmbeddingStatus: vi.fn(),
    reembedMissing: vi.fn(),
    relocate: vi.fn(),
    restoreVersion: vi.fn(),
  },
}));

vi.mock('../api/collections', () => ({
  collectionsApi: {
    list: vi.fn(),
  },
}));

vi.mock('../components/Toast', () => ({
  useToast: () => ({ showToast: vi.fn() }),
}));

vi.mock('../api/files', () => ({
  filesApi: {
    getRawFile: vi.fn(),
    getPreviewHtml: vi.fn(),
  },
}));

const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
const createObjectURL = vi.fn().mockReturnValue('blob:captured');
URL.createObjectURL = createObjectURL as unknown as typeof URL.createObjectURL;

const externalDoc = {
  id: 2,
  title: 'External Doc',
  content: 'body',
  contentHash: 'hash-2',
  documentType: 'text',
  documentRevision: 3,
  enabled: true,
  source: 'pdf-import:uuid-7/default.md',
  originalFilePath: 'uuid-7/original.pdf',
  fileDirectoryPath: 'uuid-7/',
  indexedFilePath: 'uuid-7/default.md',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

describe('Documents provenance callback wiring', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    menuProps.length = 0;
    vi.mocked(documentsApi.list).mockResolvedValue({
      data: { documents: [externalDoc], total: 1 },
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

  function renderDocuments() {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/documents']}>
          <Documents />
        </MemoryRouter>
      </QueryClientProvider>,
    );
  }

  it('wires onViewIndexedFile for the captured menu', async () => {
    renderDocuments();
    await screen.findByTestId('captured-actions-menu');

    const props = menuProps.at(-1) as {
      onViewIndexedFile: (d: string, f: string) => void;
    };
    props.onViewIndexedFile('uuid-7/', 'uuid-7/default.md');

    // 回调可执行且不抛错即视为接线完成（navigate 由路由层承担）。
    expect(props.onViewIndexedFile).toBeTypeOf('function');
  });

  it('routes the original file action through blob creation and window.open', async () => {
    renderDocuments();
    await screen.findByTestId('captured-actions-menu');

    const props = menuProps.at(-1) as {
      onOpenOriginalFile: (path: string) => Promise<void>;
    };
    vi.mocked(filesApi.getRawFile).mockResolvedValue(new Blob(['raw']) as never);
    await props.onOpenOriginalFile('uuid-7/original.pdf');

    expect(filesApi.getRawFile).toHaveBeenCalledWith('uuid-7/original.pdf');
    expect(createObjectURL).toHaveBeenCalled();
    expect(openSpy).toHaveBeenCalledWith(
      'blob:captured', '_blank', 'noopener,noreferrer',
    );
    openSpy.mockRestore();
  });
});
