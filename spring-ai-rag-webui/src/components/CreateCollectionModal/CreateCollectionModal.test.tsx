import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
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

  it('calls onClose after successful creation', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <QueryClientProvider client={queryClient}>
        <CreateCollectionModal isOpen={true} onClose={onClose} />
      </QueryClientProvider>
    );

    await user.type(screen.getByRole('textbox', { name: /name/i }), 'ValidName');
    await user.click(screen.getByRole('button', { name: /create/i }));

    await new Promise(r => setTimeout(r, 100));
    expect(onClose).toHaveBeenCalled();
  });
});
