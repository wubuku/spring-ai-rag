import { test, expect } from '@playwright/test';
import { mockAllApiCalls } from './api-mocks';

test.describe('Dashboard', () => {
  test('renders dashboard page with title', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  });

  test('shows status banner', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/dashboard');
    const banner = page.locator('[data-healthy]');
    await expect(banner).toBeVisible();
  });

  test('shows stat cards', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/dashboard');
    await expect(page.getByText('Documents')).toBeVisible();
    await expect(page.getByText('Collections')).toBeVisible();
    await expect(page.getByText('Cache')).toBeVisible();
    await expect(page.getByText('Last Check')).toBeVisible();
  });
});
