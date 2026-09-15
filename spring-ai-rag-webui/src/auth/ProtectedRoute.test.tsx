import { act, render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { ProtectedRoute } from './ProtectedRoute';
import { ApiKeyAuthProvider } from './ApiKeyAuthProvider';
import { useApiKeyAuth } from './ApiKeyAuthContext';
import { clearCredential } from './credentialStore';

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

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function UnlockProbe() {
  const location = useLocation();
  const navigate = useNavigate();
  return (
    <div>
      UNLOCK PAGE
      <span data-testid="from-state">
        {((location.state as { from?: string } | null)?.from) ?? ''}
      </span>
      <button
        type="button"
        data-testid="return-to-origin"
        onClick={() => {
          const from = (location.state as { from?: string } | null)?.from;
          navigate(from ?? '/', { replace: true });
        }}
      >
        return
      </button>
    </div>
  );
}

function renderProtectedConsole(onContext?: (value: ReturnType<typeof useApiKeyAuth>) => void) {
  function ContextProbe() {
    if (onContext) {
      onContext(useApiKeyAuth());
    }
    return null;
  }
  return render(
    <QueryClientProvider client={makeClient()}>
      <MemoryRouter initialEntries={['/documents?tab=files']}>
        <ApiKeyAuthProvider>
          <ContextProbe />
          <Routes>
            <Route element={<ProtectedRoute />}>
              <Route path="/documents" element={<div>MANAGEMENT CONTENT</div>} />
            </Route>
            <Route path="/unlock" element={<UnlockProbe />} />
          </Routes>
        </ApiKeyAuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ProtectedRoute', () => {
  beforeEach(() => {
    vi.mocked(authApi.currentIdentity).mockReset();
    clearCredential();
    localStorage.clear();
  });

  it('redirects a locked console to /unlock carrying the origin path', () => {
    const { getByTestId, queryByText } = renderProtectedConsole();

    expect(queryByText('MANAGEMENT CONTENT')).toBeNull();
    expect(getByTestId('from-state').textContent).toBe('/documents?tab=files');
  });

  it('renders the protected outlet once unlocked and the user returns', async () => {
    vi.mocked(authApi.currentIdentity).mockResolvedValue({
      data: { ...rootIdentity },
    } as never);
    let captured: ReturnType<typeof useApiKeyAuth> | null = null;
    const { getByText, getByTestId, queryByTestId, queryByText } =
      renderProtectedConsole(value => {
        captured = value;
      });

    // 锁定态：停留在 /unlock。
    expect(queryByText('MANAGEMENT CONTENT')).toBeNull();

    await act(async () => {
      await captured!.unlock('root-key');
    });
    // 与真实 Unlock 页一致：解锁后跳回来源路径。
    await act(async () => {
      getByTestId('return-to-origin').click();
    });

    expect(getByText('MANAGEMENT CONTENT')).toBeTruthy();
    expect(queryByTestId('from-state')).toBeNull();
  });
});
