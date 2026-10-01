import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '../../design-system/ThemeProvider';
import { THEME_STORAGE_KEY } from '../../design-system/theme';
import { ThemeToggle } from './ThemeToggle';

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

function installMatchMedia(matches: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches,
      media: '(prefers-color-scheme: dark)',
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
}

function renderToggle() {
  return render(
    <ThemeProvider>
      <ThemeToggle />
    </ThemeProvider>,
  );
}

describe('ThemeToggle (tri-state)', () => {
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

  it('exposes exactly one control per preference, as a radio group', () => {
    renderToggle();
    const radios = screen.getAllByRole('radio');
    expect(radios).toHaveLength(3);
    expect(radios.map(radio => radio.getAttribute('aria-label'))).toEqual([
      'theme.light',
      'theme.dark',
      'theme.system',
    ]);
  });

  it('always shows all three states, so the current mode is never ambiguous', () => {
    localStorageMock.data[THEME_STORAGE_KEY] = 'dark';
    renderToggle();
    // The old control only revealed its "back to auto" affordance while locked.
    expect(screen.getAllByRole('radio')).toHaveLength(3);
  });

  it('checks the preference that is actually in effect', () => {
    localStorageMock.data[THEME_STORAGE_KEY] = 'light';
    renderToggle();
    expect(screen.getByRole('radio', { name: 'theme.light' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'theme.system' })).not.toBeChecked();
  });

  it('defaults to system when nothing is stored', () => {
    renderToggle();
    expect(screen.getByRole('radio', { name: 'theme.system' })).toBeChecked();
  });

  it('persists an explicit selection and applies it to the document', async () => {
    const user = userEvent.setup();
    renderToggle();
    await user.click(screen.getByRole('radio', { name: 'theme.dark' }));

    expect(localStorageMock.setItem).toHaveBeenCalledWith(THEME_STORAGE_KEY, 'dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(screen.getByRole('radio', { name: 'theme.dark' })).toBeChecked();
  });

  it('returns to following the system without needing a separate unlock button', async () => {
    installMatchMedia(true);
    const user = userEvent.setup();
    localStorageMock.data[THEME_STORAGE_KEY] = 'light';
    renderToggle();

    await user.click(screen.getByRole('radio', { name: 'theme.system' }));

    expect(screen.getByRole('radio', { name: 'theme.system' })).toBeChecked();
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('keeps the control reachable by keyboard alone', async () => {
    const user = userEvent.setup();
    renderToggle();

    await user.tab();
    expect(screen.getByRole('radio', { name: 'theme.system' })).toHaveFocus();

    await user.keyboard('[ArrowRight]');
    expect(document.documentElement.getAttribute('data-theme')).toBeDefined();
  });

  it('exposes an accessible group name', () => {
    renderToggle();
    expect(screen.getByRole('group')).toHaveAccessibleName('theme.label');
  });

  it('marks the active option for styling hooks', () => {
    localStorageMock.data[THEME_STORAGE_KEY] = 'system';
    renderToggle();
    const active = screen.getByRole('radio', { name: 'theme.system' }).closest('label');
    expect(active).toHaveAttribute('data-active', 'true');
  });
});
