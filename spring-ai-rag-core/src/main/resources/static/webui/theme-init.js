// Pre-paint theme bootstrap.
// Loaded as an external same-origin script so the document needs no inline
// script and can ship a CSP without 'unsafe-inline'.
// It runs before first paint so a dark-theme user never sees a white flash.
// It resolves the same preference the React ThemeProvider will, and writes
// only the resolved `data-theme`; the logic itself lives in
// src/design-system/theme.ts so both sides stay in contract.
(function () {
  try {
    var stored = window.localStorage.getItem('theme');
    var preference =
      stored === 'light' || stored === 'dark' || stored === 'system' ? stored : 'system';
    var prefersDark =
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-color-scheme: dark)').matches;
    var resolved =
      preference === 'system' ? (prefersDark ? 'dark' : 'light') : preference;
    document.documentElement.setAttribute('data-theme', resolved);
  } catch (error) {
    // Blocked storage or an unavailable matchMedia must not block the app.
    document.documentElement.setAttribute('data-theme', 'light');
  }
})();
