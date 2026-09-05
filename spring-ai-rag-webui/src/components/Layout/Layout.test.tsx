import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { Layout } from './Layout';

// Mock ThemeToggle
vi.mock('../ThemeToggle', () => ({
  ThemeToggle: () => <div data-testid="theme-toggle">ThemeToggle</div>,
}));

// Mock ErrorBoundary
vi.mock('../ErrorBoundary', () => ({
  ErrorBoundary: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock('../../auth/ApiKeyAuthContext', () => ({
  useApiKeyAuth: () => ({
    identity: null,
    isUnlocked: true,
    unlock: vi.fn(),
    logout: vi.fn(),
  }),
}));

describe('Layout', () => {
  it('renders sidebar with navigation items', () => {
    render(
      <MemoryRouter>
        <Layout />
      </MemoryRouter>
    );

    expect(screen.getByText('spring-ai-rag')).toBeInTheDocument();
    // Mock i18n returns translation keys
    expect(screen.getByText('nav.dashboard')).toBeInTheDocument();
    expect(screen.getByText('nav.documents')).toBeInTheDocument();
    expect(screen.getByText('nav.chat')).toBeInTheDocument();
    expect(screen.getByText('nav.search')).toBeInTheDocument();
    expect(screen.getByText('nav.metrics')).toBeInTheDocument();
    expect(screen.getByText('nav.alerts')).toBeInTheDocument();
    expect(screen.getByText('nav.settings')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'unlock.logout' })).toBeInTheDocument();
  });

  it('renders ThemeToggle', () => {
    render(
      <MemoryRouter>
        <Layout />
      </MemoryRouter>
    );
    expect(screen.getByTestId('theme-toggle')).toBeInTheDocument();
  });

  it('resets the main scroll region when navigating to another page', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/api-keys']}>
        <Layout />
      </MemoryRouter>
    );
    const main = screen.getByRole('main');
    main.scrollTop = 640;
    main.scrollLeft = 120;
    document.documentElement.scrollTop = 480;
    document.body.scrollTop = 320;

    await user.click(screen.getByRole('link', { name: /nav\.files/ }));

    expect(main.scrollTop).toBe(0);
    expect(main.scrollLeft).toBe(0);
    expect(document.documentElement.scrollTop).toBe(0);
    expect(document.body.scrollTop).toBe(0);
  });
});

// ─── Route memory href sync (Batch 36 regression guard) ─────────────

import { Routes, Route, useNavigate } from 'react-router-dom';
import { rememberRoute } from '../../utils/workspaceState';

const ROUTES_KEY = 'spring-ai-rag:webui:v1:routes';

function Harness({ to }: { to: string }) {
  const navigate = useNavigate();
  return (
    <>
      <button onClick={() => navigate(to, { replace: true })}>go</button>
      <Layout />
    </>
  );
}

describe('Layout route memory', () => {
  it('refreshes nav link hrefs after the current route is remembered', async () => {
    const user = userEvent.setup();
    const { unmount } = render(
      <MemoryRouter initialEntries={['/chat']}>
        <Routes>
          <Route path="*" element={<Harness to="/chat?mode=AGENT" />} />
        </Routes>
      </MemoryRouter>,
    );

    const chatLink = screen.getByRole('link', { name: /nav\.chat/ });
    expect(chatLink.getAttribute('href')).toBe('/chat');

    // Navigating to /chat?mode=AGENT must update the remembered route and
    // re-render the nav links with the fresh query (regression guard for the
    // stale-href defect where mode=AGENT was silently dropped).
    await user.click(screen.getByRole('button', { name: 'go' }));

    expect(chatLink.getAttribute('href')).toBe('/chat?mode=AGENT');
    unmount();
    sessionStorage.removeItem(ROUTES_KEY);
  });

  it('seeds nav link hrefs from previously remembered routes', () => {
    rememberRoute('/chat', '?mode=PLAIN');
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Layout />
      </MemoryRouter>,
    );

    expect(
      screen.getByRole('link', { name: /nav\.chat/ }).getAttribute('href'),
    ).toBe('/chat?mode=PLAIN');
    sessionStorage.removeItem(ROUTES_KEY);
  });
});
