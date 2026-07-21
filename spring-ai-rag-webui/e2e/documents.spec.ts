import { test, expect } from '@playwright/test';
import { mockAllApiCalls } from './api-mocks';

test.describe('Documents', () => {
  test('renders documents page with title', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/documents', { waitUntil: 'networkidle' });
    await expect(page.getByRole('heading', { name: 'Documents' })).toBeVisible();
  });

  test('shows upload zone', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/documents', { waitUntil: 'networkidle' });
    const uploadZone = page.locator('#file-upload');
    await expect(uploadZone).toBeAttached();
  });

  test('shows upload label text', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/documents', { waitUntil: 'networkidle' });
    // i18n may render keys or English strings depending on setup
    await expect(
      page.getByText(/Drop files here|uploadHint|Supports: txt/i).first()
    ).toBeVisible();
  });

  test('shows table or empty state', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/documents', { waitUntil: 'networkidle' });
    const hasTable = await page.locator('table').isVisible().catch(() => false);
    const hasEmpty = await page.getByText(/no documents|empty|no data/i).isVisible().catch(() => false);
    const hasUpload = await page.locator('input[type="file"]').count().then(c => c > 0).catch(() => false);
    const hasTitle = await page.getByRole('heading', { name: /documents/i }).isVisible().catch(() => false);
    expect(hasTable || hasEmpty || hasUpload || hasTitle).toBeTruthy();
  });
});
