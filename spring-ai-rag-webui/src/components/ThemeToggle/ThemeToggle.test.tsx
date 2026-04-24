import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeToggle } from './ThemeToggle';

const localStorageMock = {
  data: {} as Record<string, string>,
  getItem: vi.fn((key: string) => localStorageMock.data[key] ?? null),
  setItem: vi.fn((key: string, value: string) => { localStorageMock.data[key] = value; }),
  removeItem: vi.fn((key: string) => { delete localStorageMock.data[key]; }),
};

let _currentHandler: ((e: { matches: boolean }) => void) | null = null;

function makeMq(matches: boolean) {
  return {
    matches,
    media: '(prefers-color-scheme: dark)',
    addEventListener: vi.fn((_type: string, handler: (e: { matches: boolean }) => void) => {
      _currentHandler = handler;
    }),
    removeEventListener: vi.fn(() => { _currentHandler = null; }),
  };
}

let _mqInstance = makeMq(false);
vi.stubGlobal('matchMedia', vi.fn(() => _mqInstance));

Object.defineProperty(window, 'localStorage', { value: localStorageMock });

describe('ThemeToggle', () => {
  beforeEach(() => {
    localStorageMock.data = {};
    vi.clearAllMocks();
    _currentHandler = null;
    _mqInstance = makeMq(false); // system light
  });

  // --- Rendering ---

  it('renders toggle button', () => {
    render(<ThemeToggle />);
    expect(screen.getByRole('button')).toBeInTheDocument();
  });

  // --- Auto mode (no saved preference) ---

  it('shows sync icon 🔄 on first render with no saved preference', () => {
    render(<ThemeToggle />);
    expect(screen.getByRole('button')).toHaveTextContent('🔄');
  });

  it('aria-label describes auto mode with system theme', () => {
    render(<ThemeToggle />);
    expect(screen.getByRole('button')).toHaveAttribute('aria-label',
      'Auto (system: light) — click to lock');
  });

  it('aria-label shows system dark in auto mode', () => {
    _mqInstance = makeMq(true); // system dark
    render(<ThemeToggle />);
    expect(screen.getByRole('button')).toHaveAttribute('aria-label',
      'Auto (system: dark) — click to lock');
  });

  // --- Locked mode ---

  it('shows ☀️ when locked to light', () => {
    localStorageMock.data['theme'] = 'light';
    render(<ThemeToggle />);
    expect(screen.getByLabelText(/Theme: light/)).toHaveTextContent('☀️');
  });

  it('shows 🌙 when locked to dark', () => {
    localStorageMock.data['theme'] = 'dark';
    render(<ThemeToggle />);
    expect(screen.getByLabelText(/Theme: dark/)).toHaveTextContent('🌙');
  });

  it('shows auto-return button (A) when locked', () => {
    localStorageMock.data['theme'] = 'light';
    render(<ThemeToggle />);
    expect(screen.getByLabelText('Switch to auto theme')).toBeInTheDocument();
  });

  it('does NOT show auto-return button in auto mode', () => {
    render(<ThemeToggle />);
    expect(screen.queryByLabelText('Switch to auto theme')).not.toBeInTheDocument();
  });

  // --- Interactions ---

  it('clicking toggle in auto mode locks to system light', async () => {
    const user = userEvent.setup();
    render(<ThemeToggle />);
    await user.click(screen.getByRole('button'));
    expect(localStorageMock.setItem).toHaveBeenCalledWith('theme', 'light');
    expect(screen.getByLabelText(/Theme: light/)).toHaveTextContent('☀️'); // now locked
    expect(screen.getByLabelText('Switch to auto theme')).toBeInTheDocument(); // A button appears
  });

  it('clicking toggle in auto mode locks to system dark', async () => {
    _mqInstance = makeMq(true); // system dark
    const user = userEvent.setup();
    render(<ThemeToggle />);
    await user.click(screen.getByRole('button'));
    expect(localStorageMock.setItem).toHaveBeenCalledWith('theme', 'dark');
    expect(screen.getByLabelText(/Theme: dark/)).toHaveTextContent('🌙');
  });

  it('clicking toggle when locked switches between light and dark', async () => {
    localStorageMock.data['theme'] = 'light';
    const user = userEvent.setup();
    render(<ThemeToggle />);
    await user.click(screen.getByLabelText(/Theme: light/));
    expect(localStorageMock.setItem).toHaveBeenCalledWith('theme', 'dark');
    expect(screen.getByLabelText(/Theme: dark/)).toHaveTextContent('🌙');
  });

  it('clicking A button exits locked mode and returns to auto', async () => {
    localStorageMock.data['theme'] = 'light';
    const user = userEvent.setup();
    render(<ThemeToggle />);
    await user.click(screen.getByLabelText('Switch to auto theme'));
    expect(localStorageMock.removeItem).toHaveBeenCalledWith('theme');
    expect(screen.getByRole('button')).toHaveTextContent('🔄');
    expect(screen.queryByLabelText('Switch to auto theme')).not.toBeInTheDocument();
  });

  // --- System preference listener ---

  it('system dark change updates theme when in auto mode', async () => {
    render(<ThemeToggle />);
    // Simulate OS dark mode activation
    await act(async () => {
      _currentHandler?.({ matches: true });
    });
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('system light change updates theme when in auto mode', async () => {
    _mqInstance = makeMq(true); // starts dark
    render(<ThemeToggle />);
    await act(async () => {
      _currentHandler?.({ matches: false });
    });
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('system change has no effect when locked', async () => {
    localStorageMock.data['theme'] = 'light';
    render(<ThemeToggle />);
    _currentHandler?.({ matches: true }); // system → dark
    // Should stay light (locked)
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });
});
