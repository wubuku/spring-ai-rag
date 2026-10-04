import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act } from '@testing-library/react';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { documentsApi } from '../../api/documents';
import { ReembedAllButton } from './ReembedAllButton';

const mockUseQuery = vi.fn();
const mockMutate = vi.fn();
const mockInvalidate = vi.fn();
const mutationHandlers: Array<Record<string, unknown>> = [];
const toastSpy = vi.fn();
// 渲染期闭包读取：useMutation 的 isPending 与 i18n 缺键开关。
let mockIsPending = false;

vi.mock('@tanstack/react-query', () => ({
  useQuery: () => mockUseQuery(),
  useMutation: (options: Record<string, unknown>) => {
    mutationHandlers.push(options);
    return {
      mutate: mockMutate,
      isPending: mockIsPending,
    };
  },
  useQueryClient: () => ({ invalidateQueries: mockInvalidate }),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    // 刻意与真实 i18next 行为一致：缺失的键返回键名本身（一个真值字符串）。
    // 带插值参数时把参数也带出来，这样用例能钉住「传了什么给 locale」，
    // 而不只是「用了哪个键」——重嵌入的三个 toast 各自带 success / failed /
    // message，参数传错会让用户看到错的数字，而键断言抓不到。
    t: (key: string, params?: Record<string, unknown>) =>
      params ? `${key} ${JSON.stringify(params)}` : key,
  }),
}));

vi.mock('../Toast', () => ({
  useToast: () => ({ showToast: toastSpy }),
}));

vi.mock('../../api/documents', () => ({
  documentsApi: {
    getEmbeddingStatus: vi.fn(),
    reembedMissing: vi
      .fn()
      .mockResolvedValue({ data: { success: 1, failed: 0 } }),
  },
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

  it('invalidates queries and warns when a re-embed partially fails', async () => {
    const user = userEvent.setup();
    render(<ReembedAllButton />);

    await user.click(screen.getByRole('button', { name: /documents\.missingEmbeddings/ }));
    await user.click(screen.getByRole('button', { name: 'documents.reembed' }));

    const options = mutationHandlers.at(-1) as {
      onSuccess: (result: unknown) => void;
      onError: (error: Error) => void;
    };
    // onSuccess 内会 setIsExpanded(false)，需要在 act 中触发状态更新。
    act(() => {
      options.onSuccess({ data: { success: 3, failed: 1 } });
    });

    expect(mockInvalidate).toHaveBeenCalledWith({ queryKey: ['embeddingStatus'] });
    expect(mockInvalidate).toHaveBeenCalledWith({ queryKey: ['documents'] });
    expect(toastSpy).toHaveBeenCalledWith(
      'documents.reembedSuccessPartial {"success":3,"failed":1}',
      'warning',
    );
  });

  it('reports the error toast when a re-embed rejects', async () => {
    const user = userEvent.setup();
    render(<ReembedAllButton />);

    await user.click(screen.getByRole('button', { name: /documents\.missingEmbeddings/ }));
    await user.click(screen.getByRole('button', { name: 'documents.reembed' }));

    const options = mutationHandlers.at(-1) as {
      onError: (error: Error) => void;
    };
    act(() => {
      options.onError(new Error('network down'));
    });

    expect(toastSpy).toHaveBeenCalledWith(
      'documents.reembedError (network down)',
      'error',
    );
  });

  it('routes the mutation function to the re-embed API with the force flag', async () => {
    render(<ReembedAllButton />);

    const options = mutationHandlers.at(-1) as {
      mutationFn: (force: boolean) => Promise<unknown>;
    };
    await options.mutationFn(true);

    expect(documentsApi.reembedMissing).toHaveBeenCalledWith(true);
  });

  it('shows a success toast when every re-embedded document succeeds', async () => {
    const user = userEvent.setup();
    render(<ReembedAllButton />);

    await user.click(
      screen.getByRole('button', { name: /documents\.missingEmbeddings/ }),
    );
    await user.click(screen.getByRole('button', { name: 'documents.reembed' }));

    const options = mutationHandlers.at(-1) as {
      onSuccess: (result: unknown) => void;
    };
    act(() => {
      options.onSuccess({ data: { success: 5, failed: 0 } });
    });

    expect(toastSpy).toHaveBeenCalledWith(
      'documents.reembedSuccess {"success":5}',
      'success',
    );
  });

  it('shows the key itself when a translation is missing, never a hardcoded label', async () => {
    // Batch 792 removed eight `t(...) || 'English literal'` guards here. The
    // case they were written for cannot occur: i18next returns the key string
    // for a missing key, and that string is truthy, so the guard never fired.
    // This test previously asserted the guard worked by mocking `t` to return
    // '', which i18next never does — it pinned a fiction that hid four real
    // missing keys elsewhere in the app.
    //
    // What is worth pinning is the real behaviour: no silent English appears.
    // The keys themselves are guaranteed to exist by check:i18n-keys.
    const user = userEvent.setup();
    render(<ReembedAllButton />);

    const alertButton = screen.getByRole('button', {
      name: /documents\.missingEmbeddings/,
    });
    // The key is shown, and no hardcoded English was substituted for it.
    expect(alertButton).toHaveAttribute('title', 'documents.reembedAlert');
    expect(screen.queryByText(/Documents missing embeddings/)).not.toBeInTheDocument();
    await user.click(alertButton);

    expect(screen.queryByText(/Re-embed All/)).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'documents.reembedForce' }),
    ).toBeInTheDocument();
  });

  it('shows the loading label while a re-embed is pending', async () => {
    mockIsPending = true;
    try {
      const user = userEvent.setup();
      render(<ReembedAllButton />);

      await user.click(
        screen.getByRole('button', { name: /documents\.missingEmbeddings/ }),
      );

      // 挂起中：重嵌按钮切到 loading 文案并禁用。
      expect(
        screen.getByRole('button', { name: 'common.loading' }),
      ).toBeDisabled();
      expect(
        screen.queryByRole('button', { name: 'documents.reembed' }),
      ).not.toBeInTheDocument();
    } finally {
      mockIsPending = false;
    }
  });
});
