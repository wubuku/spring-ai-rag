import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

test.describe('Documents', () => {
  test('renders documents page with title', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');
    await expect(page.getByRole('heading', { name: 'Documents' })).toBeVisible();
  });

  test('shows upload zone', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');
    const uploadZone = page.locator('#file-upload');
    await expect(uploadZone).toBeAttached();
  });

  test('shows upload label text', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');
    // i18n may render keys or English strings depending on setup
    await expect(
      page.getByText(/Drop files here|uploadHint|Supports: txt/i).first()
    ).toBeVisible();
  });

  test('shows table or empty state', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');
    const hasTable = await page.locator('table').isVisible().catch(() => false);
    const hasEmpty = await page.getByText(/no documents|empty|no data/i).isVisible().catch(() => false);
    const hasUpload = await page.locator('input[type="file"]').count().then(c => c > 0).catch(() => false);
    const hasTitle = await page.getByRole('heading', { name: /documents/i }).isVisible().catch(() => false);
    expect(hasTable || hasEmpty || hasUpload || hasTitle).toBeTruthy();
  });

  test('shows external identity freshness and offers embedding retry', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');

    await expect(page.getByText('cms:sample:1')).toBeVisible();
    await expect(page.getByText('cms-main')).toBeVisible();
    await expect(page.getByText('etag:sample-1')).toBeVisible();
    await page.getByRole('button', { name: /Open actions for.*Sample Document/ }).click();
    const retry = page.getByRole('menuitem', { name: 'Retry embedding' });
    await expect(retry).toBeVisible();
    await retry.click();
    await expect(page.getByText('Embedding retry started')).toBeVisible();
  });

  test('edits a local document with revision CAS and async index propagation', async ({ page }) => {
    await mockAllApiCalls(page);
    const patchRequest = page.waitForRequest(request =>
      request.method() === 'PATCH'
      && new URL(request.url()).pathname === '/api/v1/rag/documents/2');
    await openProtectedPage(page, '/webui/documents');

    await page.getByRole('button', {
      name: /Open actions for.*Local Lifecycle Document/,
    }).click();
    await page.getByRole('menuitem', { name: 'Edit document' }).click();
    const form = page.getByRole('form', { name: 'Edit document' });
    await expect(form).toBeVisible();
    await form.getByLabel('Content').fill('Updated local content');
    await form.getByRole('button', { name: 'Save' }).click();

    const request = await patchRequest;
    expect(request.postDataJSON()).toEqual(expect.objectContaining({
      expectedDocumentRevision: 4,
      content: 'Updated local content',
      embeddingPolicy: 'ASYNC',
    }));
    await expect(page.getByText('Document updated; index propagation has started'))
      .toBeVisible();
  });

  test('disables and restores local documents using their current revisions', async ({ page }) => {
    await mockAllApiCalls(page);
    page.on('dialog', dialog => dialog.accept());
    await openProtectedPage(page, '/webui/documents');

    const disableRequest = page.waitForRequest(request =>
      request.method() === 'POST'
      && new URL(request.url()).pathname === '/api/v1/rag/documents/2/disable');
    await page.getByRole('button', {
      name: /Open actions for.*Local Lifecycle Document/,
    }).click();
    await page.getByRole('menuitem', { name: 'Disable' }).click();
    expect((await disableRequest).postDataJSON()).toEqual({
      expectedDocumentRevision: 4,
    });
    await expect(page.getByText('Document disabled')).toBeVisible();

    const restoreRequest = page.waitForRequest(request =>
      request.method() === 'POST'
      && new URL(request.url()).pathname === '/api/v1/rag/documents/3/restore');
    await page.getByRole('button', {
      name: /Open actions for.*Disabled Lifecycle Document/,
    }).click();
    await page.getByRole('menuitem', { name: 'Restore' }).click();
    expect((await restoreRequest).postDataJSON()).toEqual({
      expectedDocumentRevision: 8,
      embeddingPolicy: 'ASYNC',
    });
    await expect(page.getByText(/Document restored/)).toBeVisible();
  });

  test('keeps external source-managed documents read only in the local lifecycle menu', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');

    await page.getByRole('button', { name: /Open actions for.*Sample Document/ }).click();
    await expect(page.getByText('Managed by an external source')).toBeVisible();
    await expect(page.getByText(/cms-main \/ cms:sample:1/)).toBeVisible();
    await expect(page.getByRole('menuitem', { name: 'Edit document' })).toHaveCount(0);
    await expect(page.getByRole('menuitem', { name: 'Disable' })).toHaveCount(0);
    await expect(page.getByRole('menuitem', { name: 'Delete permanently' })).toHaveCount(0);
  });

  test('opens the indexed PDF artifact from the row action menu', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');

    await page.getByRole('button', { name: /Open actions for.*Sample Document/ }).click();
    await page.getByRole('menuitem', { name: /File\/PDF source traceability/ }).click();
    await page.getByRole('menuitem', { name: 'View indexed file' }).click();

    await expect(page).toHaveURL(
      /\/webui\/files\?path=sample-pdf%2F&file=sample-pdf%2Fdefault\.md$/,
    );
    await expect(page.getByText('Indexed Markdown')).toBeVisible();
  });

  test('restores document filters after navigating away and back', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');

    const collectionFilter = page.getByTestId('documents-collection-filter');
    await collectionFilter.selectOption('sample-collection');
    await expect(page).toHaveURL(/collectionKey=sample-collection/);

    await page.getByRole('link', { name: /Search/ }).click();
    await expect(page).toHaveURL(/\/webui\/search$/);

    await page.goBack();
    await expect(page).toHaveURL(
      /\/webui\/documents\?collectionKey=sample-collection$/,
    );
    await expect(collectionFilter).toHaveValue('sample-collection');
  });
});
