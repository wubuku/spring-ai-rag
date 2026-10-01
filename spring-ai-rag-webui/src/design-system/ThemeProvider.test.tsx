import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from './ThemeProvider';
import { useTheme } from './themeContext';
import { THEME_STORAGE_KEY } from './theme';

const localStorageMock = {
  data: {} as Record<string, string>,
  getItem: vi.fn((key: string) => localStorageMock.data[key] ?? null),
  setItem: vi.fn((key: string, value: string) => {
    localStorageMock.data[key] = value;
  }),
  removeItem: vi.fn((key: string) => {
    delete localStorageMock.data[key];
  }),
};
Object.defineProperty(window, 'localStorage', { value: localStorageMock });

let mediaHandler: ((event: { matches: boolean }) => void) | null = null;
let mediaMatches = false;

function installMatchMedia(matches: boolean) {
  mediaMatches = matches;
  mediaHandler = null;
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      get matches() {
        return mediaMatches;
      },
      media: '(prefers-color-scheme: dark)',
      addEventListener: vi.fn((_type: string, handler: (event: { matches: boolean }) => void) => {
        mediaHandler = handler;
      }),
      removeEventListener: vi.fn(() => {
        mediaHandler = null;
      }),
    })),
  );
}

function Probe() {
  const { preference, resolvedTheme, setPreference } = useTheme();
  return (
    <div>
      <span data-testid="preference">{preference}</span>
      <span data-testid="resolved">{resolvedTheme}</span>
      <button onClick={() => setPreference('dark')}>go dark</button>
      <button onClick={() => setPreference('system')}>go system</button>
    </div>
  );
}

function renderProbe() {
  return render(
    <ThemeProvider>
      <Probe />
    </ThemeProvider>,
  );
}

describe('ThemeProvider', () => {
  beforeEach(() => {
    localStorageMock.data = {};
    vi.clearAllMocks();
    installMatchMedia(false);
    document.documentElement.removeAttribute('data-theme');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    document.documentElement.removeAttribute('data-theme');
  });

  it('starts in system mode with no stored preference and follows a light OS', () => {
    renderProbe();
    expect(screen.getByTestId('preference')).toHaveTextContent('system');
    expect(screen.getByTestId('resolved')).toHaveTextContent('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('migrates a legacy stored light preference without a flash of the wrong theme', () => {
    localStorageMock.data[THEME_STORAGE_KEY] = 'light';
    installMatchMedia(true);
    renderProbe();
    expect(screen.getByTestId('preference')).toHaveTextContent('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('resolves system mode to dark when the OS prefers dark', () => {
    installMatchMedia(true);
    renderProbe();
    expect(screen.getByTestId('resolved')).toHaveTextContent('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('reacts to an OS theme change while in system mode', async () => {
    renderProbe();
    await act(async () => {
      mediaHandler?.({ matches: true });
    });
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('ignores an OS theme change once the user picks an explicit theme', async () => {
    const user = userEvent.setup();
    renderProbe();
    await user.click(screen.getByRole('button', { name: 'go dark' }));

    await act(async () => {
      mediaHandler?.({ matches: false });
    });

    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('persists the chosen preference so a reload keeps it', async () => {
    const user = userEvent.setup();
    renderProbe();
    await user.click(screen.getByRole('button', { name: 'go dark' }));

    expect(localStorageMock.setItem).toHaveBeenCalledWith(THEME_STORAGE_KEY, 'dark');
  });

  it('writes back to system and resolves from the OS again', async () => {
    installMatchMedia(true);
    const user = userEvent.setup();
    renderProbe();
    await user.click(screen.getByRole('button', { name: 'go dark' }));
    await user.click(screen.getByRole('button', { name: 'go system' }));

    expect(screen.getByTestId('preference')).toHaveTextContent('system');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('adopts a preference changed by another tab', async () => {
    renderProbe();
    expect(screen.getByTestId('preference')).toHaveTextContent('system');

    localStorageMock.data[THEME_STORAGE_KEY] = 'dark';
    await act(async () => {
      window.dispatchEvent(
        new StorageEvent('storage', { key: THEME_STORAGE_KEY, newValue: 'dark' }),
      );
    });

    expect(screen.getByTestId('preference')).toHaveTextContent('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('does not write to storage when reacting to another tab (no event loop)', async () => {
    renderProbe();
    localStorageMock.setItem.mockClear();

    localStorageMock.data[THEME_STORAGE_KEY] = 'light';
    await act(async () => {
      window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY, newValue: 'light' }));
    });

    expect(localStorageMock.setItem).not.toHaveBeenCalled();
  });

  it('ignores storage events for unrelated keys', async () => {
    renderProbe();
    await act(async () => {
      window.dispatchEvent(new StorageEvent('storage', { key: 'language', newValue: 'zh-CN' }));
    });
    expect(screen.getByTestId('preference')).toHaveTextContent('system');
  });

  it('fails loudly when useTheme is used outside the provider', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => render(<Probe />)).toThrow(/must be used inside a <ThemeProvider>/);
    spy.mockRestore();
  });
});
