import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.REAL_WEBUI_BASE_URL ?? 'http://127.0.0.1:18081';

/**
 * 真实后端 WebUI 套件。
 *
 * <p>默认不参与 PR 快层：只有设置 `REAL_WEBUI_E2E=1` 才会执行（nightly 或显式
 * 开关）。Chromium 跑全量；Firefox/WebKit 只跑一条打包产物的主链路，且各用
 * 独立 project、`workers=1`，避免 WebKit 在约 58 个上下文后卡死的已知问题。
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 300_000,
  expect: { timeout: 30_000 },
  reporter: [
    ['list'],
    ['json', { outputFile: 'test-results/real-results.json' }],
  ],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium-real',
      // 只跑本轮真实 WebUI 套件。其它 `*-real.spec.ts`（alerts、chat-outcomes、
      // rerank、agent 工具模型）依赖各自的夹具与专用 provider，见对应脚本。
      testMatch: ['**/webui-*-real.spec.ts'],
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'firefox-real-main',
      testMatch: ['**/webui-journey-real.spec.ts'],
      grep: /main journey/,
      use: { ...devices['Desktop Firefox'] },
    },
    {
      name: 'webkit-real-main',
      testMatch: ['**/webui-journey-real.spec.ts'],
      grep: /main journey/,
      use: { ...devices['Desktop Safari'] },
    },
  ],
});
