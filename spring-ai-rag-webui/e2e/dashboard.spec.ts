import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

test.describe('Dashboard', () => {
  test('renders dashboard page with title', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/dashboard');
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  });

  test('shows status banner', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/dashboard');
    const banner = page.locator('[data-healthy]');
    await expect(banner).toBeVisible();
  });

  test('shows stat cards', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/dashboard');
    await expect(page.getByText('Documents', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('Collections', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('Cache', { exact: true }).first()).toBeVisible();
    await expect(page.getByText(/Last Check/i).first()).toBeVisible();
  });
});
