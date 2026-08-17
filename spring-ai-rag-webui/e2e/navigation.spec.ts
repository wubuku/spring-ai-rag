import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

test.describe('Navigation', () => {
  test('sidebar navigation links are visible', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/dashboard');
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
      'A/B Test',
      'API Keys',
      'Files',
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
      '/webui/abtest',
      '/webui/api-keys',
      '/webui/files',
      '/webui/settings',
    ];

    for (const route of routes) {
      await openProtectedPage(page, route);
      await expect(page.locator('aside')).toBeVisible();
    }
  });

  test('redirects root to dashboard', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/');
    await expect(page).toHaveURL(/\/dashboard/);
  });

  test('sidebar navigation participates in browser back and forward history', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/dashboard');

    await page.getByRole('link', { name: /Search/ }).click();
    await expect(page).toHaveURL(/\/webui\/search$/);

    await page.goBack();
    await expect(page).toHaveURL(/\/webui\/dashboard$/);

    await page.goForward();
    await expect(page).toHaveURL(/\/webui\/search$/);
  });
});
