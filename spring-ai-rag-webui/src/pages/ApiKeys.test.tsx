import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import { apiKeysApi } from '../api/apikeys';
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
    prepareRotation: vi.fn(),
    getRotation: vi.fn(),
    completeRotation: vi.fn(),
    cancelRotation: vi.fn(),
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

  it('toggles create-form capabilities from read-only back to full', () => {
    render(<BrowserRouter><ApiKeys /></BrowserRouter>);
    fireEvent.click(screen.getByRole('button', { name: 'apiKeys.createKey' }));

    // 先切到只读，再切回读写：两个 onChange 分支都被触达。
    fireEvent.click(screen.getByRole('radio', { name: 'RAG_READ' }));
    expect(screen.getByRole('radio', { name: 'RAG_READ' })).toBeChecked();

    fireEvent.click(screen.getByRole('radio', { name: 'RAG_READ, RAG_WRITE' }));
    expect(screen.getByRole('radio', { name: 'RAG_READ, RAG_WRITE' })).toBeChecked();
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
    // 策略表单的到期时间与集合范围单选同样被编辑。
    fireEvent.change(document.querySelector('#policy-expiry')!, {
      target: { value: '2027-06-30T12:00' },
    });
    fireEvent.click(screen.getByRole('radio', {
      name: /apiKeys\.allCollections/,
    }));

    fireEvent.click(screen.getByRole('button', { name: 'common.save' }));

    expect(mockMutateFn).toHaveBeenCalledWith({
      expectedPolicyVersion: 2,
      name: 'Production Agent',
      expiresAt: '2027-06-30T12:00:00',
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

  describe('吊销与轮换', () => {
    const rotatingPrincipal = {
      ...mockPrincipals[0],
      principalId: 'rag_p_rotate',
      name: 'Rotating Key',
      rotationPending: true,
      pendingRotationId: 'rag_rot_777',
      retiringCredentialId: 'rag_k_rotate_v1',
      retiringCredentialVersion: 1,
      rotationExpiresAt: '2026-05-01T00:00:00',
    };

    /**
     * 页面有 7 个 useMutation，靠单一 mockMutateFn 无法区分是谁被调用。
     * 这里把 mutate 接到 config 自己的 mutationFn 上，断言因此落在
     * api 层的实参——也就是真正被端到端执行的那份契约。
     *
     * 两处都必须在 render 之前装好：
     * 1. 组件渲染时就调用了全部 useMutation 并捕获返回值，render 之后再换
     *    实现，捕获到的仍然是旧的。
     * 2. useMutation 被 mock 掉之后，"mutationFn 落定后自动调 onSuccess /
     *    onError" 这件事也没了，得由 mutate 自己补上，否则成功路径永远
     *    走不到（toast 不出、结果页不渲染）。
     */
    const routeMutationsToApiLayer = () => {
      mockUseMutation.mockImplementation((config: {
        mutationFn: (...args: unknown[]) => unknown;
        onSuccess?: (value: unknown) => void;
        onError?: (error: unknown) => void;
      }) => ({
        mutate: (...args: unknown[]) => {
          void Promise.resolve(config.mutationFn(...args))
            .then(config.onSuccess, config.onError);
        },
        isPending: false,
      }));
    };

    const mockInvalidate = vi.fn();

    const renderWithPrincipals = (principals: unknown[]) => {
      routeMutationsToApiLayer();
      // queryClient 同理：组件渲染时就拿到了它，render 之后再换实现无效。
      mockUseQueryClient.mockReturnValue({ invalidateQueries: mockInvalidate });
      // onSuccess 会读 response.data，所以 api 层必须有默认返回值；
      // 否则"只验证提交参数"的用例会在 promise 链里留下未处理的 rejection。
      // 关心具体返回值的用例用 mockResolvedValueOnce 覆盖即可。
      apiKeysApi.revokeKey.mockResolvedValue({ data: {} });
      apiKeysApi.completeRotation.mockResolvedValue({ data: {} });
      apiKeysApi.cancelRotation.mockResolvedValue({ data: {} });
      apiKeysApi.prepareRotation.mockResolvedValue({
        data: { rotationId: 'rag_rot_default', keyId: 'rag_k_default', rawKey: 'default-secret' },
      });
      apiKeysApi.rotateKey.mockResolvedValue({
        data: { name: 'Default', keyId: 'rag_k_default', rawKey: 'default-secret' },
      });
      mockUseQuery.mockImplementation((options: { queryKey: unknown[] }) => {
        if (options.queryKey[0] === 'api-principals') {
          return { data: { data: principals }, isPending: false, isError: false };
        }
        return { data: { data: { collections: [] } }, isPending: false, isError: false };
      });
      render(<BrowserRouter><ApiKeys /></BrowserRouter>);
    };

    const lastMutationOf = (name: string) => {
      const call = mockUseMutation.mock.calls
        .map(([config]) => config as { mutationKey?: string[] })
        .reverse()
        .find(config => config.mutationKey?.[0] === name);
      return call as { mutationKey?: string[]; onSuccess?: (v: unknown) => void; onError?: (e: unknown) => void } | undefined;
    };

    it('吊销要先过确认对话框，确认前一个请求都不发', () => {
      renderWithPrincipals([mockPrincipals[0]]);

      fireEvent.click(screen.getByRole('button', { name: 'apiKeys.revoke' }));

      // 确认框已经打开，但密钥还挂在服务端。
      expect(screen.getByText('apiKeys.revokeConfirm')).toBeInTheDocument();
      expect(apiKeysApi.revokeKey).not.toHaveBeenCalled();

      // 真正的确认按钮（确认框里的那个，不是卡片上的入口）。
      fireEvent.click(screen.getAllByRole('button', { name: 'apiKeys.revoke' })[1]);

      expect(apiKeysApi.revokeKey).toHaveBeenCalledWith('rag_k_abc123_v2');
    });

    it('取消确认对话框不会吊销', () => {
      renderWithPrincipals([mockPrincipals[0]]);

      fireEvent.click(screen.getByRole('button', { name: 'apiKeys.revoke' }));
      fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }));

      expect(apiKeysApi.revokeKey).not.toHaveBeenCalled();
    });

    it('吊销成功后刷新列表并提示', () => {
      renderWithPrincipals([mockPrincipals[0]]);

      fireEvent.click(screen.getByRole('button', { name: 'apiKeys.revoke' }));
      fireEvent.click(screen.getAllByRole('button', { name: 'apiKeys.revoke' })[1]);

      return waitFor(() => {
        expect(mockInvalidate).toHaveBeenCalledWith({ queryKey: ['api-principals'] });
        expect(mockShowToast).toHaveBeenCalledWith('apiKeys.revoked', 'success');
      });
    });

    it('吊销失败时报告错误而不报成功', () => {
      renderWithPrincipals([mockPrincipals[0]]);
      apiKeysApi.revokeKey.mockRejectedValueOnce(new Error('nope'));

      fireEvent.click(screen.getByRole('button', { name: 'apiKeys.revoke' }));
      fireEvent.click(screen.getAllByRole('button', { name: 'apiKeys.revoke' })[1]);

      return waitFor(() => {
        expect(mockShowToast).toHaveBeenCalledWith('apiKeys.revokeError', 'error');
      });
    });

    it('完成轮换针对待完成的那一次轮换，不是当前凭据', () => {
      renderWithPrincipals([rotatingPrincipal]);

      fireEvent.click(screen.getByRole('button', { name: 'apiKeys.completeRotation' }));

      expect(apiKeysApi.completeRotation).toHaveBeenCalledWith('rag_rot_777');
      return waitFor(() => {
        expect(mockShowToast).toHaveBeenCalledWith('apiKeys.rotationCompleted', 'success');
      });
    });

    it('取消轮换同样针对待完成的那一次轮换', () => {
      renderWithPrincipals([rotatingPrincipal]);

      fireEvent.click(screen.getByRole('button', { name: 'apiKeys.cancelRotation' }));

      expect(apiKeysApi.cancelRotation).toHaveBeenCalledWith('rag_rot_777');
      return waitFor(() => {
        expect(mockShowToast).toHaveBeenCalledWith('apiKeys.rotationCanceled', 'success');
      });
    });

    it('轮换进行中不能再发起新轮换，并显示正在退役的旧凭据', () => {
      renderWithPrincipals([rotatingPrincipal]);

      expect(screen.getByRole('button', { name: 'apiKeys.rotate' })).toBeDisabled();
      expect(screen.getByText('apiKeys.retiringCredential')).toBeInTheDocument();
      expect(screen.getByTitle('rag_k_rotate_v1')).toBeInTheDocument();
    });

    describe('分阶段轮换', () => {
      const openDialog = () => {
        renderWithPrincipals([mockPrincipals[0]]);
        fireEvent.click(screen.getByRole('button', { name: 'apiKeys.rotate' }));
      };

      const submit = () => {
        fireEvent.click(screen.getByRole('button', { name: 'apiKeys.prepareRotation' }));
      };

      it.each([
        ['留空', ''],
        ['零', '0'],
        ['负数', '-1'],
        ['非整数', '1.5'],
        ['超过一天', '86401'],
      ])('重叠窗口%s时提交按钮不可用', (_label, value) => {
        openDialog();
        fireEvent.change(document.querySelector('#rotation-overlap')!, {
          target: { value },
        });

        // 禁用发生在按钮上，所以"没提交"要连着断言禁用本身，
        // 否则用例名说的是守卫、实际钉的是另一道防线。
        const submitButton = screen.getByRole('button', { name: 'apiKeys.prepareRotation' });
        expect(submitButton).toBeDisabled();

        fireEvent.click(submitButton);

        expect(apiKeysApi.prepareRotation).not.toHaveBeenCalled();
      });

      it('提交合法的重叠窗口与幂等键', () => {
        openDialog();
        fireEvent.change(document.querySelector('#rotation-overlap')!, {
          target: { value: '1800' },
        });

        submit();

        expect(apiKeysApi.prepareRotation).toHaveBeenCalledWith(
          'rag_k_abc123_v2',
          1800,
          expect.any(String),
        );
      });

      it('留空重叠窗口等同于不传该参数', () => {
        openDialog();
        // 清空后按钮本身就该禁用，所以这里直接验证默认值路径。
        expect(document.querySelector<HTMLInputElement>('#rotation-overlap')?.value).toBe('900');
      });

      it('同一次会话内重复提交沿用同一个幂等键', async () => {
        openDialog();
        // 请求保持飞行中，界面才停在表单上——真实场景正是"以为没点上又点一次"。
        apiKeysApi.prepareRotation.mockReturnValue(new Promise(() => {}));

        submit();
        expect(apiKeysApi.prepareRotation).toHaveBeenCalledTimes(1);
        fireEvent.click(screen.getByRole('button', { name: 'apiKeys.prepareRotation' }));

        expect(apiKeysApi.prepareRotation).toHaveBeenCalledTimes(2);
        expect(apiKeysApi.prepareRotation.mock.calls[0][2])
          .toBe(apiKeysApi.prepareRotation.mock.calls[1][2]);
        expect(apiKeysApi.prepareRotation.mock.calls[0][2]).toBeTruthy();
      });

      it('成功后展示一次性密钥、轮换标识与重叠截止', async () => {
        openDialog();
        apiKeysApi.prepareRotation.mockResolvedValueOnce({
          data: {
            rotationId: 'rag_rot_888',
            keyId: 'rag_k_abc123_v3',
            rawKey: 'raw-secret-value',
            rotationExpiresAt: '2026-05-01T00:00:00',
          },
        });

        submit();

        await waitFor(() => {
          expect(screen.getByText('rag_rot_888')).toBeInTheDocument();
        });
        expect(screen.getByText('rag_k_abc123_v3')).toBeInTheDocument();
        expect(screen.getByText('raw-secret-value')).toBeInTheDocument();
        expect(screen.getByText('apiKeys.stagedSecretWarning')).toBeInTheDocument();
      });

      it('重放恢复：后端没有回传一次性密钥时改走恢复提示', async () => {
        openDialog();
        apiKeysApi.prepareRotation.mockResolvedValueOnce({
          data: {
            rotationId: 'rag_rot_888',
            keyId: 'rag_k_abc123_v3',
            rawKey: null,
          },
        });

        submit();

        await waitFor(() => {
          expect(screen.getByText('apiKeys.rotationReplayRecovered')).toBeInTheDocument();
        });
        expect(screen.getByText('apiKeys.rotationReplayNoSecret')).toBeInTheDocument();
        // 一次都没有展示过的密钥不该凭空出现。
        expect(screen.queryByText('apiKeys.rawKey')).not.toBeInTheDocument();
      });

      it('复制按钮把一次性密钥写进剪贴板', async () => {
        const writeText = vi.fn().mockResolvedValue(undefined);
        Object.defineProperty(navigator, 'clipboard', {
          value: { writeText },
          configurable: true,
        });
        openDialog();
        apiKeysApi.prepareRotation.mockResolvedValueOnce({
          data: {
            rotationId: 'rag_rot_888',
            keyId: 'rag_k_abc123_v3',
            rawKey: 'raw-secret-value',
          },
        });

        submit();
        await waitFor(() => {
          expect(screen.getByRole('button', { name: 'apiKeys.copy' })).toBeInTheDocument();
        });
        fireEvent.click(screen.getByRole('button', { name: 'apiKeys.copy' }));

        expect(writeText).toHaveBeenCalledWith('raw-secret-value');
      });

      it('关闭后重开不再残留上一次的一次性密钥', async () => {
        openDialog();
        apiKeysApi.prepareRotation.mockResolvedValueOnce({
          data: {
            rotationId: 'rag_rot_888',
            keyId: 'rag_k_abc123_v3',
            rawKey: 'raw-secret-value',
          },
        });
        submit();
        await waitFor(() => {
          expect(screen.getByText('raw-secret-value')).toBeInTheDocument();
        });

        fireEvent.click(screen.getByRole('button', { name: 'common.close' }));
        expect(screen.queryByText('raw-secret-value')).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: 'apiKeys.rotate' }));
        expect(screen.queryByText('raw-secret-value')).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'apiKeys.prepareRotation' })).toBeInTheDocument();
      });
    });

    describe('立即轮换', () => {
      const openImmediateDialog = () => {
        renderWithPrincipals([mockPrincipals[0]]);
        fireEvent.click(screen.getByRole('button', { name: 'apiKeys.rotate' }));
        fireEvent.click(screen.getByRole('radio', { name: /apiKeys\.immediateRotation/ }));
      };

      it('切到立即轮换后不再提交重叠窗口', () => {
        openImmediateDialog();

        fireEvent.click(screen.getByRole('button', { name: 'apiKeys.rotateImmediately' }));

        expect(apiKeysApi.prepareRotation).not.toHaveBeenCalled();
        expect(apiKeysApi.rotateKey).toHaveBeenCalledWith('rag_k_abc123_v2');
      });

      it('立即轮换没有重叠窗口输入框', () => {
        openImmediateDialog();
        expect(document.querySelector('#rotation-overlap')).toBeNull();
      });

      it('成功后展示新名称、密钥标识与一次性密钥', async () => {
        openImmediateDialog();
        apiKeysApi.rotateKey.mockResolvedValueOnce({
          data: {
            name: 'Rotating Key',
            keyId: 'rag_k_abc123_v9',
            rawKey: 'immediate-secret',
            warning: 'apiKeys.immediateRotationWarning',
          },
        });

        fireEvent.click(screen.getByRole('button', { name: 'apiKeys.rotateImmediately' }));

        await waitFor(() => {
          expect(screen.getByText('rag_k_abc123_v9')).toBeInTheDocument();
        });
        expect(screen.getByText('immediate-secret')).toBeInTheDocument();
        expect(screen.getByText('apiKeys.immediateRotationWarning')).toBeInTheDocument();
      });
    });

    it('轮换请求失败时把后端原因带进提示', () => {
      renderWithPrincipals([mockPrincipals[0]]);
      apiKeysApi.prepareRotation.mockRejectedValueOnce(new Error('rotation window too short'));

      fireEvent.click(screen.getByRole('button', { name: 'apiKeys.rotate' }));
      fireEvent.click(screen.getByRole('button', { name: 'apiKeys.prepareRotation' }));

      return waitFor(() => {
        expect(mockShowToast).toHaveBeenCalledWith(
          'apiKeys.rotationPrepareError: rotation window too short',
          'error',
        );
      });
    });

    it('吊销与轮换都挂在 mutation 自己的回调上，不是共用一条', () => {
      // 七个 useMutation 的 onSuccess/onError 各不相同；共用一条会让
      // 吊销成功去报"轮换已完成"。
      renderWithPrincipals([rotatingPrincipal]);

      fireEvent.click(screen.getByRole('button', { name: 'apiKeys.completeRotation' }));

      const revokeConfig = lastMutationOf('cancel-api-key-rotation');
      expect(revokeConfig).toBeDefined();
      expect(lastMutationOf('complete-api-key-rotation')?.onSuccess)
        .not.toBe(lastMutationOf('cancel-api-key-rotation')?.onSuccess);
    });
  });
});
