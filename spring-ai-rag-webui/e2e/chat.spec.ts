import { test, expect } from '@playwright/test';
import {
  MOCK_CHAT_TURN_ID,
  mockAllApiCalls,
  openProtectedPage,
} from './api-mocks';

const AGENT_TURN_ID = '33333333-3333-4333-8333-333333333333';
const PLAIN_TURN_ID = '44444444-4444-4444-8444-444444444444';

test.describe('Chat', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/chat');
  });

  test('renders the composer and disables send without input', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Chat' })).toBeVisible();
    await expect(page.getByText('No messages yet. Start a conversation!')).toBeVisible();
    await expect(page.locator('textarea')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Send' })).toBeDisabled();

    await page.locator('textarea').fill('What is RAG?');
    await expect(page.getByRole('button', { name: 'Send' })).toBeEnabled();
  });

  test('sends the selected model and two collection keys in the SSE body', async ({ page }) => {
    let requestBody: Record<string, unknown> = {};
    await page.route('/api/v1/rag/chat/stream', async route => {
      requestBody = route.request().postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'X-RAG-Turn-Id': MOCK_CHAT_TURN_ID },
        body: `event: done\ndata: {"status":"complete","turnId":"${MOCK_CHAT_TURN_ID}"}\n\n`,
      });
    });

    await page.getByTestId('chat-model-select')
      .selectOption('openrouter/xiaomi/mimo-v2-pro');
    await page.getByTestId('chat-scope-SELECTED_COLLECTIONS').check();
    await page.getByRole('checkbox', { name: /Sample Collection/ }).check();
    await page.getByRole('checkbox', { name: /Product Manual/ }).check();
    await page.locator('textarea').fill('Use the selected scope');
    await page.getByRole('button', { name: 'Send' }).click();

    await expect.poll(() => requestBody).toEqual({
      message: 'Use the selected scope',
      mode: 'KNOWLEDGE',
      model: 'openrouter/xiaomi/mimo-v2-pro',
      collectionScopeMode: 'SELECTED_COLLECTIONS',
      collectionKeys: ['product-manual', 'sample-collection'],
    });
  });

  test('supports AGENT mode and renders structured tool activity and sources', async ({ page }) => {
    let requestBody: Record<string, unknown> = {};
    await page.route('/api/v1/rag/chat/stream', async route => {
      requestBody = route.request().postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'X-RAG-Turn-Id': AGENT_TURN_ID },
        body: [
          'event: tool_start',
          'data: {"tool":"searchKnowledge","query":"风格基调"}',
          '',
          'event: tool_result',
          'data: {"tool":"searchKnowledge","resultCount":2,"elapsedMs":8}',
          '',
          'event: content',
          'data: {"content":"根据知识库，风格基调是克制、清晰。"}',
          '',
          'event: sources',
          'data: {"sessionId":"agent-session-1","sources":[{"citationId":"S1","documentId":7,"title":"品牌风格指南","collectionKey":"product-manual","documentType":"TEXT"}]}',
          '',
          'event: done',
          `data: {"sessionId":"agent-session-1","status":"complete","turnId":"${AGENT_TURN_ID}","mode":"AGENT","resolvedModel":"minimax/MiniMax-M2.7"}`,
          '',
        ].join('\n'),
      });
    });

    await page.getByTestId('chat-mode-select').selectOption('AGENT');
    await page.locator('textarea').fill('查找“风格基调”相关内容');
    await page.getByRole('button', { name: 'Send' }).click();

    await expect.poll(() => requestBody).toMatchObject({
      message: '查找“风格基调”相关内容',
      mode: 'AGENT',
      model: 'minimax/MiniMax-M2.7',
    });
    await expect(page.getByText('Retrieval activity:')).toBeVisible();
    await expect(page.getByText(/Found 2 result\(s\) \(8 ms\)/)).toBeVisible();
    await expect(page.getByText('根据知识库，风格基调是克制、清晰。')).toBeVisible();
    await expect(page.getByText('Sources:')).toBeVisible();
    await expect(page.getByText('品牌风格指南')).toBeVisible();
    await expect(page).toHaveURL(
      /\/webui\/chat\/agent-session-1\?mode=AGENT$/,
    );
  });

  test('switching back to CALLER_VISIBLE removes stale collection keys', async ({ page }) => {
    const bodies: Array<Record<string, unknown>> = [];
    await page.route('/api/v1/rag/chat/stream', async route => {
      bodies.push(route.request().postDataJSON());
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'X-RAG-Turn-Id': MOCK_CHAT_TURN_ID },
        body: `event: done\ndata: {"status":"complete","turnId":"${MOCK_CHAT_TURN_ID}"}\n\n`,
      });
    });

    await page.getByTestId('chat-scope-SELECTED_COLLECTIONS').check();
    await page.getByRole('checkbox', { name: /Sample Collection/ }).check();
    await page.getByTestId('chat-scope-CALLER_VISIBLE').check();
    await page.locator('textarea').fill('Visible scope');
    await page.getByRole('button', { name: 'Send' }).click();

    await expect.poll(() => bodies.length).toBe(1);
    expect(bodies[0].collectionScopeMode).toBe('CALLER_VISIBLE');
    expect(bodies[0]).not.toHaveProperty('collectionKeys');
  });

  test('PLAIN mode omits retrieval scope from the request', async ({ page }) => {
    let requestBody: Record<string, unknown> = {};
    await page.route('/api/v1/rag/chat/stream', async route => {
      requestBody = route.request().postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'X-RAG-Turn-Id': PLAIN_TURN_ID },
        body: `event: done\ndata: {"status":"complete","turnId":"${PLAIN_TURN_ID}","mode":"PLAIN"}\n\n`,
      });
    });

    await page.getByTestId('chat-scope-SELECTED_COLLECTIONS').check();
    await page.getByTestId('chat-mode-select').selectOption('PLAIN');
    await expect(page.getByTestId('chat-scope-CALLER_VISIBLE')).toHaveCount(0);
    await page.locator('textarea').fill('Plain conversation');
    await page.getByRole('button', { name: 'Send' }).click();

    await expect.poll(() => requestBody).toEqual({
      message: 'Plain conversation',
      mode: 'PLAIN',
      model: 'minimax/MiniMax-M2.7',
    });
  });

  test('does not overflow on a mobile viewport', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByTestId('chat-scope-SELECTED_COLLECTIONS').check();
    await expect(page.getByTestId('chat-collection-options')).toBeVisible();

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    );
    expect(overflow).toBe(false);
  });

  test('creates an addressable session and restores it from chat history', async ({ page }) => {
    await page.route(/\/api\/v1\/rag\/chat\/history\/mock-session-123.*/, route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{
          id: 1,
          sessionId: 'mock-session-123',
          userMessage: 'Persist this question',
          aiResponse: 'Persisted answer',
          mode: 'KNOWLEDGE',
          sources: [{
            citationId: 'S2',
            documentId: 9,
            title: 'Persisted source',
            collectionKey: 'sample-collection',
          }],
          createdAt: '2026-08-17T08:00:00',
        }]),
      });
    });

    await page.locator('textarea').fill('Persist this question');
    await page.getByRole('button', { name: 'Send' }).click();
    await expect(page).toHaveURL(/\/webui\/chat\/mock-session-123$/);

    await page.reload();
    await expect(page).toHaveURL(/\/webui\/unlock$/);
    await page.getByTestId('root-api-key').fill(
      'root_test_0123456789_abcdefghijklmnopqrstuvwxyz',
    );
    await page.getByRole('button', { name: 'Unlock' }).click();
    await expect(page).toHaveURL(/\/webui\/chat\/mock-session-123$/);
    await expect(page.getByText('Persist this question')).toBeVisible();
    await expect(page.getByText('Persisted answer')).toBeVisible();
    await expect(page.getByText('Persisted source')).toBeVisible();
  });

  test('reuses the same key across a bounded conflict and keeps the prompt', async ({ page }) => {
    const requests: Array<{ key?: string; body: string | null }> = [];
    await page.route('/api/v1/rag/chat/stream', async route => {
      requests.push({
        key: route.request().headers()['idempotency-key'],
        body: route.request().postData(),
      });
      await route.fulfill({
        status: 409,
        headers: { 'Retry-After': '0' },
        contentType: 'application/problem+json',
        body: JSON.stringify({
          code: 'IDEMPOTENCY_OPERATION_IN_PROGRESS',
          message: 'Chat turn is still running',
        }),
      });
    });

    await page.locator('textarea').fill('Keep this prompt');
    await page.getByRole('button', { name: 'Send' }).click();

    await expect(page.getByRole('button', { name: 'Send' })).toBeVisible();
    await expect(page.locator('textarea')).toHaveValue('Keep this prompt');
    await expect(page.getByText('Error: HTTP 409')).toBeVisible();
    await expect.poll(() => requests.length).toBe(2);
    expect(requests[0].key).toBeTruthy();
    expect(requests[1].key).toBe(requests[0].key);
    expect(requests[1].body).toBe(requests[0].body);
  });

  test('replays a partial SSE attempt without duplicating the assistant bubble', async ({ page }) => {
    let requestCount = 0;
    await page.route('/api/v1/rag/chat/stream', async route => {
      requestCount += 1;
      if (requestCount === 1) {
        await route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          headers: { 'X-RAG-Turn-Id': MOCK_CHAT_TURN_ID },
          body: `event: content\ndata: {"content":"partial"}\n\n`,
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'X-RAG-Turn-Id': MOCK_CHAT_TURN_ID },
        body: [
          'event: content',
          'data: {"content":"final answer"}',
          '',
          'event: done',
          `data: {"status":"complete","turnId":"${MOCK_CHAT_TURN_ID}","sessionId":"replay-session"}`,
          '',
        ].join('\n'),
      });
    });

    await page.locator('textarea').fill('Replay this turn');
    await page.getByRole('button', { name: 'Send' }).click();

    await expect(page.getByText('final answer')).toBeVisible();
    await expect(page.getByText('final answer')).toHaveCount(1);
    await expect.poll(() => requestCount).toBe(2);
  });

  test('stop aborts local delivery without opening a retry request', async ({ page }) => {
    let requestCount = 0;
    await page.route('/api/v1/rag/chat/stream', async route => {
      requestCount += 1;
      await new Promise(resolve => setTimeout(resolve, 1_000));
      if (!route.request().isNavigationRequest()) {
        await route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          headers: { 'X-RAG-Turn-Id': MOCK_CHAT_TURN_ID },
          body: `event: done\ndata: {"status":"complete","turnId":"${MOCK_CHAT_TURN_ID}"}\n\n`,
        }).catch(() => undefined);
      }
    });

    await page.locator('textarea').fill('Stop this turn');
    await page.getByRole('button', { name: 'Send' }).click();
    await expect(page.getByRole('button', { name: 'Stop generation' })).toBeVisible();
    await page.getByRole('button', { name: 'Stop generation' }).click();

    await expect(page.getByRole('button', { name: 'Send' })).toBeVisible();
    await expect.poll(() => requestCount).toBe(1);
    await expect(page.locator('textarea')).toHaveValue('Stop this turn');
  });
});
