import { test, expect, type Page } from '@playwright/test';
import {
  MOCK_CHAT_TURN_ID,
  mockAllApiCalls,
  openProtectedPage,
} from './api-mocks';

/**
 * 失败分类的端到端呈现（Mock 层，不需要真实模型）。
 *
 * 这些用例守的是"用户看到什么"，不是后端怎么分类：
 *  - 分类必须翻成一句人能读懂的话，而不是 `HTTP 503` 或供应商英文；
 *  - 可重试的失败必须给出重试入口；
 *  - 不完整回答必须标记，且不能永远停在 loading；
 *  - 软失败（200 + status=FAILED）不能被提示成成功。
 */

const CHAT_URL = '/webui/chat';
const SEARCH_URL = '/webui/search';
const DOCUMENTS_URL = '/webui/documents';

async function sendChatMessage(page: Page, message: string) {
  await page.locator('textarea').fill(message);
  await page.getByRole('button', { name: 'Send' }).click();
}

test.describe('failure modes in the WebUI', () => {
  test('chat shows localized upstream copy and a retry action instead of the raw provider error', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.route('/api/v1/rag/chat/stream', async route => {
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        headers: { 'X-RAG-Failure-Class': 'DEPENDENCY_UNAVAILABLE' },
        body: JSON.stringify({
          detail: 'Provider exploded: upstream returned HTTP 503 (vendor stack trace)',
        }),
      });
    });
    await openProtectedPage(page, CHAT_URL);

    await sendChatMessage(page, 'Hello');

    await expect(page.getByText(
      'The model or embedding service is unavailable right now. Try again in a moment.',
    ).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
    await expect(page.getByText(/Provider exploded|vendor stack trace/)).toHaveCount(0);
    // 失败后必须退出 loading：光标消失、输入框仍可继续对话。
    await expect(page.getByText('|', { exact: true })).toHaveCount(0);
    await expect(page.locator('textarea')).toBeEnabled();
    await page.locator('textarea').fill('Another question');
    await expect(page.getByRole('button', { name: 'Send' })).toBeEnabled();
    await expect(page.getByRole('button', { name: 'Send' })).not.toHaveText('Stop');
  });

  test('chat retries the same prompt into a fresh turn', async ({ page }) => {
    await mockAllApiCalls(page);
    let requests = 0;
    await page.route('/api/v1/rag/chat/stream', async route => {
      requests += 1;
      // hook 只对瞬时失败做一次内部重试（`MAX_ATTEMPTS = 2`）。让前两次都
      // 被限流，才能既验证"自动重试有界"，又拿到用户的第二条重试路径。
      if (requests <= 2) {
        await route.fulfill({
          status: 429,
          contentType: 'application/json',
          headers: { 'X-RAG-Failure-Class': 'RATE_LIMITED', 'Retry-After': '1' },
          body: JSON.stringify({ detail: 'quota exceeded' }),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'X-RAG-Turn-Id': MOCK_CHAT_TURN_ID },
        body: [
          'event: content',
          'data: {"content":"Recovered answer"}',
          '',
          'event: done',
          `data: {"status":"complete","turnId":"${MOCK_CHAT_TURN_ID}"}`,
          '',
        ].join('\n'),
      });
    });
    await openProtectedPage(page, CHAT_URL);

    await sendChatMessage(page, 'Hello again');
    await expect(page.getByText(
      'Too many requests right now. Wait a moment, then try again.',
    ).first()).toBeVisible();
    // 自动重试只允许一次，不能无限重连。
    expect(requests).toBe(2);

    await page.getByRole('button', { name: 'Retry' }).click();

    await expect(page.getByText('Recovered answer')).toBeVisible();
    await expect(page.getByText(
      'Too many requests right now. Wait a moment, then try again.',
    )).toHaveCount(0);
    await expect.poll(() => requests).toBe(3);
    // 重试复用用户已经发出的 prompt，不要求重新输入。
    await expect(page.locator('textarea')).toHaveValue('');
  });

  test('chat marks an incomplete answer without pretending it finished cleanly', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.route('/api/v1/rag/chat/stream', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'X-RAG-Turn-Id': MOCK_CHAT_TURN_ID },
        body: [
          'event: content',
          'data: {"content":"Partial answer that stopped"}',
          '',
          'event: done',
          `data: {"status":"incomplete","turnId":"${MOCK_CHAT_TURN_ID}"}`,
          '',
        ].join('\n'),
      });
    });
    await openProtectedPage(page, CHAT_URL);

    await sendChatMessage(page, 'Tell me something long');

    await expect(page.getByText('Partial answer that stopped')).toBeVisible();
    await expect(page.getByText('Incomplete', { exact: true })).toBeVisible();
    // 不完整是终态：loading 必须结束，并且用户可以继续对话或重试。
    await expect(page.getByText('|', { exact: true })).toHaveCount(0);
    await expect(page.locator('textarea')).toBeEnabled();
    await page.locator('textarea').fill('Next question');
    await expect(page.getByRole('button', { name: 'Send' })).toBeEnabled();
    await expect(page.getByText(/The answer stream ended without a finish signal/)).toBeVisible();
    // 不完整回答不再被当成"可评价的完整回答"。
    await expect(page.getByRole('button', { name: 'Helpful' })).toHaveCount(0);
  });

  test('chat recovers a truncated stream with a bounded retry and a retry entry', async ({ page }) => {
    await mockAllApiCalls(page);
    let requests = 0;
    await page.route('/api/v1/rag/chat/stream', async route => {
      requests += 1;
      // 有内容但没有 done：模拟 SSE 中途断流。hook 只允许一次内部重试，
      // 所以断言请求数有界，而不是无限重连。
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'X-RAG-Turn-Id': MOCK_CHAT_TURN_ID },
        body: [
          'event: content',
          'data: {"content":"half an answer"}',
          '',
        ].join('\n'),
      });
    });
    await openProtectedPage(page, CHAT_URL);

    await sendChatMessage(page, 'Hello');

    await expect(page.getByText(
      'The answer stream stopped before it finished.',
    ).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
    await expect(page.getByText('|', { exact: true })).toHaveCount(0);
    expect(requests).toBeGreaterThan(0);
    expect(requests).toBeLessThanOrEqual(2);
  });

  test('search maps a rate limit to readable copy with a retry action', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.route(/\/api\/v1\/rag\/search(?:\?.*)?$/, async route => {
      await route.fulfill({
        status: 429,
        contentType: 'application/json',
        headers: { 'X-RAG-Failure-Class': 'RATE_LIMITED' },
        body: JSON.stringify({ detail: 'HTTP 429 from provider' }),
      });
    });
    await openProtectedPage(page, SEARCH_URL);

    await page.getByPlaceholder('Search documents…').fill('anything');
    await page.getByRole('button', { name: 'Search' }).click();

    await expect(page.getByText(
      'Too many requests right now. Wait a moment, then try again.',
    )).toBeVisible();
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
    await expect(page.getByText(/HTTP 429 from provider/)).toHaveCount(0);
  });

  test('embeddings soft failure is reported as an error, not as a started retry', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.route('**/api/v1/rag/documents/4/embed**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'X-RAG-Failure-Class': 'TIMEOUT' },
        body: JSON.stringify({
          documentId: 4,
          status: 'FAILED',
          failureClass: 'TIMEOUT',
          failureDetails: { reason: 'UPSTREAM_TIMEOUT', retryable: true },
        }),
      });
    });
    await openProtectedPage(page, DOCUMENTS_URL);

    const row = page.locator('tr').filter({
      hasText: 'Keyword Only Lifecycle Document',
    });
    await row.getByRole('button', {
      name: /Open actions for.*Keyword Only Lifecycle Document/,
    }).click();
    await page.getByRole('menuitem', { name: 'Retry embedding' }).click();

    const alert = page.getByRole('alert');
    await expect(alert).toContainText(
      'The model or embedding service took too long to answer. Try again.',
    );
    await expect(alert).not.toContainText('Embedding retry started');
  });

  test('upload shows a localized rate-limit message instead of the HTTP status', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.route('**/api/v1/rag/documents/upload', async route => {
      await route.fulfill({
        status: 429,
        contentType: 'application/json',
        headers: { 'X-RAG-Failure-Class': 'RATE_LIMITED' },
        body: JSON.stringify({ detail: 'HTTP 429' }),
      });
    });
    await openProtectedPage(page, DOCUMENTS_URL);

    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: 'note.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('failure-mode upload'),
    });

    const alert = page.getByRole('alert');
    await expect(alert).toContainText(
      'Too many requests right now. Wait a moment, then try again.',
    );
    await expect(alert).not.toContainText(/HTTP 429/);
  });
});
