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

  test('does not rewrite URL filters during IME composition', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/embeddings');

    const input = page.getByRole('textbox', { name: 'Collection key' });
    await input.dispatchEvent('compositionstart', { data: '蓝' });
    await input.evaluate(element => {
      const target = element as HTMLInputElement;
      const setter = Object.getOwnPropertyDescriptor(
        HTMLInputElement.prototype,
        'value',
      )?.set;
      setter?.call(target, '蓝色手机');
      target.dispatchEvent(new InputEvent('input', {
        bubbles: true,
        data: '蓝色手机',
        inputType: 'insertCompositionText',
        isComposing: true,
      }));
    });

    await expect(input).toHaveValue('蓝色手机');
    await expect.poll(() => new URL(page.url()).searchParams.get('collectionKey'))
      .toBeNull();

    await input.dispatchEvent('compositionend', { data: '蓝色手机' });
    await expect.poll(() => new URL(page.url()).searchParams.get('collectionKey'))
      .toBe('蓝色手机');
  });

  test('previews and applies a bounded derivation repair from network JSON', async ({ page }) => {
    await mockAllApiCalls(page);
    const previewRequest = page.waitForRequest(request =>
      request.method() === 'POST'
      && new URL(request.url()).pathname.endsWith('/derivation-repairs/preview'));
    await openProtectedPage(page, '/webui/embeddings?collectionKey=sample-collection');

    await expect(page.getByRole('heading', { name: 'Derivation integrity' })).toBeVisible();
    await expect(page.getByText('Corrupt')).toBeVisible();
    await page.getByRole('button', { name: 'Preview repair' }).click();
    expect((await previewRequest).postDataJSON()).toEqual({
      collectionKey: 'sample-collection',
      buckets: ['CORRUPT', 'LOCAL_UNAVAILABLE'],
      vectorConditions: ['FAILED', 'STALE'],
      maxDocuments: 100,
    });

    const dialog = page.getByRole('dialog', { name: 'Repair preview' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('REBUILD_LOCAL_AND_QUEUE_VECTOR')).toBeVisible();
    const applyRequest = page.waitForRequest(request =>
      request.method() === 'POST'
      && new URL(request.url()).pathname.endsWith('/derivation-repairs/apply'));
    await dialog.getByRole('button', { name: 'Apply repair' }).click();
    expect((await applyRequest).postDataJSON()).toEqual({
      repairId: '33333333-3333-3333-3333-333333333333',
      collectionKey: 'sample-collection',
      previewToken: 'opaque-preview-token',
      previewFingerprint: 'preview-fingerprint',
    });
    await expect(dialog).toHaveCount(0);
  });
});
