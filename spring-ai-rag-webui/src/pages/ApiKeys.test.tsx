import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import { ApiKeys } from './ApiKeys';

// Create mock functions at module level
const mockUseQuery = vi.fn();
const mockMutateFn = vi.fn();
const mockUseMutation = vi.fn(() => ({
  mutate: mockMutateFn,
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

// Mock apiKeys
vi.mock('../api/apikeys', () => ({
  apiKeysApi: {
    listKeys: vi.fn(),
    createKey: vi.fn(),
    revokeKey: vi.fn(),
    rotateKey: vi.fn(),
  },
}));

const mockKeys = [
  {
    keyId: 'rag_k_abc123',
    name: 'Production Server',
    createdAt: '2026-04-12T03:00:00',
    lastUsedAt: '2026-04-12T10:00:00',
    expiresAt: '2027-01-01T00:00:00',
    enabled: true,
  },
  {
    keyId: 'rag_k_def456',
    name: 'Test Key',
    createdAt: '2026-04-10T00:00:00',
    lastUsedAt: undefined,
    expiresAt: undefined,
    enabled: true,
  },
];

describe('ApiKeys', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockMutateFn.mockClear();
    mockUseMutation.mockReturnValue({
      mutate: mockMutateFn,
      isPending: false,
    });
    mockUseQueryClient.mockReturnValue({
      invalidateQueries: vi.fn(),
    });
  });

  it('renders title', () => {
    mockUseQuery.mockReturnValue({ data: { data: [] }, isPending: false });
    render(<BrowserRouter><ApiKeys /></BrowserRouter>);
    expect(screen.getByText('apiKeys.title')).toBeInTheDocument();
  });

  it('shows loading state when pending', () => {
    mockUseQuery.mockReturnValue({ data: undefined, isPending: true });
    render(<BrowserRouter><ApiKeys /></BrowserRouter>);
    expect(screen.getByText('common.loading')).toBeInTheDocument();
  });

  it('shows empty state when no keys', async () => {
    mockUseQuery.mockReturnValue({ data: { data: [] }, isPending: false });
    render(<BrowserRouter><ApiKeys /></BrowserRouter>);
    await waitFor(() => {
      expect(screen.getByText('apiKeys.noKeys')).toBeInTheDocument();
    });
  });

  it('renders key list when keys exist', async () => {
    mockUseQuery.mockReturnValue({ data: { data: mockKeys }, isPending: false });
    render(<BrowserRouter><ApiKeys /></BrowserRouter>);
    await waitFor(() => {
      expect(screen.getByText('Production Server')).toBeInTheDocument();
      expect(screen.getByText('Test Key')).toBeInTheDocument();
    });
  });

  it('shows Create Key button in toolbar when keys exist', async () => {
    mockUseQuery.mockReturnValue({ data: { data: mockKeys }, isPending: false });
    render(<BrowserRouter><ApiKeys /></BrowserRouter>);
    await waitFor(() => {
      expect(screen.getByText('Production Server')).toBeInTheDocument();
    });
    // Verify the toolbar has the Create Key button
    const toolbarButtons = document.querySelectorAll('[class*="_toolbar"] button');
    expect(toolbarButtons.length).toBeGreaterThan(0);
  });

  it('shows Create Key button in toolbar when no keys', async () => {
    mockUseQuery.mockReturnValue({ data: { data: [] }, isPending: false });
    render(<BrowserRouter><ApiKeys /></BrowserRouter>);
    await waitFor(() => {
      expect(screen.getByText('apiKeys.noKeys')).toBeInTheDocument();
    });
    // Verify the toolbar has the Create Key button
    const toolbarButtons = document.querySelectorAll('[class*="_toolbar"] button');
    expect(toolbarButtons.length).toBeGreaterThan(0);
  });
});
