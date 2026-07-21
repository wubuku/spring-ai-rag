import { test, expect } from '@playwright/test';
import { mockAllApiCalls } from './api-mocks';

test.describe('Chat', () => {
  test('renders chat page with title', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/chat', { waitUntil: 'networkidle' });
    await expect(page.getByRole('heading', { name: 'Chat' })).toBeVisible();
  });

  test('shows empty state message', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/chat', { waitUntil: 'networkidle' });
    await expect(page.getByText('No messages yet. Start a conversation!')).toBeVisible();
  });

  test('shows input and send button', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/chat', { waitUntil: 'networkidle' });
    await expect(page.locator('textarea')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Send' })).toBeVisible();
  });

  test('send button disabled when input is empty', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/chat', { waitUntil: 'networkidle' });
    await expect(page.getByRole('button', { name: 'Send' })).toBeDisabled();
  });

  test('type in textarea enables send button', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/chat', { waitUntil: 'networkidle' });
    const textarea = page.locator('textarea');
    await textarea.fill('What is RAG?');
    await expect(page.getByRole('button', { name: 'Send' })).toBeEnabled();
  });

  test('sends the selected model in the SSE request body', async ({ page }) => {
    await mockAllApiCalls(page);
    let requestedModel = '';
    await page.route('/api/v1/rag/chat/stream', async route => {
      requestedModel = route.request().postDataJSON().model;
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: 'event: done\ndata: {"status":"complete"}\n\n',
      });
    });
    await page.goto('/webui/chat', { waitUntil: 'networkidle' });

    await page.getByTestId('chat-model-select')
      .selectOption('openrouter/xiaomi/mimo-v2-pro');
    await page.locator('textarea').fill('Use the selected model');
    await page.getByRole('button', { name: 'Send' }).click();

    await expect.poll(() => requestedModel)
      .toBe('openrouter/xiaomi/mimo-v2-pro');
  });
});
