import { describe, it, expect } from 'vitest';
import {
  THEME_ATTRIBUTE,
  THEME_STORAGE_KEY,
  applyResolvedTheme,
  isThemePreference,
  readStoredPreference,
  resolveTheme,
} from './theme';

function storageWith(value: string | null) {
  return { getItem: (key: string) => (key === THEME_STORAGE_KEY ? value : null) };
}

describe('theme preference contract', () => {
  it('treats legacy light/dark values as explicit preferences', () => {
    expect(readStoredPreference(storageWith('light'))).toBe('light');
    expect(readStoredPreference(storageWith('dark'))).toBe('dark');
  });

  it('treats a missing value as follow-the-system', () => {
    expect(readStoredPreference(storageWith(null))).toBe('system');
  });

  it('degrades an unrecognised value to system instead of forcing a theme', () => {
    expect(readStoredPreference(storageWith('solarized'))).toBe('system');
    expect(readStoredPreference(storageWith(''))).toBe('system');
  });

  it('survives storage that throws (private mode / blocked cookies)', () => {
    const hostile = {
      getItem: () => {
        throw new Error('SecurityError');
      },
    };
    expect(readStoredPreference(hostile)).toBe('system');
  });

  it('recognises exactly the three public preferences', () => {
    expect(isThemePreference('light')).toBe(true);
    expect(isThemePreference('dark')).toBe(true);
    expect(isThemePreference('system')).toBe(true);
    expect(isThemePreference('auto')).toBe(false);
    expect(isThemePreference(undefined)).toBe(false);
  });
});

describe('resolveTheme', () => {
  it('returns an explicit preference untouched', () => {
    expect(resolveTheme('light', true)).toBe('light');
    expect(resolveTheme('dark', false)).toBe('dark');
  });

  it('follows the system only in system mode', () => {
    expect(resolveTheme('system', true)).toBe('dark');
    expect(resolveTheme('system', false)).toBe('light');
  });
});

describe('applyResolvedTheme', () => {
  it('writes only the resolved theme to the DOM', () => {
    const attributes: Record<string, string> = {};
    const root = { setAttribute: (key: string, value: string) => (attributes[key] = value) };

    applyResolvedTheme('dark', root);

    expect(attributes[THEME_ATTRIBUTE]).toBe('dark');
  });

  it('never writes the raw preference to the DOM', () => {
    const attributes: Record<string, string> = {};
    const root = { setAttribute: (key: string, value: string) => (attributes[key] = value) };

    applyResolvedTheme(resolveTheme('system', true), root);

    expect(Object.values(attributes)).toEqual(['dark']);
    expect(Object.values(attributes)).not.toContain('system');
  });
});
