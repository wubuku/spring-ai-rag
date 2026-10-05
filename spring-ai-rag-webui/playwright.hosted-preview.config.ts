import { defineConfig } from '@playwright/test';
import baseConfig from './playwright.config';

// For host scripts that start their own `vite preview` and set `BASE_URL`
// themselves, rather than letting a config's `webServer` do both.
//
// `playwright.preview.config.ts` cannot serve those scripts directly: it pins
// `baseURL` to 15174 and brings a `webServer` with `reuseExistingServer: false`,
// which would collide with the port the script just found and started a second
// server anyway. So they need the ignore without the server — and without it
// they do not get the ignore at all, which is the defect this config exists to
// close.
//
// `verify-jsonb-records.sh` and `verify-release.sh` both ran a bare
// `npx playwright test` against a static preview. `playwright.config.ts` has no
// `testIgnore`, so each of them ran the whole e2e directory: 93 passed, and five
// `*-real.spec.ts` specs failed on every single run because they need a live
// backend and credentials a preview server has neither of. A step that is red
// for a reason nobody can act on is a step people learn to skip.
export default defineConfig({
  ...baseConfig,
  testIgnore: ['**/*-real.spec.ts'],
  // The host script owns the server; a config-owned one would start a second.
  webServer: undefined,
});
