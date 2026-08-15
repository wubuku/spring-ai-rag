import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

test.describe('Search', () => {
  test('renders search page with title', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/search');
    await expect(page.getByRole('heading', { name: 'Search' })).toBeVisible();
  });

  test('shows search input', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/search');
    await expect(page.locator('input').first()).toBeVisible();
  });

  test('shows hybrid checkbox', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/search');
    await expect(page.getByText('Hybrid')).toBeVisible();
  });

  test('shows search button', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/search');
    await expect(page.getByRole('button', { name: 'Search' })).toBeVisible();
  });

  test('search button disabled when query is empty', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/search');
    await expect(page.getByRole('button', { name: 'Search' })).toBeDisabled();
  });

  test('typing query enables search button', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/search');
    const input = page.locator('input').first();
    await input.fill('test query');
    await expect(page.getByRole('button', { name: 'Search' })).toBeEnabled();
  });

  test('hybrid toggle is checked by default', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/search');
    const checkbox = page.getByText('Hybrid').locator('..').locator('input[type="checkbox"]');
    await expect(checkbox).toBeChecked();
  });

  test('sends the selected collection key', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/search');
    await page.getByTestId('search-collection-select').selectOption('sample-collection');
    await page.locator('input').first().fill('scoped query');

    const requestPromise = page.waitForRequest(request =>
      request.url().includes('/api/v1/rag/search?')
    );
    await page.getByRole('button', { name: 'Search' }).click();
    const request = await requestPromise;
    const url = new URL(request.url());

    expect(url.searchParams.getAll('collectionKeys')).toEqual(['sample-collection']);
  });
});
