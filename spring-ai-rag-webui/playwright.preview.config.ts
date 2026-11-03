import { defineConfig } from '@playwright/test';
import baseConfig from './playwright.config';

// Foolproofing for the Mock e2e suite: run against `vite preview` serving the
// production build. A preview server watches no files, so editing sources
// during a run cannot trigger HMR/full-page reloads that detach elements
// (the Batch 23/25 flake class). Backend-dependent `-real` specs are excluded.
// 视觉回归用独立的 `test:visual` 入口：它的基准图绑定平台（darwin/linux），
// 与 CI 的 Playwright 容器镜像必须使用同一渲染环境，所以不混进通用 Mock 套件。
export default defineConfig({
  ...baseConfig,
  testIgnore: ['**/*-real.spec.ts', '**/visual.spec.ts'],
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
