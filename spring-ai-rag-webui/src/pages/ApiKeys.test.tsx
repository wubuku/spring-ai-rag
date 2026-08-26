import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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
const mockShowToast = vi.fn();

// Mock the entire module
vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
  useMutation: (...args: unknown[]) => mockUseMutation(...args),
  useQueryClient: (...args: unknown[]) => mockUseQueryClient(...args),
}));

// Mock Toast
vi.mock('../components/Toast', () => ({
  useToast: vi.fn(() => ({
    showToast: mockShowToast,
  })),
}));

// Mock apiKeys
vi.mock('../api/apikeys', () => ({
  apiKeysApi: {
    listPrincipals: vi.fn(),
    createKey: vi.fn(),
    revokeKey: vi.fn(),
    rotateKey: vi.fn(),
    updatePolicy: vi.fn(),
  },
}));

vi.mock('../api/collections', () => ({
  collectionsApi: {
    list: vi.fn(),
  },
}));

const mockPrincipals = [
  {
    principalId: 'rag_p_abc123',
    name: 'Production Server',
    createdAt: '2026-04-12T03:00:00',
    updatedAt: '2026-04-12T03:00:00',
    lastUsedAt: '2026-04-12T10:00:00',
    expiresAt: '2027-01-01T00:00:00',
    status: 'ACTIVE',
    role: 'ADMIN',
    policyVersion: 2,
    currentCredentialId: 'rag_k_abc123_v2',
    currentCredentialVersion: 2,
    requestsPerMinute: 120,
    capabilities: ['RAG_READ', 'RAG_WRITE'],
  },
  {
    principalId: 'rag_p_def456',
    name: 'Test Key',
    createdAt: '2026-04-10T00:00:00',
    updatedAt: '2026-04-10T00:00:00',
    lastUsedAt: undefined,
    expiresAt: '2027-01-01T00:00:00',
    status: 'ACTIVE',
    role: 'NORMAL',
    policyVersion: 1,
    currentCredentialId: 'rag_k_def456',
    currentCredentialVersion: 1,
    capabilities: ['RAG_READ'],
  },
  {
    principalId: 'rag_p_key_scope',
    name: 'Key Scoped',
    createdAt: '2026-04-11T00:00:00',
    updatedAt: '2026-04-11T00:00:00',
    lastUsedAt: undefined,
    expiresAt: '2027-01-01T00:00:00',
    status: 'ACTIVE',
    role: 'NORMAL',
    policyVersion: 3,
    currentCredentialId: 'rag_k_key_scope_v3',
    currentCredentialVersion: 3,
    capabilities: ['RAG_READ'],
    allowedCollectionKeys: ['customer:manual'],
  },
];

