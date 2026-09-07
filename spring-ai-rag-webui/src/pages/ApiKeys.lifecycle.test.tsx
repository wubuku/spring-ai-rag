import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiKeys } from './ApiKeys';
import type { ApiPrincipalResponse } from '../api/apikeys';

const mocks = vi.hoisted(() => ({
  listPrincipals: vi.fn(),
  createKey: vi.fn(),
  revokeKey: vi.fn(),
  rotateKey: vi.fn(),
  prepareRotation: vi.fn(),
  getRotation: vi.fn(),
  completeRotation: vi.fn(),
  cancelRotation: vi.fn(),
  updatePolicy: vi.fn(),
  listCollections: vi.fn(),
  showToast: vi.fn(),
  writeText: vi.fn(),
}));

vi.mock('../api/apikeys', () => ({
  apiKeysApi: {
    listPrincipals: mocks.listPrincipals,
    createKey: mocks.createKey,
    revokeKey: mocks.revokeKey,
    rotateKey: mocks.rotateKey,
    prepareRotation: mocks.prepareRotation,
    getRotation: mocks.getRotation,
    completeRotation: mocks.completeRotation,
    cancelRotation: mocks.cancelRotation,
    updatePolicy: mocks.updatePolicy,
  },
}));

vi.mock('../api/collections', () => ({
  collectionsApi: {
    list: mocks.listCollections,
  },
}));

vi.mock('../components/Toast', () => ({
  useToast: () => ({ showToast: mocks.showToast }),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k }),
}));

function makePrincipal(
  overrides: Partial<ApiPrincipalResponse> = {},
): ApiPrincipalResponse {
  return {
    principalId: 'rag_p_main',
    name: 'Main Principal',
    role: 'NORMAL',
    policyVersion: 1,
    status: 'ACTIVE',
    createdAt: '2026-08-27T08:00:00',
    updatedAt: '2026-08-27T08:00:00',
    expiresAt: '2027-08-27T08:00:00',
    currentCredentialId: 'rag_k_main_v1',
    currentCredentialVersion: 1,
    capabilities: ['RAG_READ'],
    ...overrides,
  };
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ApiKeys />
    </QueryClientProvider>,
  );
}

function createdKeyResponse(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      keyId: 'rag_k_new_v1',
      principalId: 'rag_p_new',
      credentialVersion: 1,
      policyVersion: 1,
      rawKey: 'rag_sk_new_raw_secret',
      name: 'quota-key',
      capabilities: ['RAG_READ', 'RAG_WRITE'],
      expiresAt: '2027-09-07T00:00:00',
      requestsPerMinute: 120,
      warning: 'apiKeys.warningShownOnce',
      ...overrides,
    },
  };
}

