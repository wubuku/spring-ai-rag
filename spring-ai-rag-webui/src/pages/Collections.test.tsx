import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import { Collections } from './Collections';

// Create mock functions at module level
const mockUseQuery = vi.fn();
const mockUseMutation = vi.fn(() => ({
  mutate: vi.fn(),
  isPending: false,
}));
const mockUseQueryClient = vi.fn(() => ({
  invalidateQueries: vi.fn(),
}));

// Mock the entire module
vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
  useMutation: (...args: unknown[]) => mockUseMutation(...args),
  useQueryClient: (...args: unknown[]) => mockUseQueryClient(...args),
}));

// Mock Toast
vi.mock('../components/Toast', () => ({
  useToast: vi.fn(() => ({
    showToast: vi.fn(),
  })),
}));

// Mock collectionsApi
vi.mock('../api/collections', () => ({
  collectionsApi: {
    list: vi.fn(),
    delete: vi.fn(),
  },
}));

describe('Collections', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseMutation.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    });
  });

  it('renders page title', () => {
    mockUseQuery.mockReturnValue({
      data: { data: { collections: [], total: 0 } },
      isPending: false,
    });

    render(<BrowserRouter><Collections /></BrowserRouter>);
    expect(screen.getByText('collections.title')).toBeInTheDocument();
  });

  it('shows collection cards when data exists', () => {
    mockUseQuery.mockReturnValue({
      data: {
        data: {
          collections: [
            {
              id: 1,
              name: 'Test Collection',
              documentCount: 10,
              dimensions: 1024,
              embeddingModel: 'BGE-M3',
              createdAt: '2024-01-01T00:00:00Z',
              updatedAt: '2024-01-01T00:00:00Z',
            },
          ],
          total: 1,
        },
      },
      isPending: false,
    });

    render(<BrowserRouter><Collections /></BrowserRouter>);
    expect(screen.getByText('Test Collection')).toBeInTheDocument();
    expect(screen.getByText('BGE-M3 · 1024D')).toBeInTheDocument();
    expect(screen.getByText('10 collections.documentCount')).toBeInTheDocument();
  });

  it('shows empty state when no collections', () => {
    mockUseQuery.mockReturnValue({
      data: { data: { collections: [], total: 0 } },
      isPending: false,
    });

    render(<BrowserRouter><Collections /></BrowserRouter>);
    expect(screen.getByText('collections.noCollections')).toBeInTheDocument();
  });
});
