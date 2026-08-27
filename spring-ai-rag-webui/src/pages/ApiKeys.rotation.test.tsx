import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiKeys } from './ApiKeys';

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

const principal = {
  principalId: 'rag_p_rotation',
  name: 'Rotation Test',
  role: 'NORMAL',
  policyVersion: 3,
  status: 'ACTIVE',
  createdAt: '2026-08-27T08:00:00',
  updatedAt: '2026-08-27T08:00:00',
  expiresAt: '2027-08-27T08:00:00',
  currentCredentialId: 'rag_k_rotation_v3',
  currentCredentialVersion: 3,
  capabilities: ['RAG_READ'],
};

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <ApiKeys />
      </BrowserRouter>
    </QueryClientProvider>,
  );
  return { queryClient, rendered };
}

function preparedRotation(rawKey: string | null, replay = false) {
  return {
    data: {
      rotationId: '11111111-1111-4111-8111-111111111111',
      status: 'PENDING',
      principalId: principal.principalId,
      keyId: 'rag_k_rotation_v4',
      credentialVersion: 4,
      rawKey,
      secretAvailable: rawKey !== null,
      idempotentReplay: replay,
      currentCredentialActive: true,
      rotationPending: true,
      retiringCredentialId: principal.currentCredentialId,
      retiringCredentialVersion: principal.currentCredentialVersion,
      rotationExpiresAt: '2026-08-27T08:15:00',
    },
  };
}