describe('ApiKeys principal lifecycle rows', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listPrincipals.mockResolvedValue({
      data: [makePrincipal()],
    });
    mocks.listCollections.mockResolvedValue({
      data: { collections: [] },
    });
  });

  it('revokes the current credential with a success toast', async () => {
    const user = userEvent.setup();
    mocks.revokeKey.mockResolvedValue({ data: {} });

    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'apiKeys.revoke' }),
    );

    await waitFor(() => {
      expect(mocks.revokeKey).toHaveBeenCalledWith('rag_k_main_v1');
      expect(mocks.showToast).toHaveBeenCalledWith('apiKeys.revoked', 'success');
    });
  });

  it('surfaces revoke failures as an error toast', async () => {
    const user = userEvent.setup();
    mocks.revokeKey.mockRejectedValue(new Error('still in use'));

    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'apiKeys.revoke' }),
    );

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalledWith(
        'apiKeys.revokeError',
        'error',
      );
    });
  });

  it('renders revoked and expired rows with disabled actions', async () => {
    mocks.listPrincipals.mockResolvedValue({
      data: [
        makePrincipal({
          principalId: 'rag_p_revoked',
          name: 'Revoked Principal',
          status: 'REVOKED',
        }),
        makePrincipal({
          principalId: 'rag_p_expired',
          name: 'Expired Principal',
          status: 'EXPIRED',
        }),
      ],
    });

    renderPage();

    const revokedRow = (
      await screen.findByText('Revoked Principal')
    ).closest('div[class*="tableRow"]') as HTMLElement;
    expect(within(revokedRow).getByText('apiKeys.revoked')).toBeInTheDocument();
    expect(
      within(revokedRow).getByRole('button', { name: 'apiKeys.revoke' }),
    ).toBeDisabled();

    const expiredRow = (
      await screen.findByText('Expired Principal')
    ).closest('div[class*="tableRow"]') as HTMLElement;
    expect(within(expiredRow).getByText('apiKeys.expired')).toBeInTheDocument();
    expect(
      within(expiredRow).getByRole('button', { name: 'apiKeys.editPolicy' }),
    ).toBeDisabled();
  });

  it('falls back to placeholders for quota, scope and unknown role', async () => {
    mocks.listPrincipals.mockResolvedValue({
      data: [
        makePrincipal({
          role: 'GUEST',
          requestsPerMinute: undefined,
          allowedCollectionKeys: undefined,
          currentCredentialId: undefined,
          currentCredentialVersion: undefined,
        }),
      ],
    });

    renderPage();

    const row = (
      await screen.findByText('Main Principal')
    ).closest('div[class*="tableRow"]') as HTMLElement;
    expect(within(row).getByText('apiKeys.defaultQuota')).toBeInTheDocument();
    expect(within(row).getByText('apiKeys.allCollections')).toBeInTheDocument();
    // 未知角色走 getRoleBadge 的兜底徽章；凭据缺失的占位符也是 —。
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(2);
  });

  it('completes and cancels a pending rotation from the row', async () => {
    const user = userEvent.setup();
    mocks.listPrincipals.mockResolvedValue({
      data: [
        makePrincipal({
          rotationPending: true,
          pendingRotationId: 'rot-row-1',
          retiringCredentialId: 'rag_k_main_v1',
          retiringCredentialVersion: 1,
          rotationExpiresAt: '2026-09-10T08:00:00',
        }),
      ],
    });
    mocks.completeRotation.mockResolvedValue({ data: {} });
    mocks.cancelRotation.mockRejectedValue('boom');

    renderPage();
    expect(
      await screen.findByText('apiKeys.rotationPending'),
    ).toBeInTheDocument();

    await user.click(
      screen.getByRole('button', { name: 'apiKeys.completeRotation' }),
    );
    await waitFor(() => {
      expect(mocks.completeRotation).toHaveBeenCalledWith('rot-row-1');
      expect(mocks.showToast).toHaveBeenCalledWith(
        'apiKeys.rotationCompleted',
        'success',
      );
    });

    await user.click(
      screen.getByRole('button', { name: 'apiKeys.cancelRotation' }),
    );
    // 非 Error 抛出物走 formatMutationError 的回退分支。
    await waitFor(() => {
      expect(mocks.cancelRotation).toHaveBeenCalledWith('rot-row-1');
      expect(mocks.showToast).toHaveBeenCalledWith(
        'apiKeys.rotationCancelError',
        'error',
      );
    });
  });
});

