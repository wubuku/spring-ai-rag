import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

test.describe('Files', () => {
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
});
