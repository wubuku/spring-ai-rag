import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { collectionsApi } from '../../api/collections';
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
    expect(screen.queryByRole('textbox', { name: 'collections.name' })).not.toBeInTheDocument();
  });

  it('renders when isOpen is true', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <CreateCollectionModal isOpen={true} onClose={vi.fn()} />
      </QueryClientProvider>
    );
    expect(screen.getByRole('textbox', { name: 'collections.name' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'collections.description' })).toBeInTheDocument();
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

    await user.type(screen.getByRole('textbox', { name: 'collections.name' }), 'AB');
    await user.click(screen.getByRole('button', { name: /create/i }));
    expect(screen.getByText(/at least 3 characters/i)).toBeInTheDocument();
  });

  it('shows validation error when name exceeds 100 characters', async () => {
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={queryClient}>
        <CreateCollectionModal isOpen={true} onClose={vi.fn()} />
      </QueryClientProvider>
    );

    // Focus + paste rather than `type`: these cases assert a *length limit*,
    // not keystroke handling. Typing hundreds of characters one event at a time
    // is what made this file time out under full-suite load.
    await user.click(screen.getByRole('textbox', { name: 'collections.name' }));
    await user.paste('N'.repeat(101));
    await user.click(screen.getByRole('button', { name: /create/i }));
    expect(screen.getByText(/less than 100 characters/i)).toBeInTheDocument();
  });

  it('shows validation error when description exceeds 500 characters', async () => {
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={queryClient}>
        <CreateCollectionModal isOpen={true} onClose={vi.fn()} />
      </QueryClientProvider>
    );

    await user.type(screen.getByRole('textbox', { name: 'collections.name' }), 'ValidName');
    await user.click(screen.getByRole('textbox', { name: 'collections.description' }));
    await user.paste('d'.repeat(501));
    await user.click(screen.getByRole('button', { name: /create/i }));
    expect(screen.getByText(/less than 500 characters/i)).toBeInTheDocument();
  });

  it('shows an error toast and keeps the modal open when creation fails', async () => {
    (collectionsApi.create as ReturnType<typeof vi.fn>)
      .mockRejectedValueOnce(new Error('backend down'));
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <QueryClientProvider client={queryClient}>
        <CreateCollectionModal isOpen={true} onClose={onClose} />
      </QueryClientProvider>
    );

    await user.type(screen.getByRole('textbox', { name: 'collections.name' }), 'ValidName');
    await user.type(
      screen.getByRole('textbox', { name: 'collections.collectionKey' }),
      'failure-case-key',
    );
    await user.click(screen.getByRole('button', { name: /create/i }));

    await waitFor(() =>
      expect(toastMock.showToast).toHaveBeenCalledWith(
        'Failed to create collection: backend down',
        'error',
      ),
    );
    expect(onClose).not.toHaveBeenCalled();
  });

  it('shows validation error when collection key is invalid', async () => {
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={queryClient}>
        <CreateCollectionModal isOpen={true} onClose={vi.fn()} />
      </QueryClientProvider>
    );

    await user.type(screen.getByRole('textbox', { name: 'collections.collectionKey' }), 'invalid key');
    await user.type(screen.getByRole('textbox', { name: 'collections.name' }), 'ValidName');
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

    // Pasting exactly 128 characters also exercises the input's maxLength
    // boundary without paying for 128 individual key events.
    await user.click(screen.getByRole('textbox', { name: 'collections.collectionKey' }));
    await user.paste('a'.repeat(128));
    await user.type(screen.getByRole('textbox', { name: 'collections.name' }), 'ValidName');
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

    await user.click(screen.getByRole('button', { name: 'collections.generateUuid' }));

    expect(screen.getByRole('textbox', { name: 'collections.collectionKey' })).toHaveValue(
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
      screen.getByRole('textbox', { name: 'collections.collectionKey' }),
      'customer-test-key'
    );
    await user.type(screen.getByRole('textbox', { name: 'collections.name' }), 'ValidName');
    await user.click(screen.getByRole('button', { name: /create/i }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });
});
