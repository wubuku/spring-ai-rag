import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import {
  DARK_SCHEME_QUERY,
  THEME_STORAGE_KEY,
  applyResolvedTheme,
  readStoredPreference,
  resolveTheme,
  systemPrefersDark,
} from './theme';
import { ThemeContext } from './themeContext';
import type { ThemePreference } from './theme';
import type { ThemeContextValue } from './themeContext';

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [preference, setPreferenceState] = useState<ThemePreference>(() =>
    readStoredPreference(window.localStorage),
  );
  const [systemDark, setSystemDark] = useState<boolean>(() => systemPrefersDark());

  // Follow OS changes, but only while the user has not chosen an explicit theme.
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia(DARK_SCHEME_QUERY);
    const handleChange = (event: MediaQueryListEvent) => setSystemDark(event.matches);
    setSystemDark(query.matches);
    query.addEventListener('change', handleChange);
    return () => query.removeEventListener('change', handleChange);
  }, []);

  // Another tab changed the preference. Re-read it, but never write back, so the
  // two tabs cannot ping-pong storage events against each other.
  useEffect(() => {
    const handleStorage = (event: StorageEvent) => {
      if (event.key !== null && event.key !== THEME_STORAGE_KEY) return;
      setPreferenceState(readStoredPreference(window.localStorage));
    };
    window.addEventListener('storage', handleStorage);
    return () => window.removeEventListener('storage', handleStorage);
  }, []);

  const resolvedTheme = resolveTheme(preference, systemDark);

  useEffect(() => {
    applyResolvedTheme(resolvedTheme);
  }, [resolvedTheme]);

  const setPreference = useCallback((next: ThemePreference) => {
    setPreferenceState(next);
    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch {
      // Persisting is best-effort: the visual theme still applies for this tab.
    }
  }, []);

  const value = useMemo<ThemeContextValue>(
    () => ({ preference, resolvedTheme, setPreference }),
    [preference, resolvedTheme, setPreference],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}
