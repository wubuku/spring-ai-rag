import { act, render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiKeyAuthProvider } from './ApiKeyAuthProvider';
import { useApiKeyAuth } from './ApiKeyAuthContext';
import { clearCredential, getCredential } from './credentialStore';

vi.mock('../api/auth', () => ({
  authApi: {
    currentIdentity: vi.fn(),
  },
}));

import { authApi } from '../api/auth';

const rootIdentity = {
  principalType: 'ENVIRONMENT_ROOT',
  principalId: 'root',
  rootMode: true,
  capabilities: ['API_KEY_MANAGE'],
  principalRole: null,
  collectionAccessMode: 'UNRESTRICTED',
  allowedCollectionKeys: null,
};

const normalIdentity = {
  ...rootIdentity,
  principalType: 'DATABASE_API_KEY',
  rootMode: false,
  capabilities: [],
};

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

type Captured = ReturnType<typeof useApiKeyAuth>;

function ContextProbe({ onContext }: { onContext: (value: Captured) => void }) {
  onContext(useApiKeyAuth());
  return null;
}

function renderConsole(
  onContext: (value: Captured) => void,
): { queryByTestId: (id: string) => HTMLElement | null } {
  return render(
    <QueryClientProvider client={makeClient()}>
      <MemoryRouter initialEntries={['/documents']}>
        <ApiKeyAuthProvider>
          <ContextProbe onContext={onContext} />
        </ApiKeyAuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ApiKeyAuthProvider', () => {
  let captured: Captured | null = null;

  beforeEach(() => {
    vi.mocked(authApi.currentIdentity).mockReset();
    clearCredential();
    localStorage.clear();
    captured = null;
  });

  it('rejects a blank credential without calling the identity API', async () => {
    renderConsole(value => { captured = value; });
    await expect(actUnlock('   ')).rejects.toThrow('Root API key is required');
    expect(authApi.currentIdentity).not.toHaveBeenCalled();
    expect(getCredential()).toBeNull();
  });

  it('rejects a non-root identity without persisting the credential', async () => {
    vi.mocked(authApi.currentIdentity).mockResolvedValue({
      data: normalIdentity,
    } as never);
    renderConsole(value => { captured = value; });
    await expect(actUnlock('normal-key')).rejects.toThrow(
      'This API key cannot unlock the management console',
    );
    expect(getCredential()).toBeNull();
  });

  it('unlocks with a root identity, trimming the credential', async () => {
    vi.mocked(authApi.currentIdentity).mockResolvedValue({
      data: rootIdentity,
    } as never);
    renderConsole(value => { captured = value; });
    await act(async () => {
      await captured!.unlock('  root-key  ');
    });
    expect(authApi.currentIdentity).toHaveBeenCalledWith('root-key');
    expect(getCredential()).toBe('root-key');
  });

  it('exposes isUnlocked tied to both identity and stored credential', async () => {
    vi.mocked(authApi.currentIdentity).mockResolvedValue({
      data: rootIdentity,
    } as never);
    renderConsole(value => { captured = value; });
    expect(captured!.isUnlocked).toBe(false);

    await act(async () => {
      await captured!.unlock('root-key');
    });
    expect(captured!.identity?.principalType).toBe('ENVIRONMENT_ROOT');
    expect(captured!.isUnlocked).toBe(true);

    // 外部清空凭证（如 401 处理路径）→ 订阅回调重置身份。
    await act(async () => {
      clearCredential();
    });
    expect(captured!.identity).toBeNull();
    expect(captured!.isUnlocked).toBe(false);
  });

  it('logout clears the persisted credential', async () => {
    vi.mocked(authApi.currentIdentity).mockResolvedValue({
      data: rootIdentity,
    } as never);
    renderConsole(value => { captured = value; });
    await act(async () => {
      await captured!.unlock('root-key');
    });

    await act(async () => {
      captured!.logout();
    });
    expect(getCredential()).toBeNull();
  });

  async function actUnlock(credential: string): Promise<void> {
    await act(async () => {
      await captured!.unlock(credential);
    });
  }
});
