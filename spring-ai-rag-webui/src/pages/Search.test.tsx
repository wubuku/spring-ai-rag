import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { Search } from './Search';
import { searchApi } from '../api/search';

const mockRefetch = vi.fn();
const mockUseQuery = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
}));

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

describe('Search', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    (searchApi.search as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: { query: 'test', total: 0, results: [] },
    });
    mockUseQuery.mockImplementation((options: {
      queryKey: unknown[];
      queryFn: () => unknown;
    }) => {
      if (options.queryKey[0] === 'search-collections') {
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
          isPending: false,
        };
      }
      return {
        data: undefined,
        isPending: false,
        refetch: () => {
          mockRefetch();
          return options.queryFn();
        },
      };
    });
  });

  it('renders page title', () => {
    render(<Search />);
    const h1 = document.querySelector('h1');
    expect(h1).toBeInTheDocument();
    expect(h1).toHaveTextContent('search.title');
  });

  it('shows search input', () => {
    render(<Search />);
    expect(screen.getByPlaceholderText(/search.placeholder/)).toBeInTheDocument();
  });

  it('shows hybrid checkbox', () => {
    render(<Search />);
    expect(screen.getByLabelText(/Hybrid/)).toBeInTheDocument();
  });

  it('shows search button', () => {
    render(<Search />);
    expect(screen.getByRole('button', { name: /search.searchButton/ })).toBeInTheDocument();
  });

  it('search button is disabled when input is empty', () => {
    render(<Search />);
    expect(screen.getByRole('button', { name: /search.searchButton/ })).toBeDisabled();
  });

  it('search button is enabled when input has text', () => {
    render(<Search />);
    const input = screen.getByPlaceholderText(/search.placeholder/);
    fireEvent.change(input, { target: { value: 'test' } });
    expect(screen.getByRole('button', { name: /search.searchButton/ })).not.toBeDisabled();
  });

  it('calls refetch when form is submitted', () => {
    render(<Search />);
    const input = screen.getByPlaceholderText(/search.placeholder/);
    fireEvent.change(input, { target: { value: 'test query' } });
    fireEvent.click(screen.getByRole('button', { name: /search.searchButton/ }));
    expect(mockRefetch).toHaveBeenCalled();
  });

  it('searches by the selected collection key', async () => {
    render(<Search />);

    fireEvent.change(screen.getByTestId('search-collection-select'), {
      target: { value: 'customer:manual' },
    });
    fireEvent.change(screen.getByPlaceholderText(/search.placeholder/), {
      target: { value: 'test query' },
    });
    fireEvent.click(screen.getByRole('button', { name: /search.searchButton/ }));

    await waitFor(() => {
      expect(searchApi.search).toHaveBeenCalledWith({
        query: 'test query',
        useHybrid: true,
        collectionKeys: ['customer:manual'],
      });
    });
  });
});
