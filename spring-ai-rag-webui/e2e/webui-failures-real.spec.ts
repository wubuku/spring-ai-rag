import { expect, test } from '@playwright/test';
import { rootHeaders } from './support/chat-lifecycle';
import { corruptPdfBuffer } from './support/pdf-fixture';
import { startRealProxy, type RealProxy } from './support/real-proxy';

const REAL = process.env.REAL_WEBUI_E2E === '1';
const BACKEND = process.env.REAL_WEBUI_BASE_URL ?? 'http://127.0.0.1:18081';
const COPY = {
  401: /Authentication failed/,
  403: /not allowed to perform that action/,
  404: /Failed to load documents/,
  422: /server rejected this request/,
  429: /Too many requests right now/,
  503: /model or embedding service is unavailable/,
  504: /took too long to answer/,
} as const;

let proxy: RealProxy;

test.describe.configure({ mode: 'serial' });
test.setTimeout(180_000);

test.beforeAll(async () => {
  test.skip(!REAL, 'REAL_WEBUI_E2E=1 is required');
  proxy = await startRealProxy(BACKEND);
});

test.afterAll(async () => {
  await proxy?.close();
});

test.afterEach(() => {
  proxy?.clearFailures();
});

for (const [status, copy] of Object.entries(COPY)) {
  test(`HTTP ${status} renders localized copy without a raw status`, async ({ page }) => {
    test.skip(!REAL, 'REAL_WEBUI_E2E=1 is required');

    await page.goto(`${proxy.baseURL}/webui/documents`, { waitUntil: 'networkidle' });
    if (new URL(page.url()).pathname === '/webui/unlock') {
      const keyInput = page.getByTestId('root-api-key');
      await keyInput.fill(rootHeaders()['X-API-Key']);
      await page.getByRole('button', { name: 'Unlock', exact: true }).click();
    }
    if (new URL(page.url()).pathname !== '/webui/documents') {
      await page.getByRole('link', { name: 'Documents', exact: true }).first().click();
    }
    await expect(page.getByRole('heading', { name: 'Documents', exact: true }))
      .toBeVisible();

    proxy.failAlways('GET', '/api/v1/rag/documents?', {
      status: Number(status),
      failureClass: failureClassFor(Number(status)),
      detail: 'The server rejected the read for a controlled acceptance check.',
      retryAfterSeconds: Number(status) === 429 ? 7 : undefined,
    });
    await page.getByRole('textbox').first().fill(`failure-probe-${status}`);

    if (Number(status) === 401) {
      await expect(page).toHaveURL(/\/webui\/unlock/, { timeout: 30_000 });
      const notice = page.getByRole('alert');
      await expect(notice, '401 回到解锁页时必须说明凭据已失效').toContainText(
        /credential is no longer accepted/,
      );
      expect(await notice.innerText()).not.toMatch(/\b401\b/);
      return;
    }

    const banner = page.getByRole('alert').filter({ hasText: copy }).first();
    // 5xx 会走真实客户端重试链：axios 3 次 + React Query 1 次，真实网络上
    // 到达最终失败态可能超过 30 秒。这里等的是"最终可读文案"，不是瞬时响应。
    await expect(banner, `HTTP ${status} 必须给出可读文案`).toBeVisible({ timeout: 90_000 });
    const text = await banner.innerText();
    expect(text, `HTTP ${status} 不应把裸状态码甩给用户`).not.toMatch(
      /\b(?:HTTP\s*)?(?:401|403|404|422|429|503|504)\b/,
    );
    if (Number(status) === 429) {
      expect(text).not.toMatch(/Retry-After/i);
    }
  });
}

test('a corrupt PDF gets an explicit 422 parse explanation', async ({ page }) => {
  test.skip(!REAL, 'REAL_WEBUI_E2E=1 is required');

  await page.goto('/webui/files', { waitUntil: 'networkidle' });
  if (new URL(page.url()).pathname === '/webui/unlock') {
    await page.getByTestId('root-api-key').fill(rootHeaders()['X-API-Key']);
    await page.getByRole('button', { name: 'Unlock', exact: true }).click();
  }
  await expect(page.getByRole('heading', { name: 'Files' })).toBeVisible();

  const rejected = page.waitForResponse(response =>
    response.url().endsWith('/api/v1/rag/files/pdf')
    && response.request().method() === 'POST',
  );
  await page.locator('input[type="file"]').setInputFiles({
    name: `corrupt-${Date.now()}.pdf`,
    mimeType: 'application/pdf',
    buffer: corruptPdfBuffer(),
  });
  const response = await rejected;
  expect(response.status(), '损坏 PDF 必须是 422 而不是 500').toBe(422);
  expect(response.headers()['x-rag-failure-class']).toBe('CLIENT_REQUEST');

  await expect(page.getByRole('alert').filter({
    hasText: /This PDF could not be parsed/,
  })).toBeVisible();
  await expect(page.getByRole('alert').filter({
    hasText: /scanned image PDF|encrypted|damaged/,
  })).toBeVisible();
});

function failureClassFor(status: number): string {
  switch (status) {
    case 401: return 'AUTHENTICATION';
    case 403: return 'AUTHORIZATION';
    case 404: return 'NOT_FOUND';
    case 422: return 'CLIENT_REQUEST';
    case 429: return 'RATE_LIMITED';
    case 503: return 'DEPENDENCY_UNAVAILABLE';
    case 504: return 'TIMEOUT';
    default: throw new Error(`no failure class for ${status}`);
  }
}
