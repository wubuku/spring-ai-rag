import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CreateCollectionModal } from './CreateCollectionModal';

vi.mock('../../api/collections', () => ({
  collectionsApi: {
    create: vi.fn().mockResolvedValue({ id: 1, name: 'Test Collection' }),
  },
}));

const toastMock = { showToast: vi.fn() };
vi.mock('../Toast', () => ({ useToast: () => toastMock }));

const queryClient = new QueryClient();

describe('CreateCollectionModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('does not render when isOpen is false', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <CreateCollectionModal isOpen={false} onClose={vi.fn()} />
      </QueryClientProvider>
    );
    expect(screen.queryByRole('textbox', { name: /name/i })).not.toBeInTheDocument();
  });

  it('renders when isOpen is true', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <CreateCollectionModal isOpen={true} onClose={vi.fn()} />
      </QueryClientProvider>
    );
    expect(screen.getByRole('textbox', { name: /name/i })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /description/i })).toBeInTheDocument();
  });

  it('shows validation error when name is empty', async () => {
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={queryClient}>
        <CreateCollectionModal isOpen={true} onClose={vi.fn()} />
      </QueryClientProvider>
    );

    await user.click(screen.getByRole('button', { name: /create/i }));
    expect(screen.getByText(/name is required/i)).toBeInTheDocument();
  });

  it('shows validation error when name is too short', async () => {
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={queryClient}>
        <CreateCollectionModal isOpen={true} onClose={vi.fn()} />
      </QueryClientProvider>
    );

    await user.type(screen.getByRole('textbox', { name: /name/i }), 'AB');
    await user.click(screen.getByRole('button', { name: /create/i }));
    expect(screen.getByText(/at least 3 characters/i)).toBeInTheDocument();
  });

  it('shows validation error when collection key is invalid', async () => {
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={queryClient}>
        <CreateCollectionModal isOpen={true} onClose={vi.fn()} />
      </QueryClientProvider>
    );

    await user.type(screen.getByRole('textbox', { name: /collection key/i }), 'invalid key');
    await user.type(screen.getByRole('textbox', { name: /name/i }), 'ValidName');
    await user.click(screen.getByRole('button', { name: /create/i }));

    expect(screen.getByText(/1-128 visible ASCII characters/i)).toBeInTheDocument();
  });

  it('accepts a collection key at the 128-character limit', async () => {
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={queryClient}>
        <CreateCollectionModal isOpen={true} onClose={vi.fn()} />
      </QueryClientProvider>
    );

    await user.type(screen.getByRole('textbox', { name: /collection key/i }), 'a'.repeat(128));
    await user.type(screen.getByRole('textbox', { name: /name/i }), 'ValidName');
    await user.click(screen.getByRole('button', { name: /create/i }));

    await waitFor(() =>
      expect(screen.queryByText(/1-128 visible ASCII characters/i)).not.toBeInTheDocument()
    );
  });

  it('generates a UUID for the collection key', async () => {
    const user = userEvent.setup();
    const randomUUID = vi.spyOn(crypto, 'randomUUID').mockReturnValue(
      '550e8400-e29b-41d4-a716-446655440000'
    );
    render(
      <QueryClientProvider client={queryClient}>
        <CreateCollectionModal isOpen={true} onClose={vi.fn()} />
      </QueryClientProvider>
    );

    await user.click(screen.getByRole('button', { name: /generate uuid/i }));

    expect(screen.getByRole('textbox', { name: /collection key/i })).toHaveValue(
      '550e8400-e29b-41d4-a716-446655440000'
    );
    randomUUID.mockRestore();
  });

  it('calls onClose after successful creation', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <QueryClientProvider client={queryClient}>
        <CreateCollectionModal isOpen={true} onClose={onClose} />
      </QueryClientProvider>
    );

    await user.type(
      screen.getByRole('textbox', { name: /collection key/i }),
      'customer-test-key'
    );
    await user.type(screen.getByRole('textbox', { name: /name/i }), 'ValidName');
    await user.click(screen.getByRole('button', { name: /create/i }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });
});
