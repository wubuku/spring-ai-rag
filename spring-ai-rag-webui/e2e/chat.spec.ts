import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

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
        body: 'event: done\ndata: {"status":"complete"}\n\n',
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
      model: 'openrouter/xiaomi/mimo-v2-pro',
      collectionScopeMode: 'SELECTED_COLLECTIONS',
      collectionKeys: ['product-manual', 'sample-collection'],
    });
  });

  test('switching back to CALLER_VISIBLE removes stale collection keys', async ({ page }) => {
    const bodies: Array<Record<string, unknown>> = [];
    await page.route('/api/v1/rag/chat/stream', async route => {
      bodies.push(route.request().postDataJSON());
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: 'event: done\ndata: {"status":"complete"}\n\n',
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
  });
});
