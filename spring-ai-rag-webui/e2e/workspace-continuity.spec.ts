import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

async function expectDialogDesign(
  page: Page,
  name: string | RegExp,
): Promise<Locator> {
  const dialog = page.getByRole('dialog', { name });
  await expect(dialog).toBeVisible();
  await expect(dialog).toHaveAttribute('aria-modal', 'true');
  await expect(page.locator('body')).toHaveCSS('overflow', 'hidden');
  expect(await dialog.evaluate(element => element.contains(document.activeElement)))
    .toBe(true);

  const panelBackground = await dialog.evaluate(element =>
    getComputedStyle(element).backgroundColor);
  expect(panelBackground).not.toBe('rgba(0, 0, 0, 0)');
  expect(panelBackground).not.toBe('transparent');

  const backdrop = page.getByTestId('dialog-backdrop');
  await expect(backdrop).toBeVisible();
  const backdropBackground = await backdrop.evaluate(element =>
    getComputedStyle(element).backgroundColor);
  expect(backdropBackground).not.toBe('rgba(0, 0, 0, 0)');
  expect(backdropBackground).not.toBe('transparent');
  expect(Number(await backdrop.evaluate(element => getComputedStyle(element).zIndex)))
    .toBeGreaterThan(0);
  return dialog;
}

async function closeDialogWithEscape(
  page: Page,
  dialog: Locator,
  trigger: Locator,
) {
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await expect(page.locator('body')).not.toHaveCSS('overflow', 'hidden');
}

