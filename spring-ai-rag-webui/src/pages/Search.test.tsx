import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { collectionsApi } from '../api/collections';
import { filesApi } from '../api/files';
import { searchApi } from '../api/search';
import { ToastProvider } from '../components/Toast';
import { MemoryRouter } from 'react-router-dom';
import { Search } from './Search';

vi.mock('../api/search', () => ({
  searchApi: {
    search: vi.fn(),
  },
}));

vi.mock('../api/collections', () => ({
  collectionsApi: {
    list: vi.fn(),
  },
}));

vi.mock('../api/files', () => ({
  filesApi: {
    getRawFile: vi.fn(),
  },
}));

const collections = [
  {
    id: 10,
    collectionKey: 'zeta:manual',
    name: 'Zeta Manual',
    documentCount: 2,
  },
  {
    id: 11,
    collectionKey: 'alpha:manual',
    name: 'Alpha Manual',
    documentCount: 3,
  },
];

function renderSearch(initialEntries = ['/search']) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <ToastProvider>
          <MemoryRouter initialEntries={initialEntries}>
            <Search />
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>,
    ),
  };
}

async function submit(query = 'test query') {
  fireEvent.change(screen.getByPlaceholderText(/search.placeholder/), {
    target: { value: query },
  });
  fireEvent.click(screen.getByRole('button', { name: /search.searchButton/ }));
  await waitFor(() => expect(searchApi.search).toHaveBeenCalled());
}

describe('Search', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.sessionStorage.clear();
    (searchApi.search as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: { query: 'test', total: 0, results: [] },
    });
    (collectionsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        collections,
        total: collections.length,
        offset: 0,
        limit: 50,
      },
    });
  });

  it('renders the basic search controls', () => {
    renderSearch();

    expect(screen.getByText('search.title')).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/search.placeholder/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Hybrid/)).toBeChecked();
    expect(screen.getByRole('button', { name: /search.searchButton/ })).toBeDisabled();
  });

  it('restores an unsubmitted draft only for the same submitted URL', () => {
    const first = renderSearch(['/search?query=manual']);
    fireEvent.change(screen.getByPlaceholderText(/search.placeholder/), {
      target: { value: 'unfinished refinement' },
    });
    first.unmount();

    renderSearch(['/search?query=manual']);
    expect(screen.getByPlaceholderText(/search.placeholder/))
      .toHaveValue('unfinished refinement');
  });

  it('sends CALLER_VISIBLE without collection keys by default', async () => {
    const { client } = renderSearch();
    await submit();

    expect(searchApi.search).toHaveBeenCalledWith({
      query: 'test query',
      useHybrid: true,
      collectionScopeMode: 'CALLER_VISIBLE',
      collectionKeys: undefined,
    });
    expect(client.getQueryCache().getAll().map(query => query.queryKey))
      .toContainEqual([
        'search',
        'test query',
        true,
        'CALLER_VISIBLE',
        [],
      ]);
  });

  it('sends ANY_COLLECTION without stale collection keys', async () => {
    renderSearch();
    fireEvent.click(screen.getByTestId('search-scope-ANY_COLLECTION'));
    await submit('assigned only');

    expect(searchApi.search).toHaveBeenCalledWith({
      query: 'assigned only',
      useHybrid: true,
      collectionScopeMode: 'ANY_COLLECTION',
      collectionKeys: undefined,
    });
  });

  it('requires selected collections and sends sorted repeated keys', async () => {
    renderSearch();
    fireEvent.click(screen.getByTestId('search-scope-SELECTED_COLLECTIONS'));
    fireEvent.change(screen.getByPlaceholderText(/search.placeholder/), {
      target: { value: 'selected query' },
    });

    expect(screen.getByRole('button', { name: /search.searchButton/ })).toBeDisabled();

    const zeta = await screen.findByRole('checkbox', { name: /Zeta Manual/ });
    const alpha = screen.getByRole('checkbox', { name: /Alpha Manual/ });
    fireEvent.click(zeta);
    fireEvent.click(alpha);
    fireEvent.click(screen.getByRole('button', { name: /search.searchButton/ }));

    await waitFor(() => {
      expect(searchApi.search).toHaveBeenCalledWith({
        query: 'selected query',
        useHybrid: true,
        collectionScopeMode: 'SELECTED_COLLECTIONS',
        collectionKeys: ['alpha:manual', 'zeta:manual'],
      });
    });
  });

  it('preserves file provenance returned by the Search API', async () => {
    (searchApi.search as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        query: 'manual',
        total: 1,
        results: [{
          documentId: '7',
          title: 'Manual',
          chunkText: 'Indexed text',
          score: 0.8,
          source: 'pdf-import:uuid-7/default.md',
          originalFilename: 'manual.pdf',
          fileDirectoryPath: 'uuid-7/',
          indexedFilePath: 'uuid-7/default.md',
          originalFilePath: 'uuid-7/original.pdf',
        }],
      },
    });

    renderSearch();
    await submit('manual');

    expect(await screen.findByText('manual.pdf')).toBeInTheDocument();
    expect(screen.getByRole('button', {
      name: 'search.viewFileDirectory',
    })).toBeInTheDocument();
    expect(screen.getByRole('button', {
      name: 'search.viewIndexedFile',
    })).toBeInTheDocument();
    expect(screen.getByRole('button', {
      name: 'search.openOriginalPdf',
    })).toBeInTheDocument();
  });

  it('replays a search from URL state after a direct navigation', async () => {
    (searchApi.search as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        query: 'manual',
        total: 1,
        results: [{
          documentId: '7',
          title: 'Manual',
          chunkText: 'Indexed text',
          score: 0.8,
        }],
      },
    });

    renderSearch([
      '/search?query=manual&hybrid=false&scopeMode=ANY_COLLECTION',
    ]);

    expect(await screen.findByText('Manual')).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/search.placeholder/)).toHaveValue('manual');
    expect(screen.getByLabelText(/Hybrid/)).not.toBeChecked();
    expect(searchApi.search).toHaveBeenCalledWith({
      query: 'manual',
      useHybrid: false,
      collectionScopeMode: 'ANY_COLLECTION',
      collectionKeys: undefined,
    });
  });

  it('refetches when the submitted search already matches the current URL', async () => {
    renderSearch([
      '/search?query=manual&hybrid=true&scopeMode=CALLER_VISIBLE',
    ]);

    await waitFor(() => expect(searchApi.search).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: /search.searchButton/ }));

    await waitFor(() => expect(searchApi.search).toHaveBeenCalledTimes(2));
  });
});

