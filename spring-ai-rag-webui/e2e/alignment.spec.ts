import { test, expect, type Locator } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

async function captureVisualEvidence(
  locator: Locator,
  testInfo: { outputPath: (name: string) => string },
  name: string,
) {
  const screenshot = await locator.screenshot({
    path: testInfo.outputPath(name),
    animations: 'disabled',
  });
  expect(screenshot.byteLength).toBeGreaterThan(1000);
}

test.describe('WebUI alignment policy', () => {
  test('uses start alignment for ordinary dashboard content', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/dashboard');

    await expect(page.locator('#root')).toHaveCSS('text-align', 'start');
    await expect(page.locator('html')).toHaveCSS('font-size', '18px');
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toHaveCSS('text-align', 'start');
    await expect(page.locator('main [class*="card"]').first()).toHaveCSS('text-align', 'start');
    await captureVisualEvidence(page.locator('main'), testInfo, 'dashboard-alignment-desktop.png');
  });

  test('keeps settings and API key management content start-aligned', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/settings');

    await expect(page.getByRole('heading', { name: 'Settings' })).toHaveCSS('text-align', 'start');
    await expect(page.locator('main label').first()).toHaveCSS('text-align', 'start');
    await expect(page.locator('main [class*="sectionDesc"]').first()).toHaveCSS('text-align', 'start');

    await page.getByRole('link', { name: /API Keys/ }).click();
    await expect(page).toHaveURL(/\/webui\/api-keys$/);
    await expect(page.getByRole('heading', { name: 'API Keys' })).toHaveCSS('text-align', 'start');
    await expect(page.locator('main [class*="empty"]').first()).toHaveCSS('text-align', 'start');
    await captureVisualEvidence(page.locator('main'), testInfo, 'api-keys-alignment-desktop.png');
  });

  test('keeps the document upload zone centered without centering the table', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');

    await expect(page.getByRole('heading', { name: 'Documents' })).toHaveCSS('text-align', 'start');
    await expect(page.locator('main [class*="uploadZone"]')).toHaveCSS('text-align', 'center');
    await expect(page.locator('main table tbody td').first()).toHaveCSS('text-align', 'start');
    await captureVisualEvidence(page.locator('main'), testInfo, 'documents-alignment-desktop.png');
  });

  test('keeps file upload and preview placeholders centered while tree content starts at the edge', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/files');

    await expect(page.getByRole('heading', { name: 'Files' })).toHaveCSS('text-align', 'start');
    await expect(page.locator('main [class*="uploadArea"]')).toHaveCSS('text-align', 'center');
    await expect(page.locator('main [class*="treeHeader_"]')).toHaveCSS('text-align', 'start');
    await expect(page.locator('main [class*="previewEmpty_"]')).toHaveCSS('text-align', 'center');
    await captureVisualEvidence(page.locator('main'), testInfo, 'files-alignment-desktop.png');
  });

  test('keeps the alignment baseline on a narrow viewport', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/documents');

    await expect(page.locator('#root')).toHaveCSS('text-align', 'start');
    await expect(page.getByRole('heading', { name: 'Documents' })).toHaveCSS('text-align', 'start');
    await expect(page.locator('main [class*="uploadZone"]')).toHaveCSS('text-align', 'center');
    await expect(page.locator('html')).toHaveCSS('font-size', '16px');
    const menuBox = await page.getByRole('button', { name: 'Open sidebar' }).boundingBox();
    const titleBox = await page.getByRole('heading', { name: 'Documents' }).boundingBox();
    expect(menuBox).not.toBeNull();
    expect(titleBox).not.toBeNull();
    expect(titleBox!.y).toBeGreaterThanOrEqual(menuBox!.y + menuBox!.height);
    await captureVisualEvidence(page.locator('main'), testInfo, 'documents-alignment-mobile.png');
  });
});
