import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

test.describe('Embeddings operations', () => {
  test('lists jobs from JSON and keeps URL filters', async ({ page }) => {
    await mockAllApiCalls(page);
    const jobs = page.waitForRequest(req =>
      req.url().includes('/api/v1/rag/embedding-jobs') && req.method() === 'GET');
    await openProtectedPage(page, '/webui/embeddings');
    await jobs;
    await expect(page.getByRole('heading', { name: /Embedding operations|嵌入任务运营/ })).toBeVisible();
    await expect(page.getByText('QUEUED')).toBeVisible();
    await page.getByPlaceholder('QUEUED').fill('FAILED');
    await expect(page).toHaveURL(/status=FAILED/);
  });
});
