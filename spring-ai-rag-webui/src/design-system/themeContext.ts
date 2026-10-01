import { createContext, useContext } from 'react';
import type { ResolvedTheme, ThemePreference } from './theme';

export interface ThemeContextValue {
  /** What the user chose: an explicit theme, or follow-the-system. */
  preference: ThemePreference;
  /** What the document is actually rendering. */
  resolvedTheme: ResolvedTheme;
  setPreference: (next: ThemePreference) => void;
}

// The context and its consumer hook live apart from <ThemeProvider> so that
// module exports only a component, which keeps React Fast Refresh working.
export const ThemeContext = createContext<ThemeContextValue | null>(null);

export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext);
  if (context === null) {
    throw new Error('useTheme must be used inside a <ThemeProvider>');
  }
  return context;
}
