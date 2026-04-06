import { test, expect } from '@playwright/test';
import { mockAllApiCalls } from './api-mocks';

test.describe('Navigation', () => {
  test('sidebar navigation links are visible', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/webui/dashboard', { waitUntil: 'networkidle' });
    const sidebar = page.locator('aside');
    await expect(sidebar).toBeVisible();

    const navLinks = [
      'Dashboard',
      'Documents',
      'Collections',
      'Chat',
      'Search',
      'Metrics',
      'Alerts',
      'Settings',
    ];

    for (const label of navLinks) {
      await expect(page.getByText(label, { exact: true }).first()).toBeVisible();
    }
  });

  test('navigates to all pages without crash', async ({ page }) => {
    mockAllApiCalls(page);
    const routes = [
      '/dashboard',
      '/documents',
      '/collections',
      '/chat',
      '/search',
      '/metrics',
      '/alerts',
      '/settings',
    ];

    for (const route of routes) {
      await page.goto(route, { waitUntil: 'networkidle' });
      await expect(page.locator('aside')).toBeVisible();
    }
  });

  test('redirects root to dashboard', async ({ page }) => {
    mockAllApiCalls(page);
    await page.goto('/webui/', { waitUntil: 'networkidle' });
    await expect(page).toHaveURL(/\/dashboard/);
  });
});