test.describe('Workspace continuity', () => {
  test('all sidebar destinations restore their last legal deep link', async ({ page }) => {
    const remembered = {
      '/dashboard': '/dashboard?view=operations',
      '/documents': '/documents?collectionKey=sample-collection&keyword=manual&page=1',
      '/collections': '/collections?sort=name',
      '/chat': '/chat/mock-session-123?mode=AGENT',
      '/search': '/search?query=manual&hybrid=false&scopeMode=ANY_COLLECTION',
      '/metrics': '/metrics?from=2026-08-01',
      '/evaluation': '/evaluation?tab=suites',
      '/embeddings': '/embeddings?status=FAILED&collectionKey=sample-collection',
      '/alerts': '/alerts?tab=notification-deliveries&status=FAILED',
      '/abtest': '/abtest/1',
      '/api-keys': '/api-keys?filter=active',
      '/files': '/files?path=sample-pdf%2F&file=sample-pdf%2Fdefault.md',
      '/settings': '/settings?tab=cache',
    };
    await page.addInitScript(routes => {
      if (!sessionStorage.getItem('spring-ai-rag:webui:v1:routes')) {
        sessionStorage.setItem(
          'spring-ai-rag:webui:v1:routes',
          JSON.stringify(routes),
        );
      }
    }, remembered);
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/dashboard');

    const destinations = [
      ['Documents', remembered['/documents']],
      ['Collections', remembered['/collections']],
      ['Chat', remembered['/chat']],
      ['Search', remembered['/search']],
      ['Metrics', remembered['/metrics']],
      ['Evaluation', remembered['/evaluation']],
      ['Embeddings', remembered['/embeddings']],
      ['Alerts', remembered['/alerts']],
      ['A/B Test', remembered['/abtest']],
      ['API Keys', remembered['/api-keys']],
      ['Files', remembered['/files']],
      ['Settings', remembered['/settings']],
    ] as const;

    for (const [label, path] of destinations) {
      await page.getByRole('link', { name: new RegExp(label) }).click();
      await expect(page).toHaveURL(new RegExp(`/webui${path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`));
    }
  });

  test('Chat, Search, and Files restore necessary state after route unmount', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/chat');

    await page.getByTestId('chat-mode-select').selectOption('AGENT');
    await page.locator('textarea').fill('Unsent chat draft');
    await page.getByRole('link', { name: /Search/ }).click();
    await page.getByRole('link', { name: /Chat/ }).click();
    await expect(page).toHaveURL(/\/webui\/chat\?mode=AGENT$/);
    await expect(page.locator('textarea')).toHaveValue('Unsent chat draft');

    await page.getByRole('link', { name: /Search/ }).click();
    await page.getByPlaceholder('Search documents…').fill('Unsubmitted search draft');
    await page.getByRole('link', { name: /Files/ }).click();
    await page.getByRole('link', { name: /Search/ }).click();
    await expect(page.getByPlaceholder('Search documents…'))
      .toHaveValue('Unsubmitted search draft');

    await page.getByRole('link', { name: /Files/ }).click();
    await page.getByTitle('Readable manual.pdf').dblclick();
    await expect(page).toHaveURL(/path=sample-pdf%2F/);
    await expect(page.getByTitle('default.md')).toBeVisible();
    await expect(page.getByTestId('files-rag-actions')).toBeVisible();
    await page.getByTestId('files-rag-collection-select')
      .selectOption('sample-collection');
    await page.getByLabel('Find in this folder').fill('default');
    await page.getByRole('link', { name: /Chat/ }).click();
    await page.getByRole('link', { name: /Files/ }).click();
    await expect(page).toHaveURL(/path=sample-pdf%2F/);
    await expect(page).toHaveURL(/q=default/);
    await expect(page.getByTestId('files-rag-collection-select'))
      .toHaveValue('sample-collection');
  });

  test('A/B Test and Collection create dialogs share the accessible opaque design', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/collections');

    const createTrigger = page.getByRole('button', { name: /Create Collection/ });
    await createTrigger.click();
    const createDialog = await expectDialogDesign(page, 'Create Collection');
    await expect(page.getByPlaceholder('e.g., Product Documentation')).toBeFocused();
    await closeDialogWithEscape(page, createDialog, createTrigger);

    await page.getByRole('link', { name: /A\/B Test/ }).click();
    const experimentTrigger = page.getByRole('button', { name: 'New Experiment' });
    await experimentTrigger.click();
    const experimentDialog = await expectDialogDesign(page, 'New Experiment');
    await expect(page.getByPlaceholder('e.g., Hybrid vs Rerank Search')).toBeFocused();
    await closeDialogWithEscape(page, experimentDialog, experimentTrigger);
  });

  test('Collection purge dialog follows the same design and restores its trigger', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/collections');

    const sampleCollection = page.getByText('Sample Collection', { exact: true })
      .locator('..');
    const purgeTrigger = sampleCollection.getByRole('button', {
      name: 'Permanently purge',
    });
    await purgeTrigger.click();
    const purgeDialog = await expectDialogDesign(
      page,
      'Permanently purge and retire Collection',
    );
    await expect(page.getByLabel(/Type sample-collection/)).toBeVisible();
    await closeDialogWithEscape(page, purgeDialog, purgeTrigger);
  });

  test('Document confirmations and history use shared dialogs without native confirm', async ({ page }) => {
    const nativeDialogs: string[] = [];
    page.on('dialog', async dialog => {
      nativeDialogs.push(dialog.type());
      await dialog.dismiss();
    });
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');

    const actionsTrigger = page.getByRole('button', {
      name: /Open actions.*Local Lifecycle Document/,
    });
    await actionsTrigger.click();
    await page.getByRole('menuitem', { name: 'Disable' }).click();
    const disableDialog = await expectDialogDesign(page, 'Disable');
    expect(nativeDialogs).toEqual([]);
    await closeDialogWithEscape(page, disableDialog, actionsTrigger);

    await actionsTrigger.click();
    await page.getByRole('menuitem', { name: 'Versions' }).click();
    const versionDialog = await expectDialogDesign(
      page,
      /Version History.*Local Lifecycle Document/,
    );
    await expect(versionDialog.getByText('Initial version')).toBeVisible();
    await closeDialogWithEscape(page, versionDialog, actionsTrigger);

    const reembedPanelTrigger = page.getByRole('button', {
      name: /documents need re-embedding/i,
    });
    await reembedPanelTrigger.click();
    const forceTrigger = page.getByRole('button', { name: 'Force Re-embed' });
    await forceTrigger.click();
    const forceDialog = await expectDialogDesign(page, 'Force Re-embed');
    expect(nativeDialogs).toEqual([]);
    await closeDialogWithEscape(page, forceDialog, forceTrigger);
  });

  test('API Key and Embedding repair dialogs share the design language', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/api-keys');

    const createKeyTrigger = page.getByRole('button', { name: 'Create Key' }).first();
    await createKeyTrigger.click();
    const createKeyDialog = await expectDialogDesign(page, 'Create Key');
    await expect(page.getByPlaceholder('e.g. Production Server')).toBeFocused();
    await closeDialogWithEscape(page, createKeyDialog, createKeyTrigger);

    await page.getByRole('link', { name: /Embeddings/ }).click();
    await page.getByLabel('Collection Key').fill('sample-collection');
    const repairTrigger = page.getByRole('button', { name: 'Preview repair' });
    await repairTrigger.click();
    const repairDialog = await expectDialogDesign(page, 'Repair preview');
    await expect(repairDialog.getByText('REBUILD_LOCAL_AND_QUEUE_VECTOR')).toBeVisible();
    await closeDialogWithEscape(page, repairDialog, repairTrigger);
  });
});
