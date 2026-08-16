import { test, expect, type Page } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

const searchInput = (page: Page) =>
  page.getByPlaceholder('Search documents…');

test.describe('Search', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/search');
  });

  test('renders search controls and keeps submit disabled without a query', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Search' })).toBeVisible();
    await expect(searchInput(page)).toBeVisible();
    await expect(page.getByText('Hybrid')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Search' })).toBeDisabled();

    await searchInput(page).fill('test query');
    await expect(page.getByRole('button', { name: 'Search' })).toBeEnabled();
  });

  test('sends ANY_COLLECTION without collection keys', async ({ page }) => {
    await page.getByTestId('search-scope-ANY_COLLECTION').check();
    await searchInput(page).fill('assigned documents');

    const requestPromise = page.waitForRequest(request =>
      request.url().includes('/api/v1/rag/search?')
    );
    await page.getByRole('button', { name: 'Search' }).click();
    const url = new URL((await requestPromise).url());

    expect(url.searchParams.get('collectionScopeMode')).toBe('ANY_COLLECTION');
    expect(url.searchParams.getAll('collectionKeys')).toEqual([]);
  });

  test('sends two selected collection keys and removes them after mode reset', async ({ page }) => {
    await page.getByTestId('search-scope-SELECTED_COLLECTIONS').check();
    await page.getByRole('checkbox', { name: /Sample Collection/ }).check();
    await page.getByRole('checkbox', { name: /Product Manual/ }).check();
    await searchInput(page).fill('scoped query');

    const selectedRequest = page.waitForRequest(request =>
      request.url().includes('/api/v1/rag/search?')
    );
    await page.getByRole('button', { name: 'Search' }).click();
    const selectedUrl = new URL((await selectedRequest).url());

    expect(selectedUrl.searchParams.get('collectionScopeMode'))
      .toBe('SELECTED_COLLECTIONS');
    expect(selectedUrl.searchParams.getAll('collectionKeys'))
      .toEqual(['product-manual', 'sample-collection']);

    await page.getByTestId('search-scope-CALLER_VISIBLE').check();
    const visibleRequest = page.waitForRequest(request =>
      request.url().includes('/api/v1/rag/search?')
    );
    await page.getByRole('button', { name: 'Search' }).click();
    const visibleUrl = new URL((await visibleRequest).url());

    expect(visibleUrl.searchParams.get('collectionScopeMode')).toBe('CALLER_VISIBLE');
    expect(visibleUrl.searchParams.getAll('collectionKeys')).toEqual([]);
  });

  test('loads a collection from the second page and keeps it selected', async ({ page }) => {
    await page.route(/\/api\/v1\/rag\/collections.*/, async route => {
      const url = new URL(route.request().url());
      const offset = Number(url.searchParams.get('offset') ?? 0);
      const query = url.searchParams.get('query') ?? '';
      const target = {
        id: 51,
        collectionKey: 'page-two-target',
        name: 'Page Two Target',
        embeddingModel: 'bge-m3',
        dimensions: 1024,
        documentCount: 4,
      };
      const collections = query
        ? (query.includes('target') ? [target] : [])
        : offset >= 50
          ? [target]
          : [{
              id: 1,
              collectionKey: 'page-one',
              name: 'Page One',
              embeddingModel: 'bge-m3',
              dimensions: 1024,
              documentCount: 1,
            }];
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          collections,
          total: query ? collections.length : 51,
          offset,
          limit: 50,
        }),
      });
    });

    await page.getByTestId('search-scope-SELECTED_COLLECTIONS').check();
    await page.getByRole('button', { name: 'Next' }).click();
    await page.getByRole('checkbox', { name: /Page Two Target/ }).check();
    await searchInput(page).fill('page two');

    const requestPromise = page.waitForRequest(request =>
      request.url().includes('/api/v1/rag/search?')
    );
    await page.getByRole('button', { name: 'Search' }).click();
    const url = new URL((await requestPromise).url());
    expect(url.searchParams.getAll('collectionKeys')).toEqual(['page-two-target']);
  });

  test('does not overflow on a mobile viewport', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByTestId('search-scope-SELECTED_COLLECTIONS').check();
    await expect(page.getByTestId('search-collection-options')).toBeVisible();

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    );
    expect(overflow).toBe(false);
  });
});
