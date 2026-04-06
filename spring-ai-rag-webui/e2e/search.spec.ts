import { test, expect } from '@playwright/test';
import { mockAllApiCalls } from './api-mocks';

test.describe('Search', () => {
  test('renders search page with title', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/webui/search', { waitUntil: 'networkidle' });
    await expect(page.getByRole('heading', { name: 'Search' })).toBeVisible();
  });

  test('shows search input', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/webui/search', { waitUntil: 'networkidle' });
    await expect(page.locator('input[type="text"], input[placeholder*="search"]')).toBeVisible();
  });

  test('shows hybrid checkbox', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/webui/search', { waitUntil: 'networkidle' });
    await expect(page.getByText('Hybrid')).toBeVisible();
  });

  test('shows search button', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/webui/search', { waitUntil: 'networkidle' });
    await expect(page.getByRole('button', { name: 'Search' })).toBeVisible();
  });

  test('search button disabled when query is empty', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/webui/search', { waitUntil: 'networkidle' });
    await expect(page.getByRole('button', { name: 'Search' })).toBeDisabled();
  });

  test('typing query enables search button', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/webui/search', { waitUntil: 'networkidle' });
    const input = page.locator('input').first();
    await input.fill('test query');
    await expect(page.getByRole('button', { name: 'Search' })).toBeEnabled();
  });

  test('hybrid toggle is checked by default', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/webui/search', { waitUntil: 'networkidle' });
    const checkbox = page.getByText('Hybrid').locator('..').locator('input[type="checkbox"]');
    await expect(checkbox).toBeChecked();
  });
});
