/**
 * Theme contract.
 *
 * The DOM only ever carries a *resolved* theme (`data-theme="light|dark"`).
 * Business components must never branch on the raw preference to pick their own
 * colours; they read design tokens, which the resolved theme already selects.
 *
 * Storage stays backward compatible with the original two-button toggle, which
 * persisted only `light` or `dark`. A missing or unrecognised value means
 * "follow the system", so upgrading never forces a theme on the user.
 */

export type ThemePreference = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'theme';
export const THEME_ATTRIBUTE = 'data-theme';
export const DARK_SCHEME_QUERY = '(prefers-color-scheme: dark)';

export const THEME_PREFERENCES: readonly ThemePreference[] = ['light', 'dark', 'system'];

export function isThemePreference(value: unknown): value is ThemePreference {
  return value === 'light' || value === 'dark' || value === 'system';
}

/**
 * Read the stored preference. Legacy `light`/`dark` values stay valid; anything
 * missing or unknown degrades to `system` rather than to a fixed theme.
 */
export function readStoredPreference(storage: Pick<Storage, 'getItem'>): ThemePreference {
  let raw: string | null = null;
  try {
    raw = storage.getItem(THEME_STORAGE_KEY);
  } catch {
    // Private-mode / blocked storage must not break the first paint.
    return 'system';
  }
  return isThemePreference(raw) ? raw : 'system';
}

export function resolveTheme(preference: ThemePreference, systemPrefersDark: boolean): ResolvedTheme {
  if (preference === 'system') return systemPrefersDark ? 'dark' : 'light';
  return preference;
}

export function systemPrefersDark(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  return window.matchMedia(DARK_SCHEME_QUERY).matches;
}

/**
 * Write the resolved theme to the document. This is the only function allowed to
 * touch `data-theme`, which keeps the pre-paint bootstrap and React in sync.
 */
export function applyResolvedTheme(
  theme: ResolvedTheme,
  root: Pick<Element, 'setAttribute'> = document.documentElement,
): void {
  root.setAttribute(THEME_ATTRIBUTE, theme);
}