describe('ApiKeys create modal with quota and clipboard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listPrincipals.mockResolvedValue({ data: [makePrincipal()] });
    mocks.listCollections.mockResolvedValue({
      data: { collections: [] },
    });
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: mocks.writeText },
      configurable: true,
    });
    mocks.writeText.mockResolvedValue(undefined);
  });

  it('creates a key with a quota and copies the shown-once raw key', async () => {
    const user = userEvent.setup();
    // userEvent.setup() 会替换 navigator.clipboard，因此在其之后打 spy。
    const writeSpy = vi
      .spyOn(navigator.clipboard, 'writeText')
      .mockResolvedValue(undefined);
    mocks.createKey.mockResolvedValue(createdKeyResponse());

    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'apiKeys.createKey' }),
    );

    const dialog = screen.getByRole('dialog', { name: 'apiKeys.createKey' });
    await user.type(
      within(dialog).getByPlaceholderText('apiKeys.namePlaceholder'),
      'quota-key',
    );
    await user.type(
      within(dialog).getByLabelText('apiKeys.quota'),
      '120',
    );
    await user.click(
      within(dialog).getByRole('button', { name: 'apiKeys.create' }),
    );

    await waitFor(() => {
      expect(mocks.createKey).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'quota-key',
          requestsPerMinute: 120,
        }),
      );
    });

    // 创建成功面板展示一次性 raw key。
    expect(await screen.findByText('rag_sk_new_raw_secret')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'apiKeys.copy' }));
    await waitFor(() => {
      expect(writeSpy).toHaveBeenCalledWith('rag_sk_new_raw_secret');
      expect(mocks.showToast).toHaveBeenCalledWith('apiKeys.copied', 'success');
    });
  });

  it('shows the default quota placeholder when the created key has none', async () => {
    const user = userEvent.setup();
    mocks.createKey.mockResolvedValue(
      createdKeyResponse({ requestsPerMinute: undefined }),
    );

    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'apiKeys.createKey' }),
    );
    const dialog = screen.getByRole('dialog', { name: 'apiKeys.createKey' });
    await user.type(
      within(dialog).getByPlaceholderText('apiKeys.namePlaceholder'),
      'quota-less',
    );
    await user.click(
      within(dialog).getByRole('button', { name: 'apiKeys.create' }),
    );

    // 列表行与创建成功面板各渲染一处 defaultQuota 占位。
    expect(
      (await screen.findAllByText('apiKeys.defaultQuota')).length,
    ).toBeGreaterThanOrEqual(2);
  });
});