describe('ApiKeys', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockMutateFn.mockClear();
    mockShowToast.mockClear();
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

  it('renders one row per principal with current credential metadata', async () => {
    mockUseQuery.mockReturnValue({ data: { data: mockPrincipals }, isPending: false });
    render(<BrowserRouter><ApiKeys /></BrowserRouter>);
    await waitFor(() => {
      expect(screen.getByText('Production Server')).toBeInTheDocument();
      expect(screen.getByText('Test Key')).toBeInTheDocument();
      expect(screen.getAllByText('apiKeys.allCollections')).toHaveLength(2);
      expect(screen.getByText('customer:manual')).toBeInTheDocument();
      expect(screen.getByText('rag_k_abc123_v2')).toBeInTheDocument();
      expect(screen.getByText('v2')).toBeInTheDocument();
      expect(screen.getByText('120')).toBeInTheDocument();
    });
  });

  it('shows Create Key button in toolbar when keys exist', async () => {
    mockUseQuery.mockReturnValue({ data: { data: mockPrincipals }, isPending: false });
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

  it('submits selected collection keys when creating a restricted key', async () => {
    mockUseQuery.mockImplementation((options: { queryKey: unknown[] }) => {
      if (options.queryKey[0] === 'api-principals') {
        return { data: { data: mockPrincipals }, isPending: false, isError: false };
      }
      return {
        data: {
          data: {
            collections: [
              {
                id: 10,
                collectionKey: 'customer:manual',
                name: 'Knowledge Base',
                description: '',
                embeddingModel: 'BAAI/bge-m3',
                dimensions: 1024,
                enabled: true,
                metadata: {},
                createdAt: '2026-07-21T00:00:00',
                updatedAt: '2026-07-21T00:00:00',
                documentCount: 0,
              },
            ],
          },
        },
        isPending: false,
        isError: false,
      };
    });

    render(<BrowserRouter><ApiKeys /></BrowserRouter>);
    fireEvent.click(screen.getByRole('button', { name: 'apiKeys.createKey' }));
    fireEvent.change(screen.getByPlaceholderText('apiKeys.namePlaceholder'), {
      target: { value: 'Scoped Key' },
    });
    fireEvent.click(screen.getByText('apiKeys.selectedCollections'));
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.change(document.querySelector('#create-key-quota')!, {
      target: { value: '75' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'apiKeys.create' }));

    expect(mockMutateFn).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Scoped Key',
      capabilities: ['RAG_READ', 'RAG_WRITE'],
      allowedCollectionKeys: ['customer:manual'],
      requestsPerMinute: 75,
      expiresAt: expect.stringMatching(/T\d{2}:\d{2}:00$/),
    }));
    expect(mockMutateFn).not.toHaveBeenCalledWith(expect.objectContaining({
      allowedCollectionIds: expect.anything(),
    }));
  });

  it('submits policy CAS updates for the stable principal', () => {
    mockUseQuery.mockImplementation((options: { queryKey: unknown[] }) => {
      if (options.queryKey[0] === 'api-principals') {
        return { data: { data: mockPrincipals }, isPending: false, isError: false };
      }
      return {
        data: { data: { collections: [] } },
        isPending: false,
        isError: false,
      };
    });

    render(<BrowserRouter><ApiKeys /></BrowserRouter>);
    fireEvent.click(screen.getAllByRole('button', { name: 'apiKeys.editPolicy' })[0]);
    fireEvent.change(document.querySelector('#policy-name')!, {
      target: { value: 'Production Agent' },
    });
    fireEvent.change(document.querySelector('#policy-quota')!, {
      target: { value: '240' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }));

    expect(mockMutateFn).toHaveBeenCalledWith({
      expectedPolicyVersion: 2,
      name: 'Production Agent',
      expiresAt: '2027-01-01T00:00:00',
      capabilities: ['RAG_READ', 'RAG_WRITE'],
      requestsPerMinute: 240,
    });
  });

  it('requires a future expiration without a maximum', () => {
    mockUseQuery.mockReturnValue({ data: { data: [] }, isPending: false });
    render(<BrowserRouter><ApiKeys /></BrowserRouter>);

    fireEvent.click(screen.getByRole('button', { name: 'apiKeys.createKey' }));
    const expiry = document.querySelector<HTMLInputElement>('input[type="datetime-local"]');

    expect(expiry).not.toBeNull();
    expect(expiry).toBeRequired();
    expect(expiry?.value).not.toBe('');
    expect(expiry?.min).not.toBe('');
    expect(expiry?.max).toBe('');
  });

  it('preserves the browser-managed year across unrelated rerenders', () => {
    mockUseQuery.mockReturnValue({ data: { data: [] }, isPending: false });
    render(<BrowserRouter><ApiKeys /></BrowserRouter>);

    fireEvent.click(screen.getByRole('button', { name: 'apiKeys.createKey' }));
    const nameInput = screen.getByPlaceholderText('apiKeys.namePlaceholder');
    const expiry = document.querySelector<HTMLInputElement>('input[type="datetime-local"]');
    const setNativeValue = Object.getOwnPropertyDescriptor(
      HTMLInputElement.prototype,
      'value',
    )?.set;

    expect(expiry).not.toBeNull();
    expect(setNativeValue).toBeDefined();

    fireEvent.change(expiry!, { target: { value: '' } });
    setNativeValue!.call(expiry, '2099-12-31T23:59');
    fireEvent.change(nameInput, { target: { value: 'Slow Keyboard Entry' } });

    expect(expiry).toHaveValue('2099-12-31T23:59');
    fireEvent.click(screen.getByRole('button', { name: 'apiKeys.create' }));
    expect(mockMutateFn).toHaveBeenCalledWith({
      name: 'Slow Keyboard Entry',
      expiresAt: '2099-12-31T23:59:00',
      capabilities: ['RAG_READ', 'RAG_WRITE'],
    });
  });

  it('does not submit when the required expiration is empty', () => {
    mockUseQuery.mockReturnValue({ data: { data: [] }, isPending: false });
    render(<BrowserRouter><ApiKeys /></BrowserRouter>);

    fireEvent.click(screen.getByRole('button', { name: 'apiKeys.createKey' }));
    fireEvent.change(screen.getByPlaceholderText('apiKeys.namePlaceholder'), {
      target: { value: 'Missing Expiry' },
    });
    const expiry = document.querySelector<HTMLInputElement>('input[type="datetime-local"]');
    fireEvent.change(expiry!, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'apiKeys.create' }));

    expect(mockMutateFn).not.toHaveBeenCalled();
  });

  it('submits an expiration beyond 90 days unchanged', () => {
    mockUseQuery.mockReturnValue({ data: { data: [] }, isPending: false });
    render(<BrowserRouter><ApiKeys /></BrowserRouter>);

    fireEvent.click(screen.getByRole('button', { name: 'apiKeys.createKey' }));
    fireEvent.change(screen.getByPlaceholderText('apiKeys.namePlaceholder'), {
      target: { value: 'Long-lived Service' },
    });
    const expiry = document.querySelector<HTMLInputElement>('input[type="datetime-local"]');
    fireEvent.change(expiry!, { target: { value: '2027-12-31T23:59' } });
    fireEvent.click(screen.getByRole('button', { name: 'apiKeys.create' }));

    expect(mockMutateFn).toHaveBeenCalledWith({
      name: 'Long-lived Service',
      expiresAt: '2027-12-31T23:59:00',
      capabilities: ['RAG_READ', 'RAG_WRITE'],
    });
  });

  it('shows the backend reason when creation fails', () => {
    mockUseQuery.mockReturnValue({ data: { data: [] }, isPending: false });
    render(<BrowserRouter><ApiKeys /></BrowserRouter>);

    fireEvent.click(screen.getByRole('button', { name: 'apiKeys.createKey' }));
    const mutationOptions = mockUseMutation.mock.calls.at(-1)?.[0] as {
      onError?: (error: unknown) => void;
    };
    mutationOptions.onError?.(new Error('Server validation failed'));

    expect(mockShowToast).toHaveBeenCalledWith(
      'apiKeys.createError: Server validation failed',
      'error',
    );
  });
});
