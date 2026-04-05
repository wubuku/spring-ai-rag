import { defineConfig, devices } from '@playwright/test';

// E2E tests run against the Spring Boot backend (port 8081) which serves the webui at /webui/
const baseURL = process.env.BASE_URL || 'http://localhost:8081/webui';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: [
    ['list'],
    ['html', { open: 'never' }],
  ],
  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  // E2E tests expect Spring Boot backend already running on port 8081
  // Start backend: cd spring-ai-rag && POSTGRES_HOST=localhost POSTGRES_USER=postgres POSTGRES_PASSWORD=123456 POSTGRES_DATABASE=spring_ai_rag_dev mvn spring-boot:run -pl spring-ai-rag-core
  // Then: cd spring-ai-rag-webui && npx playwright test
  webServer: undefined,
});
