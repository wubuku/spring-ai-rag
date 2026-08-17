import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

test.describe('Files', () => {
  test('sorts imported directories by import time in both directions', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/files');

    const treeEntries = page.getByTestId('file-tree-entry');
    await expect(treeEntries).toHaveCount(3);
    await expect(treeEntries.nth(0)).toHaveAttribute('data-entry-path', 'newest-pdf/');
    await expect(treeEntries.nth(1)).toHaveAttribute('data-entry-path', 'sample-pdf/');
    await expect(treeEntries.nth(2)).toHaveAttribute('data-entry-path', 'older-pdf/');

    await page.getByTestId('files-import-time-sort').click();

    await expect(page).toHaveURL(/\/webui\/files\?sort=asc$/);
    await expect(treeEntries.nth(0)).toHaveAttribute('data-entry-path', 'older-pdf/');
    await expect(treeEntries.nth(1)).toHaveAttribute('data-entry-path', 'sample-pdf/');
    await expect(treeEntries.nth(2)).toHaveAttribute('data-entry-path', 'newest-pdf/');

    await page.getByRole('link', { name: /Search/ }).click();
    await page.goBack();
    await expect(page).toHaveURL(/\/webui\/files\?sort=asc$/);
    await expect(treeEntries.nth(0)).toHaveAttribute('data-entry-path', 'older-pdf/');
  });

  test('sends the selected collection key when adding a PDF to RAG', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/files');

    await page.getByText('sample-pdf').click();
    await page.getByTestId('files-rag-collection-select').selectOption('sample-collection');

    const requestPromise = page.waitForRequest(request =>
      request.url().includes('/api/v1/rag/files/sample-pdf/embed?')
    );
    await page.getByRole('button', { name: 'Add to RAG' }).click();
    const request = await requestPromise;
    const url = new URL(request.url());

    expect(url.searchParams.get('collectionKey')).toBe('sample-collection');
  });

  test('opens a deep-linked directory and previews its indexed file', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(
      page,
      '/webui/files?path=sample-pdf%2F&file=sample-pdf%2Fdefault.md',
    );

    await expect(page).toHaveURL(/path=sample-pdf%2F/);
    await expect(page).toHaveURL(/file=sample-pdf%2Fdefault.md/);
    await expect(page.getByTitle('default.md')).toBeVisible();
    await expect(page.getByText('Indexed Markdown')).toBeVisible();
  });

  test('records directory and file preview navigation in browser history', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/files');

    await page.getByTestId('file-tree-entry').filter({ hasText: 'sample-pdf' }).click();
    await expect(page).toHaveURL(/\/webui\/files\?path=sample-pdf%2F$/);

    await page.getByTitle('default.md').click();
    await expect(page).toHaveURL(
      /\/webui\/files\?path=sample-pdf%2F&file=sample-pdf%2Fdefault\.md$/,
    );
    await expect(page.getByText('Indexed Markdown')).toBeVisible();

    await page.goBack();
    await expect(page).toHaveURL(/\/webui\/files\?path=sample-pdf%2F$/);
    await expect(page.getByText('Indexed Markdown')).toHaveCount(0);
    await expect(page.getByTitle('default.md')).toBeVisible();

    await page.goForward();
    await expect(page).toHaveURL(
      /\/webui\/files\?path=sample-pdf%2F&file=sample-pdf%2Fdefault\.md$/,
    );
    await expect(page.getByText('Indexed Markdown')).toBeVisible();
  });
});
