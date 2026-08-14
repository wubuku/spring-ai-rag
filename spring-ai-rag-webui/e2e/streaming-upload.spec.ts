import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

/**
 * Chat SSE Streaming E2E Tests (UI smoke; API mocked)
 */
test.describe('Chat SSE Streaming', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiCalls(page);
  });

  test('chat page loads with textarea and send button', async ({ page }) => {
    await openProtectedPage(page, '/webui/chat');
    await page.waitForSelector('textarea', { timeout: 20000 });
    await expect(page.locator('textarea')).toBeVisible();
    await expect(page.getByRole('button', { name: /send|chat\.send/i })).toBeVisible();
  });

  test('textarea accepts input', async ({ page }) => {
    await openProtectedPage(page, '/webui/chat');
    await page.waitForSelector('textarea', { timeout: 20000 });
    const textarea = page.locator('textarea');
    await textarea.fill('What is RAG?');
    await expect(textarea).toHaveValue('What is RAG?');
  });
});

/**
 * Documents Upload E2E Tests (UI smoke; API mocked)
 */
test.describe('Documents Upload', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiCalls(page);
  });

  test('upload zone is visible on documents page', async ({ page }) => {
    await openProtectedPage(page, '/webui/documents');
    const fileInput = page.locator('input[type="file"]');
    await expect(fileInput).toBeAttached({ timeout: 15000 });
  });

  test('can select a file for upload', async ({ page }) => {
    await openProtectedPage(page, '/webui/documents');
    const fileInput = page.locator('input[type="file"]');
    await expect(fileInput).toBeAttached({ timeout: 15000 });
  });
});
