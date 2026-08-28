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
