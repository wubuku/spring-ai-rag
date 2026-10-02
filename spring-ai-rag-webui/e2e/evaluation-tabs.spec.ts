import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

test.describe('Evaluation tabs', () => {
  test('opens suites runs and citations tabs via URL', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/evaluation?tab=suites');
    await expect(page.getByRole('tab', { name: /Suites|套件/ })).toBeVisible();
    // Role-scoped on purpose. `getByLabel(/Suites|套件/)` matches two things:
    // the tabpanel (named through `aria-labelledby`) and the section inside it
    // (named through `aria-label`), so it violates strict mode. The tabpanel is
    // the element this line is actually about — "the selected tab rendered its
    // panel" — and scoping by role says that without naming either string.
    await expect(page.getByRole('tabpanel')).toBeVisible();

    await page.getByRole('tab', { name: /Runs|运行/ }).click();
    await expect(page).toHaveURL(/tab=runs/);
    await expect(page.getByRole('button', { name: /Start run|启动运行/ })).toBeVisible();

    await page.getByRole('tab', { name: /Citations|引用校验/ }).click();
    await expect(page).toHaveURL(/tab=citations/);
    await expect(page.getByRole('cell', { name: 'VALID', exact: true })).toBeVisible();
  });
});
