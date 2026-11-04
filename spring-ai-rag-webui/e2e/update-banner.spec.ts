import { expect, test, type Page } from '@playwright/test';
import { mockAllApiCalls } from './api-mocks';
import { openEnglishPage, useEnglishUi } from './support/english-ui';

/**
 * Stale-tab update banner, exercised as the real browser sees it.
 *
 * The build under test carries a `webui-build-version` meta tag. A deployment is
 * simulated by intercepting only the version-check request (its query parameter
 * is what separates it from the document navigation) and answering with a
 * different tag. `page.clock` fast-forwards the five-minute poll instead of
 * waiting for it.
 */

const POLL_CHECK = /\/webui\/?\?webui-version-check=/u;
const POLL_INTERVAL_MS = 5 * 60 * 1000;

function versionHtml(version: string): string {
  return `<!doctype html><html><head><meta name="webui-build-version" content="${version}"></head><body></body></html>`;
}

async function runningVersion(page: Page): Promise<string> {
  const value = await page
    .locator('meta[name="webui-build-version"]')
    .getAttribute('content');
  expect(value, '构建产物必须带 webui-build-version').toBeTruthy();
  return value as string;
}

test.describe('stale tab update banner', () => {
  test('a newer deployment offers a manual refresh that loads the new build', async ({ page }) => {
    await mockAllApiCalls(page);
    await useEnglishUi(page);
    await page.clock.install();

    const checks: string[] = [];
    page.on('request', request => {
      if (POLL_CHECK.test(request.url())) checks.push(request.url());
    });

    await openEnglishPage(page, '/webui/dashboard');
    // 首屏不发版本请求：答案在加载那一刻必然是“同一个版本”。
    expect(checks).toEqual([]);
    const deployed = await runningVersion(page);

    await page.route(POLL_CHECK, route => route.fulfill({
      status: 200,
      contentType: 'text/html; charset=utf-8',
      body: versionHtml('next-build'),
    }));

    await page.clock.fastForward(POLL_INTERVAL_MS);

    const banner = page.getByRole('status');
    await expect(banner).toContainText('A new version is available. Refresh when you are ready.');
    // Never an automatic reload: the page is still on the build it loaded.
    await expect(page).toHaveURL(/\/webui\/dashboard$/u);
    await expect(
      page.locator('meta[name="webui-build-version"]'),
    ).toHaveAttribute('content', deployed);

    await page.getByRole('button', { name: 'Refresh now' }).click();
    await page.waitForURL(/\/webui\/(?:unlock|dashboard)$/u);
    // The reload reads the served index, not the intercepted check response.
    await expect(
      page.locator('meta[name="webui-build-version"]'),
    ).toHaveAttribute('content', deployed);
    await expect(page.getByRole('status')).toHaveCount(0);
    expect(await page.evaluate(
      () => document.querySelector('#root')?.children.length ?? 0,
    )).toBeGreaterThan(0);
  });

  test('an unchanged deployment shows no banner', async ({ page }) => {
    await mockAllApiCalls(page);
    await useEnglishUi(page);
    await page.clock.install();

    await openEnglishPage(page, '/webui/dashboard');
    const deployed = await runningVersion(page);

    await page.route(POLL_CHECK, route => route.fulfill({
      status: 200,
      contentType: 'text/html; charset=utf-8',
      body: versionHtml(deployed),
    }));

    await page.clock.fastForward(POLL_INTERVAL_MS * 2);
    await expect(page.getByRole('status')).toHaveCount(0);
    await expect(page.getByText('A new version is available. Refresh when you are ready.'))
      .toHaveCount(0);
  });

  test('an offline or 404 check is silent rather than an error', async ({ page }) => {
    await mockAllApiCalls(page);
    await useEnglishUi(page);
    await page.clock.install();

    await openEnglishPage(page, '/webui/dashboard');
    await page.route(POLL_CHECK, route => route.abort());

    await page.clock.fastForward(POLL_INTERVAL_MS);
    await expect(page.getByRole('status')).toHaveCount(0);
    await expect(page.getByText('Something went wrong')).toHaveCount(0);
    await expect(page.getByText('An unexpected error occurred')).toHaveCount(0);
  });
});
