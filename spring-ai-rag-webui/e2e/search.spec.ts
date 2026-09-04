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
    // KNOWN FLAKE: after clicking Next the page-two checkbox can be
    // re-mounted repeatedly for a while (element detached, retrying). A
    // dedicated investigation is queued in the hardening loop ledger; rerun
    // this spec when it fires.
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

  test('shows a plain-language match basis instead of a zero percent score', async ({ page }) => {
    await page.route(/\/api\/v1\/rag\/search(?:\?.*)?$/, route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          query: 'Spring AI',
          total: 1,
          results: [{
            documentId: '19',
            title: 'Spring AI Reference',
            chunkText: 'Spring AI retrieval result',
            score: 0.5,
            vectorScore: 0.7089,
            fulltextScore: 0,
            chunkIndex: 0,
          }],
        }),
      });
    });

    await searchInput(page).fill('Spring AI');
    await page.getByRole('button', { name: 'Search' }).click();

    await expect(page.getByText('Meaning match')).toBeVisible();
    await expect(page.getByText('#1')).toHaveCount(0);
    await expect(page.getByText('0.0%')).toHaveCount(0);
  });

  test('navigates from a PDF result to the indexed file preview', async ({ page }) => {
    await page.route(/\/api\/v1\/rag\/search(?:\?.*)?$/, route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          query: 'manual',
          total: 1,
          results: [{
            documentId: '7',
            title: 'Sample PDF',
            chunkText: 'Indexed Markdown',
            score: 0.8,
            vectorScore: 0.7,
            fulltextScore: 0.4,
            source: 'pdf-import:sample-pdf/default.md',
            originalFilename: 'sample.pdf',
            fileDirectoryPath: 'sample-pdf/',
            indexedFilePath: 'sample-pdf/default.md',
            originalFilePath: 'sample-pdf/original.pdf',
          }],
        }),
      });
    });

    await searchInput(page).fill('manual');
    await page.getByRole('button', { name: 'Search' }).click();
    await page.getByRole('button', { name: 'View indexed file' }).click();

    await expect(page).toHaveURL(/\/webui\/files\?/);
    await expect(page).toHaveURL(/path=sample-pdf%2F/);
    await expect(page).toHaveURL(/file=sample-pdf%2Fdefault.md/);
    await expect(page.getByText('Indexed Markdown')).toBeVisible();
  });

  test('restores search results after returning from the indexed file', async ({ page }) => {
    await page.route(/\/api\/v1\/rag\/search(?:\?.*)?$/, route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          query: 'manual',
          total: 1,
          results: [{
            documentId: '7',
            title: 'Sample PDF',
            chunkText: 'Indexed Markdown',
            score: 0.8,
            vectorScore: 0.7,
            fulltextScore: 0.4,
            source: 'pdf-import:sample-pdf/default.md',
            originalFilename: 'sample.pdf',
            fileDirectoryPath: 'sample-pdf/',
            indexedFilePath: 'sample-pdf/default.md',
            originalFilePath: 'sample-pdf/original.pdf',
          }],
        }),
      });
    });

    await searchInput(page).fill('manual');
    await page.getByRole('button', { name: 'Search' }).click();
    await expect(page.getByText('Sample PDF')).toBeVisible();

    await page.getByRole('button', { name: 'View indexed file' }).click();
    await expect(page.getByText('Indexed Markdown')).toBeVisible();

    await page.goBack();
    await expect(page).toHaveURL(/\/webui\/search\?query=manual/);
    await expect(page.getByText('Sample PDF')).toBeVisible();
    await expect(page.getByText('Indexed Markdown')).toBeVisible();

    await page.goForward();
    await expect(page).toHaveURL(
      /\/webui\/files\?path=sample-pdf%2F&file=sample-pdf%2Fdefault\.md$/,
    );
    await expect(page.getByText('Indexed Markdown')).toBeVisible();
  });

  test('restores a search URL after a full page reload and unlock', async ({ page }) => {
    await page.route(/\/api\/v1\/rag\/search(?:\?.*)?$/, route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          query: 'manual',
          total: 1,
          results: [{
            documentId: '7',
            title: 'Reloaded PDF result',
            chunkText: 'Reloaded indexed content',
            score: 0.8,
          }],
        }),
      });
    });

    await searchInput(page).fill('manual');
    await page.getByRole('button', { name: 'Search' }).click();
    await expect(page.getByText('Reloaded PDF result')).toBeVisible();

    await page.reload();
    await expect(page).toHaveURL(/\/webui\/unlock$/);
    await page.getByTestId('root-api-key').fill(
      'root_test_0123456789_abcdefghijklmnopqrstuvwxyz',
    );
    await page.getByRole('button', { name: 'Unlock' }).click();

    await expect(page).toHaveURL(/\/webui\/search\?query=manual/);
    await expect(page.getByText('Reloaded PDF result')).toBeVisible();
  });

  test('opens the original PDF through an authenticated API request', async ({ page }) => {
    await page.route(/\/api\/v1\/rag\/search(?:\?.*)?$/, route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          query: 'manual',
          total: 1,
          results: [{
            documentId: '7',
            title: 'Sample PDF',
            chunkText: 'Indexed Markdown',
            score: 0.8,
            source: 'pdf-import:sample-pdf/default.md',
            originalFilename: 'sample.pdf',
            fileDirectoryPath: 'sample-pdf/',
            indexedFilePath: 'sample-pdf/default.md',
            originalFilePath: 'sample-pdf/original.pdf',
          }],
        }),
      });
    });

    await searchInput(page).fill('manual');
    await page.getByRole('button', { name: 'Search' }).click();

    const rawRequest = page.waitForRequest(request =>
      request.url().includes('/api/v1/rag/files/raw?')
    );
    await page.getByRole('button', { name: 'Open original PDF' }).click();
    const request = await rawRequest;

    expect(new URL(request.url()).searchParams.get('path'))
      .toBe('sample-pdf/original.pdf');
    expect(request.headers()['x-api-key']).toBeTruthy();
  });
  test('turning hybrid off sends useHybrid=false and keeps it in the URL', async ({ page }) => {
    await searchInput(page).fill('keyword only query');
    await page.getByRole('checkbox', { name: 'Hybrid' }).uncheck();

    const requestPromise = page.waitForRequest(request =>
      request.url().includes('/api/v1/rag/search?')
    );
    await page.getByRole('button', { name: 'Search' }).click();
    const url = new URL((await requestPromise).url());

    expect(url.searchParams.get('useHybrid')).toBe('false');
    expect(new URL(page.url()).searchParams.get('hybrid')).toBe('false');
  });

});
