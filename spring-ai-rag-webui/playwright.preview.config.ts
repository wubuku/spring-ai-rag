import { defineConfig } from '@playwright/test';
import baseConfig from './playwright.config';

// Foolproofing for the Mock e2e suite: run against `vite preview` serving the
// production build. A preview server watches no files, so editing sources
// during a run cannot trigger HMR/full-page reloads that detach elements
// (the Batch 23/25 flake class). Backend-dependent `-real` specs are excluded.
export default defineConfig({
  ...baseConfig,
  testIgnore: ['**/*-real.spec.ts'],
  use: {
    ...baseConfig.use,
    baseURL: 'http://127.0.0.1:15174',
  },
  webServer: {
    command: 'npm run build && npx vite preview --port 15174 --strictPort',
    url: 'http://127.0.0.1:15174/webui/',
    reuseExistingServer: false,
    timeout: 180_000,
  },
});