describe('ApiKeys rotate and edit modal internals', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listPrincipals.mockResolvedValue({ data: [makePrincipal()] });
    mocks.listCollections.mockResolvedValue({
      data: {
        collections: [
          { id: 1, collectionKey: 'kb', name: 'Knowledge Base', enabled: true },
        ],
      },
    });
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: mocks.writeText },
      configurable: true,
    });
    mocks.writeText.mockResolvedValue(undefined);
  });

  async function openRotateModal() {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Main Principal');
    await user.click(screen.getByRole('button', { name: 'apiKeys.rotate' }));
    return user;
  }

  async function openEditModal() {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Main Principal');
    await user.click(
      screen.getByRole('button', { name: 'apiKeys.editPolicy' }),
    );
    await screen.findByLabelText('apiKeys.name *');
    return user;
  }

  it('disables prepare while the overlap window is invalid or empty', async () => {
    mocks.prepareRotation.mockResolvedValue({
      data: {
        rotationId: 'rot-1',
        status: 'PENDING',
        principalId: 'rag_p_main',
        keyId: 'rag_k_v2',
        credentialVersion: 2,
        rawKey: null,
        secretAvailable: false,
        idempotentReplay: false,
        currentCredentialActive: true,
        rotationPending: true,
        retiringCredentialId: 'rag_k_main_v1',
        retiringCredentialVersion: 1,
        overlapSeconds: 900,
      },
    });
    const user = await openRotateModal();
    const overlap = screen.getByLabelText('apiKeys.overlapSeconds') as HTMLInputElement;
    const prepareBtn = () =>
      screen.getByRole('button', { name: 'apiKeys.prepareRotation' }) as HTMLButtonElement;

    // 越界（0）与空值都会禁用按钮——UI 层是第一道防线。
    await user.clear(overlap);
    await user.type(overlap, '0');
    expect(prepareBtn()).toBeDisabled();
    await user.clear(overlap);
    expect(prepareBtn()).toBeDisabled();
    expect(mocks.prepareRotation).not.toHaveBeenCalled();

    // 合法重叠窗口恢复可用并发出 prepare。
    await user.type(overlap, '900');
    expect(prepareBtn()).toBeEnabled();
    await user.click(prepareBtn());
    await waitFor(() => {
      expect(mocks.prepareRotation).toHaveBeenCalledWith(
        'rag_k_main_v1',
        900,
        expect.any(String),
      );
    });
  });

  it('runs an immediate rotation and copies the shown-once raw key', async () => {
    const user = userEvent.setup();
    const writeSpy = vi
      .spyOn(navigator.clipboard, 'writeText')
      .mockResolvedValue(undefined);
    mocks.rotateKey.mockResolvedValue({
      data: {
        keyId: 'rag_k_imm_v1',
        principalId: 'rag_p_main',
        credentialVersion: 2,
        policyVersion: 1,
        rawKey: 'rag_sk_imm_raw',
        name: 'Main Principal',
        capabilities: ['RAG_READ'],
        warning: 'shown once',
      },
    });

    await openRotateModal();
    fireEvent.click(screen.getAllByRole('radio')[1]);
    await user.click(
      screen.getByRole('button', { name: 'apiKeys.rotateImmediately' }),
    );

    expect(await screen.findByText('rag_sk_imm_raw')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'apiKeys.copy' }));
    await waitFor(() => {
      expect(writeSpy).toHaveBeenCalledWith('rag_sk_imm_raw');
      expect(mocks.showToast).toHaveBeenCalledWith('apiKeys.copied', 'success');
    });
  });

  it('surfaces an immediate rotation failure through formatMutationError', async () => {
    const user = userEvent.setup();
    mocks.rotateKey.mockRejectedValue(new Error('credential active'));

    await openRotateModal();
    fireEvent.click(screen.getAllByRole('radio')[1]);
    await user.click(
      screen.getByRole('button', { name: 'apiKeys.rotateImmediately' }),
    );

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalledWith(
        'apiKeys.rotateError: credential active',
        'error',
      );
    });
  });

  it('submits a policy CAS update and toasts success', async () => {
    const user = await openEditModal();
    mocks.updatePolicy.mockResolvedValue({ data: {} });

    fireEvent.change(document.querySelector('#policy-name')!, {
      target: { value: 'Renamed Principal' },
    });
    await user.click(screen.getByRole('button', { name: 'common.save' }));

    await waitFor(() => {
      expect(mocks.updatePolicy).toHaveBeenCalledWith(
        'rag_p_main',
        expect.objectContaining({
          expectedPolicyVersion: 1,
          name: 'Renamed Principal',
        }),
      );
      expect(mocks.showToast).toHaveBeenCalledWith(
        'apiKeys.policyUpdated',
        'success',
      );
    });
  });

  it('surfaces the policy update error toast when the CAS fails', async () => {
    const user = await openEditModal();
    mocks.updatePolicy.mockRejectedValue(new Error('version conflict'));

    await user.click(screen.getByRole('button', { name: 'common.save' }));

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalledWith(
        'apiKeys.policyUpdateError: version conflict',
        'error',
      );
    });
  });

  it('restricts collection access and toggles a collection off again', async () => {
    const user = await openEditModal();
    mocks.updatePolicy.mockResolvedValue({ data: {} });

    // 编辑模态的 scope 单选组名为 policyCollectionScope，第二枚为限定集合。
    const scopeRadios = document.querySelectorAll(
      'input[name="policyCollectionScope"]',
    );
    fireEvent.click(scopeRadios[1]);
    const checkbox = await screen.findByRole('checkbox');
    // 勾选 → 移除 → 再勾选：toggleCollection 两个分支都走到。
    await user.click(checkbox);
    await user.click(checkbox);
    await user.click(checkbox);

    fireEvent.change(document.querySelector('#policy-name')!, {
      target: { value: 'scoped' },
    });
    await user.click(screen.getByRole('button', { name: 'common.save' }));

    await waitFor(() => {
      expect(mocks.updatePolicy).toHaveBeenCalledWith(
        'rag_p_main',
        expect.objectContaining({ name: 'scoped' }),
      );
    });
  });

  it('surfaces the complete rotation error through formatMutationError', async () => {
    const user = userEvent.setup();
    mocks.listPrincipals.mockResolvedValue({
      data: [
        makePrincipal({
          rotationPending: true,
          pendingRotationId: 'rot-err-1',
          retiringCredentialId: 'rag_k_main_v1',
          retiringCredentialVersion: 1,
          rotationExpiresAt: '2026-09-10T08:00:00',
        }),
      ],
    });
    mocks.completeRotation.mockRejectedValue(new Error('lease lost'));

    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'apiKeys.completeRotation' }),
    );

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalledWith(
        'apiKeys.rotationCompleteError: lease lost',
        'error',
      );
    });
  });
});
