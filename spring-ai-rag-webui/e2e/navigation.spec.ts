import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

async function waitForLayoutFrames(page: import('@playwright/test').Page) {
  await page.evaluate(() => new Promise<void>(resolve => {
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
  }));
}

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
      'Embeddings',
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
      '/webui/embeddings',
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

  test('does not leak the previous page scroll position across repeated navigation', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.unroute(/\/api\/v1\/rag\/api-keys(?:\/.*)?$/);
    await page.route('/api/v1/rag/api-keys/principals', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(Array.from({ length: 80 }, (_, index) => ({
          principalId: `rag_p_scroll_${index}`,
          name: `Scroll test principal ${index}`,
          role: 'NORMAL',
          capabilities: ['RAG_READ'],
          collectionAccessMode: 'UNRESTRICTED',
          policyVersion: 1,
          status: 'ACTIVE',
          expiresAt: '2099-12-31T23:59:59Z',
          createdAt: '2026-08-28T00:00:00Z',
          updatedAt: '2026-08-28T00:00:00Z',
        }))),
      });
    });
    await openProtectedPage(page, '/webui/api-keys');
    await expect(page.getByText('Scroll test principal 79')).toBeVisible();
    await waitForLayoutFrames(page);

    for (let attempt = 0; attempt < 3; attempt += 1) {
      const main = page.getByRole('main');
      await main.evaluate(element => {
        element.scrollTop = element.scrollHeight;
      });
      expect(await main.evaluate(element => element.scrollTop)).toBeGreaterThan(0);

      await page.getByRole('link', { name: /Files/ }).click();
      await expect(page).toHaveURL(/\/webui\/files(?:\?.*)?$/);
      await expect(page.getByText('Loading…', { exact: true })).toHaveCount(0);
      await expect.poll(async () => {
        const positions = await page.evaluate(() => ({
          window: window.scrollY,
          document: document.scrollingElement?.scrollTop ?? 0,
          main: document.querySelector('main')?.scrollTop ?? 0,
        }));
        return Math.max(positions.window, positions.document, positions.main);
      }).toBe(0);

      if (attempt < 2) {
        await page.getByRole('link', { name: /API Keys/ }).click();
        await expect(page).toHaveURL(/\/webui\/api-keys$/);
        await expect(page.getByText('Loading…', { exact: true })).toHaveCount(0);
        await expect(page.getByText('Scroll test principal 79')).toBeVisible();
        await waitForLayoutFrames(page);
      }
    }
  });
});