describe('Search history and original file actions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.removeItem('rag_search_history');
    (collectionsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: { collections, total: collections.length },
    });
    (searchApi.search as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        query: 'first query',
        total: 1,
        results: [{
          documentId: '7',
          title: 'Manual',
          chunkText: 'Indexed text',
          score: 0.8,
          source: 'pdf-import:uuid-7/default.md',
          originalFilename: 'manual.pdf',
          fileDirectoryPath: 'uuid-7/',
          indexedFilePath: 'uuid-7/default.md',
          originalFilePath: 'uuid-7/original.pdf',
        }],
      },
    });
  });

  async function runSearchAndOpenHistory() {
    renderSearch();
    await submit('first query');
    // 有历史记录时 focus 输入框即展开历史面板。
    const input = screen.getByPlaceholderText(/search.placeholder/);
    fireEvent.focus(input);
    return input;
  }

  it('tracks search history and restores a query from it', async () => {
    const input = await runSearchAndOpenHistory();

    // 历史面板出现并记录了查询。
    expect(await screen.findByText('search.recentSearches')).toBeInTheDocument();
    expect(screen.getByText('first query')).toBeInTheDocument();

    // 选择历史项回填查询。
    fireEvent.click(screen.getByText('first query'));
    expect(input).toHaveValue('first query');
  });

  it('removes a single history entry and clears all entries', async () => {
    // 直接预置两条历史记录。
    localStorage.setItem('rag_search_history', JSON.stringify([
      { query: 'first query', useHybrid: true, timestamp: 1000 },
      { query: 'second query', useHybrid: false, timestamp: 2000 },
    ]));
    renderSearch();
    const input = screen.getByPlaceholderText(/search.placeholder/);
    fireEvent.focus(input);

    expect(await screen.findByText('first query')).toBeInTheDocument();
    expect(screen.getByText('second query')).toBeInTheDocument();

    // 单条删除后历史计数减一。
    fireEvent.click(screen.getAllByTitle('common.delete')[0]);
    await waitFor(() => {
      expect(screen.queryByText('first query')).not.toBeInTheDocument();
    });
    expect(screen.getByText('second query')).toBeInTheDocument();

    // 一键清空后面板关闭且 localStorage 清空。
    fireEvent.click(screen.getByText('search.clearHistory'));
    await waitFor(() => {
      expect(screen.queryByText('search.recentSearches')).not.toBeInTheDocument();
    });
    // 清空实现写入空数组而非移除键。
    expect(localStorage.getItem('rag_search_history')).toBe('[]');
  });

  it('opens the original file as a blob and reports failures', async () => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    const createObjectURL = vi.fn().mockReturnValue('blob:mock');
    URL.createObjectURL = createObjectURL as unknown as typeof URL.createObjectURL;
    const user = userEvent.setup();

    renderSearch();
    await submit('manual');
    const originalBtn = await screen.findByRole('button', {
      name: 'search.openOriginalPdf',
    });
    (filesApi.getRawFile as ReturnType<typeof vi.fn>).mockResolvedValue(
      new Blob(['pdf']),
    );
    await user.click(originalBtn);

    await waitFor(() => {
      expect(filesApi.getRawFile).toHaveBeenCalledWith('uuid-7/original.pdf');
      expect(createObjectURL).toHaveBeenCalled();
      expect(openSpy).toHaveBeenCalledWith(
        'blob:mock', '_blank', 'noopener,noreferrer',
      );
    });

    // 失败路径以 toast 呈现。
    (filesApi.getRawFile as ReturnType<typeof vi.fn>)
      .mockRejectedValue(new Error('binary gone'));
    await user.click(screen.getByRole('button', { name: 'search.openOriginalPdf' }));

    // i18n mock 直通返回 key；错误 toast 以 key 文本出现。
    await waitFor(() => {
      expect(
        screen.getAllByText('search.openOriginalPdfError').length,
      ).toBeGreaterThan(0);
    });
    openSpy.mockRestore();
  });
});
