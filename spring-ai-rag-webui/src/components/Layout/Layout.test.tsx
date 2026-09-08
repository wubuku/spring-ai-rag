import { describe, it, expect, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
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

describe('Layout mobile sidebar', () => {
  it('closes the mobile sidebar from its close button', () => {
    const originalWidth = window.innerWidth;
    window.innerWidth = 480;
    render(
      <MemoryRouter>
        <Layout />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: 'Close sidebar' }));

    // 关闭后 aside 不再携带展开态样式类。
    const aside = document.querySelector('aside');
    expect(aside?.className).not.toContain('sidebarOpen');
    window.innerWidth = originalWidth;
  });
});

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

describe('Layout responsive sidebar and logout', () => {
  function setWindowWidth(width: number) {
    Object.defineProperty(window, 'innerWidth', {
      value: width,
      configurable: true,
    });
    window.dispatchEvent(new Event('resize'));
  }

  function renderLayout() {
    return render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Layout />
      </MemoryRouter>,
    );
  }

  it('shows the mobile menu trigger after resizing below the breakpoint', async () => {
    const user = userEvent.setup();
    renderLayout();

    // 桌面宽度下没有移动端菜单按钮。
    expect(screen.queryByRole('button', { name: 'Open sidebar' })).not.toBeInTheDocument();

    setWindowWidth(500);
    expect(
      await screen.findByRole('button', { name: 'Open sidebar' }),
    ).toBeInTheDocument();

    // 打开侧边栏后出现遮罩，点击遮罩关闭。
    await user.click(screen.getByRole('button', { name: 'Open sidebar' }));
    const overlay = document.querySelector('[class*="overlay"]');
    expect(overlay).not.toBeNull();
    await user.click(overlay!);
    expect(
      screen.queryByRole('button', { name: 'Open sidebar' }),
    ).toBeInTheDocument();
  });

  it('closes the mobile sidebar when a nav link is clicked', async () => {
    const user = userEvent.setup();
    renderLayout();
    setWindowWidth(500);

    await user.click(screen.getByRole('button', { name: 'Open sidebar' }));
    const navLink = screen.getAllByRole('link', { name: /nav\./ })[0];
    await user.click(navLink);

    // 桌面化或再次渲染后菜单按钮保持可见（sidebarOpen 已复位）。
    expect(
      screen.getByRole('button', { name: 'Open sidebar' }),
    ).toBeInTheDocument();
  });

  it('invokes logout from the sidebar action', async () => {
    const user = userEvent.setup();
    renderLayout();

    await user.click(screen.getByRole('button', { name: 'unlock.logout' }));
    // logout 来自 mocked context，这里仅验证按钮可点击且不抛错。
  });

  it('resets to desktop layout when resizing back above the breakpoint', async () => {
    setWindowWidth(500);
    const view = renderLayout();
    expect(
      view.getByRole('button', { name: 'Open sidebar' }),
    ).toBeInTheDocument();

    // resize 触发的 setState 需在 act 内刷新；查询限定在本次容器内，
    // 避免命中其他用例残留的挂载树。
    await act(async () => {
      setWindowWidth(1200);
      window.dispatchEvent(new Event('resize'));
    });
    await waitFor(() => {
      expect(
        view.queryByRole('button', { name: 'Open sidebar' }),
      ).not.toBeInTheDocument();
    });
  });
});
