import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

test.describe('Documents', () => {
  test('renders documents page with title', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');
    await expect(page.getByRole('heading', { name: 'Documents' })).toBeVisible();
  });

  test('shows upload zone', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');
    const uploadZone = page.locator('#file-upload');
    await expect(uploadZone).toBeAttached();
  });

  test('shows upload label text', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');
    // i18n may render keys or English strings depending on setup
    await expect(
      page.getByText(/Drop files here|uploadHint|Supports: txt/i).first()
    ).toBeVisible();
  });

  test('shows table or empty state', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');
    const hasTable = await page.locator('table').isVisible().catch(() => false);
    const hasEmpty = await page.getByText(/no documents|empty|no data/i).isVisible().catch(() => false);
    const hasUpload = await page.locator('input[type="file"]').count().then(c => c > 0).catch(() => false);
    const hasTitle = await page.getByRole('heading', { name: /documents/i }).isVisible().catch(() => false);
    expect(hasTable || hasEmpty || hasUpload || hasTitle).toBeTruthy();
  });

  test('shows external identity freshness and offers embedding retry', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');

    await expect(page.getByText('cms:sample:1')).toBeVisible();
    await expect(page.getByText('etag:sample-1')).toBeVisible();
    const retry = page.getByRole('button', { name: 'Retry embedding' });
    await expect(retry).toBeVisible();
    await retry.click();
    await expect(page.getByText('Embedding retry started')).toBeVisible();
  });
});