describe('ApiKeys staged credential rotation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listPrincipals.mockResolvedValue({ data: [principal] });
    mocks.listCollections.mockResolvedValue({ data: { collections: [] } });
  });

  it('keeps a newly created principal actionable after the shown-once modal closes', async () => {
    const rawKey = 'rag_sk_created_shown_once';
    const createResponse = {
      data: {
        keyId: 'rag_k_created_v1',
        principalId: 'rag_p_created',
        credentialVersion: 1,
        policyVersion: 1,
        rawKey,
        name: 'Created Principal',
        capabilities: ['RAG_READ'],
        expiresAt: '2027-08-27T08:00:00',
        requestsPerMinute: 12,
        warning: 'shown once',
      },
    };
    const listedCreatedPrincipal = {
      principalId: createResponse.data.principalId,
      name: createResponse.data.name,
      role: 'NORMAL',
      policyVersion: createResponse.data.policyVersion,
      status: 'ACTIVE',
      createdAt: '2026-08-27T08:01:00',
      updatedAt: '2026-08-27T08:01:00',
      expiresAt: createResponse.data.expiresAt,
      currentCredentialId: createResponse.data.keyId,
      currentCredentialVersion: createResponse.data.credentialVersion,
      requestsPerMinute: createResponse.data.requestsPerMinute,
      capabilities: createResponse.data.capabilities,
    };
    mocks.createKey.mockResolvedValue(createResponse);
    mocks.listPrincipals
      .mockReset()
      .mockResolvedValueOnce({ data: [principal] })
      .mockResolvedValue({ data: [listedCreatedPrincipal, principal] });
    const { queryClient } = renderPage();

    await screen.findByText(principal.name);
    fireEvent.click(screen.getByRole('button', { name: 'apiKeys.createKey' }));
    fireEvent.change(screen.getByPlaceholderText('apiKeys.namePlaceholder'), {
      target: { value: 'Created Principal' },
    });
    fireEvent.click(screen.getByLabelText('RAG_READ'));
    fireEvent.change(document.querySelector('#create-key-quota')!, {
      target: { value: '12' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'apiKeys.create' }));

    expect(await screen.findByText(rawKey)).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: 'common.close' }));
    expect(screen.queryByText(rawKey)).not.toBeInTheDocument();

    const row = within((await screen.findByText('Created Principal')).closest(
      '[class*="tableRow"]',
    )!);
    expect(row.getByText('rag_k_created_v1')).toBeVisible();
    expect(row.getByRole('button', { name: 'apiKeys.editPolicy' })).toBeEnabled();
    expect(JSON.stringify(
      queryClient.getQueryData(['api-principals']),
    )).not.toContain(rawKey);
    await waitFor(() => expect(mocks.listPrincipals).toHaveBeenCalledTimes(2));
  });

  it('uses staged rotation by default and removes the shown-once secret on close', async () => {
    mocks.prepareRotation.mockResolvedValue(
      preparedRotation('rag_sk_staged_shown_once'),
    );
    renderPage();

    const row = within((await screen.findByText(principal.name)).closest(
      '[class*="tableRow"]',
    )!);
    fireEvent.click(row.getByRole('button', { name: 'apiKeys.rotate' }));
    expect(screen.getByRole('radio', {
      name: /apiKeys\.stagedRotation/,
    })).toBeChecked();
    fireEvent.change(screen.getByLabelText('apiKeys.overlapSeconds'), {
      target: { value: '120' },
    });
    fireEvent.click(screen.getByRole('button', {
      name: 'apiKeys.prepareRotation',
    }));

    await waitFor(() => {
      expect(mocks.prepareRotation).toHaveBeenCalledWith(
        principal.currentCredentialId,
        120,
        expect.any(String),
      );
    });
    expect(await screen.findByText('rag_sk_staged_shown_once')).toBeVisible();
    expect(screen.getByText('11111111-1111-4111-8111-111111111111'))
      .toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: 'common.close' }));
    expect(screen.queryByText('rag_sk_staged_shown_once')).not.toBeInTheDocument();
  });

  it('reuses one idempotency key after a failed request and never invents a replay secret', async () => {
    mocks.prepareRotation
      .mockRejectedValueOnce(new Error('connection lost'))
      .mockResolvedValueOnce(preparedRotation(null, true));
    renderPage();

    const row = within((await screen.findByText(principal.name)).closest(
      '[class*="tableRow"]',
    )!);
    fireEvent.click(row.getByRole('button', { name: 'apiKeys.rotate' }));
    const prepareButton = screen.getByRole('button', {
      name: 'apiKeys.prepareRotation',
    });
    fireEvent.click(prepareButton);
    await waitFor(() => expect(mocks.prepareRotation).toHaveBeenCalledTimes(1));
    fireEvent.click(prepareButton);
    await waitFor(() => expect(mocks.prepareRotation).toHaveBeenCalledTimes(2));

    const firstRequestKey = mocks.prepareRotation.mock.calls[0][2];
    const secondRequestKey = mocks.prepareRotation.mock.calls[1][2];
    expect(firstRequestKey).toBeTruthy();
    expect(secondRequestKey).toBe(firstRequestKey);
    expect(await screen.findByText('apiKeys.rotationReplayNoSecret')).toBeVisible();
    expect(screen.queryByText(/^rag_sk_/)).not.toBeInTheDocument();
  });

  it('shows pending credential metadata and executes complete and cancel by rotation id', async () => {
    const pendingPrincipal = {
      ...principal,
      currentCredentialId: 'rag_k_rotation_v4',
      currentCredentialVersion: 4,
      rotationPending: true,
      pendingRotationId: '11111111-1111-4111-8111-111111111111',
      retiringCredentialId: principal.currentCredentialId,
      retiringCredentialVersion: principal.currentCredentialVersion,
      rotationExpiresAt: '2026-08-27T08:15:00',
    };
    mocks.listPrincipals.mockResolvedValue({ data: [pendingPrincipal] });
    mocks.completeRotation.mockResolvedValue(preparedRotation(null));
    mocks.cancelRotation.mockResolvedValue({
      data: { ...preparedRotation(null).data, status: 'CANCELED' },
    });
    renderPage();

    const rowElement = (await screen.findByText(principal.name)).closest(
      '[class*="tableRow"]',
    )!;
    const row = within(rowElement);
    expect(row.getByText('rag_k_rotation_v4')).toBeVisible();
    expect(row.getByText(principal.currentCredentialId)).toBeVisible();
    expect(row.getByText('apiKeys.rotationPending')).toBeVisible();
    expect(row.getByRole('button', { name: 'apiKeys.rotate' })).toBeDisabled();

    fireEvent.click(row.getByRole('button', {
      name: 'apiKeys.completeRotation',
    }));
    await waitFor(() => {
      expect(mocks.completeRotation).toHaveBeenCalledWith(
        pendingPrincipal.pendingRotationId,
      );
    });

    fireEvent.click(row.getByRole('button', {
      name: 'apiKeys.cancelRotation',
    }));
    await waitFor(() => {
      expect(mocks.cancelRotation).toHaveBeenCalledWith(
        pendingPrincipal.pendingRotationId,
      );
    });
  });

  it('keeps immediate rotation as an explicit compatibility path', async () => {
    mocks.rotateKey.mockResolvedValue({
      data: {
        keyId: 'rag_k_rotation_v4',
        principalId: principal.principalId,
        credentialVersion: 4,
        policyVersion: 3,
        rawKey: 'rag_sk_immediate_shown_once',
        name: principal.name,
        capabilities: ['RAG_READ'],
        expiresAt: principal.expiresAt,
        warning: 'shown once',
      },
    });
    renderPage();

    const row = within((await screen.findByText(principal.name)).closest(
      '[class*="tableRow"]',
    )!);
    fireEvent.click(row.getByRole('button', { name: 'apiKeys.rotate' }));
    fireEvent.click(screen.getByRole('radio', {
      name: /apiKeys\.immediateRotation/,
    }));
    fireEvent.click(screen.getByRole('button', {
      name: 'apiKeys.rotateImmediately',
    }));

    await waitFor(() => {
      expect(mocks.rotateKey).toHaveBeenCalledWith(
        principal.currentCredentialId,
      );
    });
    expect(await screen.findByText('rag_sk_immediate_shown_once')).toBeVisible();
  });
});
