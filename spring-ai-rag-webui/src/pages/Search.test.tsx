import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { collectionsApi } from '../api/collections';
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

function renderSearch() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <ToastProvider>
          <MemoryRouter>
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
});
