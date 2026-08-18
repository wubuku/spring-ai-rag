import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

test.describe('Evaluation tabs', () => {
  test('opens suites runs and citations tabs via URL', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/evaluation?tab=suites');
    await expect(page.getByRole('tab', { name: /Suites|套件/ })).toBeVisible();
    await expect(page.getByLabel(/Suites|套件/)).toBeVisible();

    await page.getByRole('tab', { name: /Runs|运行/ }).click();
    await expect(page).toHaveURL(/tab=runs/);
    await expect(page.getByRole('button', { name: /Start run|启动运行/ })).toBeVisible();

    await page.getByRole('tab', { name: /Citations|引用校验/ }).click();
    await expect(page).toHaveURL(/tab=citations/);
    await expect(page.getByRole('cell', { name: 'VALID', exact: true })).toBeVisible();
  });
});
