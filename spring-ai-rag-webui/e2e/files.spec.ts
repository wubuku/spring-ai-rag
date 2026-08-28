import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

test.describe('Files', () => {
  test('keeps a stable file-manager workspace and remembers the resized directory panel', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/files');

    const commandBar = page.getByTestId('files-command-bar');
    const workspace = page.getByTestId('files-workspace');
    const treePanel = page.getByTestId('files-tree-panel');
    const previewPanel = page.getByTestId('files-preview-panel');
    const splitter = page.getByTestId('files-tree-splitter');
    const collectionSelect = page.getByTestId('files-rag-collection-select');
    const addToRag = page.getByRole('button', { name: 'Add to RAG' });

    await expect(commandBar).toBeVisible();
    await expect(page.getByTestId('files-rag-actions')).toHaveCount(0);

    const rootWorkspaceBox = await workspace.boundingBox();
    const rootCommandBox = await commandBar.boundingBox();
    const rootTreeBox = await treePanel.boundingBox();
    const rootPreviewBox = await previewPanel.boundingBox();
    expect(rootWorkspaceBox).not.toBeNull();
    expect(rootCommandBox).not.toBeNull();
    expect(rootTreeBox).not.toBeNull();
    expect(rootPreviewBox).not.toBeNull();

    const splitterBox = await splitter.boundingBox();
    expect(splitterBox).not.toBeNull();
    await page.mouse.move(
      splitterBox!.x + splitterBox!.width / 2,
      splitterBox!.y + splitterBox!.height / 2,
    );
    await page.mouse.down();
    await page.mouse.move(
      splitterBox!.x + splitterBox!.width / 2 + 72,
      splitterBox!.y + splitterBox!.height / 2,
    );
    await page.mouse.up();

    const resizedTreeBox = await treePanel.boundingBox();
    expect(resizedTreeBox!.width).toBeGreaterThan(rootTreeBox!.width + 60);
    const rememberedWidth = Number(await splitter.getAttribute('aria-valuenow'));
    expect(rememberedWidth).toBeGreaterThan(320);

    await page.getByTitle('Readable manual.pdf').click();
    await expect(page.getByRole('button', { name: 'Open folder' })).toBeVisible();
    await expect(page).toHaveURL(/\/webui\/files$/);
    await page.getByTitle('Readable manual.pdf').dblclick();
    await expect(page).toHaveURL(/\/webui\/files\?path=sample-pdf%2F$/);
    await expect(page.getByTestId('files-rag-actions')).toBeVisible();
    await expect(collectionSelect).toBeEnabled();
    await expect(addToRag).toBeEnabled();

    const childWorkspaceBox = await workspace.boundingBox();
    const childCommandBox = await commandBar.boundingBox();
    const childTreeBox = await treePanel.boundingBox();
    const childPreviewBox = await previewPanel.boundingBox();
    expect(Math.abs(childWorkspaceBox!.y - rootWorkspaceBox!.y)).toBeLessThan(1);
    expect(Math.abs(childWorkspaceBox!.height - rootWorkspaceBox!.height)).toBeLessThan(1);
    expect(Math.abs(childCommandBox!.height - rootCommandBox!.height)).toBeLessThan(1);
    expect(Math.abs(childTreeBox!.width - resizedTreeBox!.width)).toBeLessThan(1);
    expect(Math.abs(childPreviewBox!.x - (resizedTreeBox!.x + resizedTreeBox!.width + splitterBox!.width)))
      .toBeLessThan(2);

    await page.getByRole('button', { name: 'Root', exact: true }).click();
    await expect(page).toHaveURL(/\/webui\/files$/);
    await expect(splitter).toHaveAttribute('aria-valuenow', String(rememberedWidth));
    expect(Math.abs((await treePanel.boundingBox())!.width - resizedTreeBox!.width)).toBeLessThan(1);
  });

  test('does not rewrite the URL while Chinese IME composition is active', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/files');

    const query = page.getByLabel('Find in this folder');
    await query.dispatchEvent('compositionstart', { data: '中' });
    await query.evaluate(element => {
      const input = element as HTMLInputElement;
      const setter = Object.getOwnPropertyDescriptor(
        HTMLInputElement.prototype,
        'value',
      )?.set;
      setter?.call(input, '中文');
      input.dispatchEvent(new InputEvent('input', {
        bubbles: true,
        data: '中文',
        inputType: 'insertCompositionText',
        isComposing: true,
      }));
    });

    await expect(query).toHaveValue('中文');
    await page.waitForTimeout(350);
    expect(new URL(page.url()).searchParams.get('q')).toBeNull();

    await query.dispatchEvent('compositionend', { data: '中文' });
    await expect(page).toHaveURL(/\?q=%E4%B8%AD%E6%96%87$/);
    await expect(query).toHaveValue('中文');
  });

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

  test('keeps the parent action visible for an empty filtered folder', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/files?path=sample-pdf%2F&q=missing');

    await expect(page.getByTestId('files-parent-entry')).toBeVisible();
    await expect(page.getByText('No matches in this folder')).toBeVisible();
    await page.getByTestId('files-parent-entry').click();
    await expect(page).toHaveURL(/\/webui\/files$/);
  });

  test('records directory and file preview navigation in browser history', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/files');

    await page.getByTestId('file-tree-entry').filter({ hasText: 'sample-pdf' }).dblclick();
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
