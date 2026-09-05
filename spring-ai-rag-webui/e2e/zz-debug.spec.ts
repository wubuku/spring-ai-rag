import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

test('debug chat mode round trip', async ({ page }) => {
  await mockAllApiCalls(page);
  await openProtectedPage(page, '/webui/chat');

  await page.getByTestId('chat-mode-select').selectOption('AGENT');
  await expect(page).toHaveURL(/mode=AGENT/);
  await page.locator('textarea').fill('draft');
  await page.getByRole('link', { name: /Search/ }).click();
  await expect(page).toHaveURL(/\/webui\/search/);

  const hrefBefore = await page.getByRole('link', { name: /Chat/ }).first().getAttribute('href');
  const routes = await page.evaluate(() =>
    sessionStorage.getItem('spring-ai-rag:webui:v1:routes'));
  console.log('HREF_BEFORE:', hrefBefore);
  console.log('ROUTES:', routes);

  await page.getByRole('link', { name: /Chat/ }).first().click();
  console.log('URL_AFTER:', page.url());
  const hrefAfter = await page.getByRole('link', { name: /Chat/ }).first().getAttribute('href');
  console.log('HREF_AFTER:', hrefAfter);
  const routesAfter = await page.evaluate(() =>
    sessionStorage.getItem('spring-ai-rag:webui:v1:routes'));
  console.log('ROUTES_AFTER:', routesAfter);
  expect(page.url()).toContain('mode=AGENT');
});
