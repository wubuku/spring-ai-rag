import { test, expect } from '@playwright/test';
import { mockAllApiCalls } from './api-mocks';

test.describe('Navigation', () => {
  test('sidebar navigation links are visible', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/dashboard', { waitUntil: 'networkidle' });
    const sidebar = page.locator('aside');
    await expect(sidebar).toBeVisible();

    // Labels include emoji icons in nav links (e.g. "📄 Documents")
    const navLinks = [
      'Dashboard',
      'Documents',
      'Collections',
      'Chat',
      'Search',
      'Metrics',
      'Evaluation',
      'Alerts',
      'Settings',
    ];

    for (const label of navLinks) {
      await expect(page.getByRole('link', { name: new RegExp(label) })).toBeVisible();
    }
  });

  test('navigates to all pages without crash', async ({ page }) => {
    await mockAllApiCalls(page);
    const routes = [
      '/webui/dashboard',
      '/webui/documents',
      '/webui/collections',
      '/webui/chat',
      '/webui/search',
      '/webui/metrics',
      '/webui/evaluation',
      '/webui/alerts',
      '/webui/settings',
    ];

    for (const route of routes) {
      await page.goto(route, { waitUntil: 'networkidle' });
      await expect(page.locator('aside')).toBeVisible();
    }
  });

  test('redirects root to dashboard', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.goto('/webui/', { waitUntil: 'networkidle' });
    await expect(page).toHaveURL(/\/dashboard/);
  });
});
