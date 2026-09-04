import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { Unlock } from './Unlock';
import { ApiKeyAuthContext, type ApiKeyAuthContextValue } from '../auth/ApiKeyAuthContext';

const mockUnlock = vi.fn();

function renderUnlock(options?: { isUnlocked?: boolean; from?: string }) {
  const value: ApiKeyAuthContextValue = {
    identity: null,
    isUnlocked: options?.isUnlocked ?? false,
    unlock: mockUnlock,
    logout: vi.fn(),
  };
  return render(
    <ApiKeyAuthContext.Provider value={value}>
      <MemoryRouter
        initialEntries={[
          {
            pathname: '/unlock',
            state: options?.from ? { from: options.from } : undefined,
          },
        ]}
      >
        <Routes>
          <Route path="/unlock" element={<Unlock />} />
          <Route path="/dashboard" element={<div>dashboard-page</div>} />
          <Route path="/collections" element={<div>collections-page</div>} />
        </Routes>
      </MemoryRouter>
    </ApiKeyAuthContext.Provider>,
  );
}

describe('Unlock', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('redirects to dashboard immediately when already unlocked on entry', () => {
    renderUnlock({ isUnlocked: true });
    expect(screen.getByText('dashboard-page')).toBeInTheDocument();
    expect(screen.queryByTestId('root-api-key')).not.toBeInTheDocument();
  });

  it('renders the unlock form with password input and disabled submit when empty', () => {
    renderUnlock();

    const input = screen.getByTestId('root-api-key');
    expect(input).toHaveAttribute('type', 'password');
    expect(input).toHaveAccessibleName('unlock.rootApiKey');

    expect(screen.getByRole('button', { name: 'unlock.submit' })).toBeDisabled();
  });

  it('submits the key and returns to the originating page after unlock', async () => {
    const user = userEvent.setup();
    mockUnlock.mockResolvedValueOnce(undefined);
    renderUnlock({ from: '/collections' });

    await user.type(screen.getByTestId('root-api-key'), 'root-key-1');
    await user.click(screen.getByRole('button', { name: 'unlock.submit' }));

    expect(mockUnlock).toHaveBeenCalledWith('root-key-1');
    expect(await screen.findByText('collections-page')).toBeInTheDocument();
    expect(screen.queryByTestId('root-api-key')).not.toBeInTheDocument();
  });

  it('navigates to dashboard when no origin was recorded', async () => {
    const user = userEvent.setup();
    mockUnlock.mockResolvedValueOnce(undefined);
    renderUnlock();

    await user.type(screen.getByTestId('root-api-key'), 'root-key-2');
    await user.click(screen.getByRole('button', { name: 'unlock.submit' }));

    expect(await screen.findByText('dashboard-page')).toBeInTheDocument();
  });

  it('shows an alert and keeps the form when the key is rejected', async () => {
    const user = userEvent.setup();
    mockUnlock.mockRejectedValueOnce(new Error('rejected'));
    renderUnlock();

    await user.type(screen.getByTestId('root-api-key'), 'bad-key');
    await user.click(screen.getByRole('button', { name: 'unlock.submit' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('unlock.invalidKey');
    expect(screen.getByTestId('root-api-key')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('root-api-key')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'unlock.submit' })).toBeEnabled();
  });
});
