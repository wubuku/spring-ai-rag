import { test, expect } from '@playwright/test';
import { mockAllApiCalls } from './api-mocks';

test.describe('Chat', () => {
  test('renders chat page with title', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/chat');
    await expect(page.getByRole('heading', { name: 'RAG Chat' })).toBeVisible();
  });

  test('shows empty state message', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/chat');
    await expect(page.getByText('Ask me anything about your documents')).toBeVisible();
  });

  test('shows input and send button', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/chat');
    await expect(page.locator('textarea')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Send' })).toBeVisible();
  });

  test('send button disabled when input is empty', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/chat');
    await expect(page.getByRole('button', { name: 'Send' })).toBeDisabled();
  });

  test('type in textarea enables send button', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/chat');
    const textarea = page.locator('textarea');
    await textarea.fill('What is RAG?');
    await expect(page.getByRole('button', { name: 'Send' })).toBeEnabled();
  });
});
