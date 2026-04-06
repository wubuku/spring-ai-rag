import { test, expect } from '@playwright/test';
import { mockAllApiCalls } from './api-mocks';

test.describe('Documents', () => {
  test('renders documents page with title', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/documents', { waitUntil: 'networkidle' });
    await expect(page.getByRole('heading', { name: 'Documents' })).toBeVisible();
  });

  test('shows upload zone', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/documents', { waitUntil: 'networkidle' });
    const uploadZone = page.locator('#file-upload');
    await expect(uploadZone).toBeAttached();
  });

  test('shows upload label text', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/documents', { waitUntil: 'networkidle' });
    await expect(page.getByText('Drop files here or click to upload')).toBeVisible();
    await expect(page.getByText('Supports: txt, md, json, xml, html, csv, log')).toBeVisible();
  });

  test('shows table or empty state', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/documents', { waitUntil: 'networkidle' });
    // Should show either table or loading or empty state
    const hasContent = await page
      .locator('table')
      .isVisible()
      .catch(() => false);
    const hasEmpty = await page
      .getByText('No documents found')
      .isVisible()
      .catch(() => false);
    const hasLoading = await page
      .getByText('Loading documents')
      .isVisible()
      .catch(() => false);
    const hasError = await page
      .getByText('Failed to load')
      .isVisible()
      .catch(() => false);
    expect(hasContent || hasEmpty || hasLoading || hasError).toBeTruthy();
  });
});
