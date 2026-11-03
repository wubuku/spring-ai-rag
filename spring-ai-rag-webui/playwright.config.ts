import { defineConfig, devices } from '@playwright/test';

// E2E tests run against the Spring Boot backend (port 8081) which serves the webui at /webui/
const baseURL = process.env.BASE_URL || 'http://localhost:8081';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: [
    ['list'],
    ['html', { open: 'never' }],
    // CI 诊断：`github` 把失败直接标注到 PR 文件行；JSON 报告由
    // `.github/scripts/ci-summary.mjs` 汇总成"哪个 spec 的哪个用例失败"。
    ...(process.env.CI ? [['github'] as const] : []),
    ['json', { outputFile: 'test-results/results.json' }],
  ],
  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'off',
    // 三引擎连续跑时，vite preview 首次加载大 bundle 在 WebKit 上偶尔超过 30s；
    // 45s 是加载预算，不是断言放宽（所有内容断言仍按默认超时）。
    navigationTimeout: 45_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    // 安全相关套件（可访问性 / 键盘 / 响应式）在非 Chromium 引擎上再跑一遍。
    // 其余业务 spec 仍只跑 Chromium：它们依赖 Mock API 时序，跨引擎重跑
    // 收益低而成本高。这里用显式 testMatch，而不是静默 skip。
    {
      name: 'firefox',
      testMatch: /(accessibility|keyboard|responsive)\.spec\.ts/,
      use: { ...devices['Desktop Firefox'] },
    },
    {
      name: 'webkit',
      testMatch: /(accessibility|keyboard|responsive)\.spec\.ts/,
      // WebKit 首次并发加载大 bundle 偶发导航卡住（Chromium / Firefox 不出现），
      // `openProtectedPage` 会换页重试一次；单次导航预算 20s、整条用例 45s，
      // 即使 context 已卡死也能快速失败并交给测试级重试，而不是长时间挂住。
      timeout: 45_000,
      use: { ...devices['Desktop Safari'], navigationTimeout: 20_000 },
    },
  ],
  // E2E tests expect Spring Boot backend already running on port 8081
  // Start backend: cd spring-ai-rag && POSTGRES_HOST=localhost POSTGRES_USER=postgres POSTGRES_PASSWORD=123456 POSTGRES_DATABASE=spring_ai_rag_dev mvn spring-boot:run -pl spring-ai-rag-core
  // Then: cd spring-ai-rag-webui && npx playwright test
  webServer: undefined,
});
