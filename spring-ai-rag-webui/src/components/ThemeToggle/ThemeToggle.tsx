import { useState, useEffect, useCallback } from 'react';
import styles from './ThemeToggle.module.css';

type Theme = 'light' | 'dark';

const STORAGE_KEY = 'theme';

/**
 * Returns the theme to apply immediately on mount.
 * Priority: (1) saved manual preference, (2) system preference.
 */
function getInitialTheme(): Theme {
  const saved = localStorage.getItem(STORAGE_KEY);
  if (saved === 'light' || saved === 'dark') return saved;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/** True when user has explicitly locked a theme in localStorage. */
function isThemeLocked(): boolean {
  return localStorage.getItem(STORAGE_KEY) !== null;
}

export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(getInitialTheme);
  const [locked, setLocked] = useState<boolean>(() => isThemeLocked());

  // Listen for system preference changes when in auto (unlocked) mode
  useEffect(() => {
    if (locked) return;

    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = (e: MediaQueryListEvent) => {
      const next: Theme = e.matches ? 'dark' : 'light';
      setTheme(next);
      document.documentElement.setAttribute('data-theme', next);
    };

    // Sync immediately (tab may have been backgrounded while system changed)
    document.documentElement.setAttribute('data-theme', theme);
    mq.addEventListener('change', handler);
    return () => mq.removeEventListener('change', handler);
  }, [locked, theme]);

  // Apply theme to DOM and persist when locked
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    if (locked) {
      localStorage.setItem(STORAGE_KEY, theme);
    }
  }, [theme, locked]);

  /** Clicking the main button: in auto → lock to current system; locked → toggle */
  const handleToggle = useCallback(() => {
    if (locked) {
      setTheme(t => (t === 'light' ? 'dark' : 'light'));
    } else {
      const systemDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
      const systemTheme: Theme = systemDark ? 'dark' : 'light';
      setTheme(systemTheme);
      setLocked(true);
    }
  }, [locked]);

  /** "A" button: return to auto (follow system) mode */
  const handleAutoMode = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY);
    const systemDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    setTheme(systemDark ? 'dark' : 'light');
    setLocked(false);
  }, []);

  const icon = locked
    ? theme === 'light' ? '☀️' : '🌙'
    : '🔄';

  const title = locked
    ? `Theme: ${theme} (locked) — click to toggle`
    : `Auto (system: ${theme}) — click to lock`;

  return (
    <div className={styles.wrapper}>
      <button
        className={styles.toggle}
        onClick={handleToggle}
        title={title}
        aria-label={title}
      >
        {icon}
      </button>
      {locked && (
        <button
          className={styles.autoBtn}
          onClick={handleAutoMode}
          title="Revert to auto (follow system)"
          aria-label="Switch to auto theme"
        >
          A
        </button>
      )}
    </div>
  );
}
