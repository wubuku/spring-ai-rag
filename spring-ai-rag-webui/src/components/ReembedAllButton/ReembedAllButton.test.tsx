import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ReembedAllButton } from './ReembedAllButton';

const mockUseQuery = vi.fn();
const mockMutate = vi.fn();
const mockInvalidate = vi.fn();

vi.mock('@tanstack/react-query', () => ({
  useQuery: () => mockUseQuery(),
  useMutation: () => ({
    mutate: mockMutate,
    isPending: false,
  }),
  useQueryClient: () => ({ invalidateQueries: mockInvalidate }),
}));

vi.mock('../Toast', () => ({
  useToast: () => ({ showToast: vi.fn() }),
}));

function embedStatus(overrides: {
  hasMissing?: boolean;
  withoutEmbeddings?: number;
} = {}) {
  return {
    data: {
      data: {
        totalDocuments: 10,
        withEmbeddings: 10 - (overrides.withoutEmbeddings ?? 0),
        withoutEmbeddings: overrides.withoutEmbeddings ?? 0,
        hasMissing: overrides.hasMissing ?? false,
      },
    },
    isLoading: false,
    isPending: false,
  };
}

describe('ReembedAllButton', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseQuery.mockReturnValue(
      embedStatus({ hasMissing: true, withoutEmbeddings: 1 }),
    );
  });

  it('renders a skeleton while the embedding status loads', () => {
    mockUseQuery.mockReturnValueOnce({ isLoading: true, data: undefined });

    const { container } = render(<ReembedAllButton />);

    expect(container.querySelectorAll('div')).toHaveLength(1);
    expect(container.querySelector('div')).toBeEmptyDOMElement();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('renders nothing when every document already has embeddings', () => {
    mockUseQuery.mockReturnValueOnce(embedStatus({ hasMissing: false }));

    const { container } = render(<ReembedAllButton />);

    expect(container).toBeEmptyDOMElement();
  });

  it('expands the action panel from the alert button', async () => {
    const user = userEvent.setup();
    mockUseQuery.mockReturnValueOnce(
      embedStatus({ hasMissing: true, withoutEmbeddings: 3 }),
    );

    render(<ReembedAllButton />);

    const alertButton = screen.getByRole('button', { name: /documents\.missingEmbeddings/ });
    expect(alertButton).toHaveTextContent('3');
    expect(
      screen.queryByRole('button', { name: 'documents.reembed' }),
    ).not.toBeInTheDocument();

    await user.click(alertButton);

    expect(
      screen.getByRole('button', { name: 'documents.reembed' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'documents.reembedForce' }),
    ).toBeInTheDocument();
  });

  it('triggers a normal re-embed without the force flag', async () => {
    const user = userEvent.setup();
    mockUseQuery.mockReturnValueOnce(
      embedStatus({ hasMissing: true, withoutEmbeddings: 2 }),
    );

    render(<ReembedAllButton />);
    await user.click(
      screen.getByRole('button', { name: /documents\.missingEmbeddings/ }),
    );
    await user.click(screen.getByRole('button', { name: 'documents.reembed' }));

    expect(mockMutate).toHaveBeenCalledWith(false);
  });

  it('forces a re-embed only after confirming the danger dialog', async () => {
    const user = userEvent.setup();
    mockUseQuery.mockReturnValueOnce(
      embedStatus({ hasMissing: true, withoutEmbeddings: 4 }),
    );

    render(<ReembedAllButton />);
    await user.click(
      screen.getByRole('button', { name: /documents\.missingEmbeddings/ }),
    );
    await user.click(
      screen.getByRole('button', { name: 'documents.reembedForce' }),
    );

    const dialog = screen.getByRole('dialog', { name: 'documents.reembedForce' });
    expect(dialog).toHaveTextContent('documents.reembedForceConfirm');

    await user.click(within(dialog).getByRole('button', { name: 'common.cancel' }));
    expect(mockMutate).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    await user.click(
      screen.getByRole('button', { name: 'documents.reembedForce' }),
    );
    const reopened = screen.getByRole('dialog', { name: 'documents.reembedForce' });
    await user.click(
      within(reopened).getByRole('button', { name: 'documents.reembedForce' }),
    );

    expect(mockMutate).toHaveBeenCalledWith(true);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
