import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Documents } from './Documents';

// Mock functions at module level
const mockUseQuery = vi.fn();
const mockUseMutation = vi.fn(() => ({
  mutate: vi.fn(),
  isPending: false,
}));
const mockUseQueryClient = vi.fn(() => ({
  invalidateQueries: vi.fn(),
}));
const mockUploadFiles = vi.fn();
const mockShowToast = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
  useMutation: (...args: unknown[]) => mockUseMutation(...args),
  useQueryClient: (...args: unknown[]) => mockUseQueryClient(...args),
}));

vi.mock('../components/Toast', () => ({
  useToast: vi.fn(() => ({
    showToast: mockShowToast,
  })),
}));

vi.mock('../hooks/useFileUpload', () => ({
  useFileUpload: vi.fn(() => ({
    uploadFiles: mockUploadFiles,
    isUploading: false,
    uploads: [],
  })),
}));

vi.mock('../api/documents', () => ({
  documentsApi: {
    list: vi.fn(),
    delete: vi.fn(),
  },
}));

describe('Documents', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseMutation.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    });
  });

  it('renders page title', () => {
    mockUseQuery.mockReturnValue({
      data: { data: { documents: [], total: 0 } },
      isPending: false,
      error: null,
    });

    render(<Documents />);
    const h1 = document.querySelector('h1');
    expect(h1).toBeInTheDocument();
    expect(h1).toHaveTextContent('Documents');
  });

  it('shows upload zone', () => {
    mockUseQuery.mockReturnValue({
      data: { data: { documents: [], total: 0 } },
      isPending: false,
      error: null,
    });

    render(<Documents />);
    expect(screen.getByText(/Drop files here or click to upload/)).toBeInTheDocument();
  });

  it('shows table when documents exist', () => {
    mockUseQuery.mockReturnValue({
      data: {
        data: {
          documents: [
            {
              id: 1,
              title: 'Test Doc',
              content: 'Content',
              contentHash: 'abc123',
              documentType: 'txt',
              createdAt: '2024-01-01T00:00:00Z',
              updatedAt: '2024-01-01T00:00:00Z',
            },
          ],
          total: 1,
        },
      },
      isPending: false,
      error: null,
    });

    render(<Documents />);
    expect(screen.getByText('Test Doc')).toBeInTheDocument();
  });

  it('shows empty state when no documents', () => {
    mockUseQuery.mockReturnValue({
      data: { data: { documents: [], total: 0 } },
      isPending: false,
      error: null,
    });

    render(<Documents />);
    expect(screen.getByText(/No documents found/)).toBeInTheDocument();
  });

  it('shows pagination controls', () => {
    mockUseQuery.mockReturnValue({
      data: { data: { documents: [], total: 50 } },
      isPending: false,
      error: null,
    });

    render(<Documents />);
    expect(screen.getByText('Page 1 — Total: 50')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeInTheDocument();
  });

  it('Previous button is disabled on first page', () => {
    mockUseQuery.mockReturnValue({
      data: { data: { documents: [], total: 50 } },
      isPending: false,
      error: null,
    });

    render(<Documents />);
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
  });
});
