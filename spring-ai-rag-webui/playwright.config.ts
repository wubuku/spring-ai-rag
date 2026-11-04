import { defineConfig, devices } from '@playwright/test';

// E2E tests run against the Spring Boot backend (port 8081) which serves the webui at /webui/
const baseURL = process.env.BASE_URL || 'http://localhost:8081';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  // 不用重试掩盖偶发失败：5 轮 `--retries=0` 基线里唯一的失败是 WebKit
  // 长套件的上下文堆积（见下方 webkit 分片），已由测试基建隔离；其余用例
  // 必须一次通过。失败就是失败，`flaky` 计数不应成为常态。
  retries: 0,
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
    // 没有重试就没有 "first retry"，用保留失败 trace 才拿得到现场。
    trace: 'retain-on-failure',
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
    // 安全相关套件（可访问性 / 键盘 / 响应式）与崩溃/导航韧性套件在非 Chromium 引擎上再跑一遍。
    // 其余业务 spec 仍只跑 Chromium：它们依赖 Mock API 时序，跨引擎重跑
    // 收益低而成本高。这里用显式 testMatch，而不是静默 skip。
    {
      name: 'firefox',
      testMatch: /(accessibility|keyboard|responsive|resilience|sse-resume)\.spec\.ts/,
      use: { ...devices['Desktop Firefox'] },
    },
    // WebKit 的故障形态是"单浏览器进程内的上下文堆积"：一个 project 连续跑完
    // 61 个用例（即 61 个 BrowserContext）时，约第 58 个用例的首次导航会永久
    // 卡死——`goto` 超时，同页重试仍超时，而紧接着的下一个用例又恢复正常。
    // 5 轮 `--retries=0` 基线测得 1/5 轮、1/305 个 WebKit 用例翻车，Chromium
    // 与 Firefox 的 1130 个用例 0 失败，说明不是应用实现缺陷。
    //
    // 根因在进程级资源，不在单条断言，所以修法是"强制重启"：把 WebKit 拆成
    // 5 个 project。Playwright 在切换 project 时会重建 worker、关闭并重开
    // 浏览器进程，每个分片的上下文数因此不超过 19 个（约为观测阈值的 1/3），
    // 而不是靠 `openProtectedPage` 换页重试或 CI `retries` 掩盖。
    // 分片按"文件 + 宽度组"切，保持每片内部语义完整，不改变任何断言。
    {
      name: 'webkit-accessibility-keyboard',
      testMatch: /(accessibility|keyboard)\.spec\.ts/,
      timeout: 45_000,
      use: { ...devices['Desktop Safari'], navigationTimeout: 20_000 },
    },
    {
      name: 'webkit-responsive-375',
      testMatch: /responsive\.spec\.ts/,
      grep: /375px/,
      timeout: 45_000,
      use: { ...devices['Desktop Safari'], navigationTimeout: 20_000 },
    },
    {
      name: 'webkit-responsive-768',
      testMatch: /responsive\.spec\.ts/,
      grep: /768px/,
      timeout: 45_000,
      use: { ...devices['Desktop Safari'], navigationTimeout: 20_000 },
    },
    {
      name: 'webkit-responsive-1440',
      testMatch: /responsive\.spec\.ts/,
      grep: /1440px/,
      timeout: 45_000,
      use: { ...devices['Desktop Safari'], navigationTimeout: 20_000 },
    },
    {
      // 慢网 / 断网两条用例的标题不带宽度，用 grepInvert 保留它们。
      name: 'webkit-responsive-network',
      testMatch: /responsive\.spec\.ts/,
      grepInvert: /375px|768px|1440px/,
      timeout: 45_000,
      use: { ...devices['Desktop Safari'], navigationTimeout: 20_000 },
    },
  ],
  // E2E tests expect Spring Boot backend already running on port 8081
  // Start backend: cd spring-ai-rag && POSTGRES_HOST=localhost POSTGRES_USER=postgres POSTGRES_PASSWORD=123456 POSTGRES_DATABASE=spring_ai_rag_dev mvn spring-boot:run -pl spring-ai-rag-core
  // Then: cd spring-ai-rag-webui && npx playwright test
  webServer: undefined,
});
