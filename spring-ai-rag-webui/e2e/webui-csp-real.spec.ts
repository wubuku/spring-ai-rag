import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { rootHeaders } from './support/chat-lifecycle';

const REAL = process.env.REAL_WEBUI_E2E === '1';
const TIMEOUT = 120_000;
const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

test.setTimeout(TIMEOUT);

test('packaged WebUI has no CSP violations, console errors or serious axe findings', async ({
  page,
}) => {
  test.skip(!REAL, 'REAL_WEBUI_E2E=1 is required');

  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on('console', message => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.addInitScript(() => {
    const surface = window as unknown as { __cspViolations?: string[] };
    surface.__cspViolations = surface.__cspViolations ?? [];
    document.addEventListener('securitypolicyviolation', event => {
      surface.__cspViolations!.push(
        `${event.violatedDirective} ${event.blockedURI} ${event.sourceFile}:${event.lineNumber}`,
      );
    });
  });

  await page.goto('/webui/chat', { waitUntil: 'networkidle' });
  if (new URL(page.url()).pathname === '/webui/unlock') {
    await page.getByTestId('root-api-key').fill(rootHeaders()['X-API-Key']);
    await page.getByRole('button', { name: 'Unlock', exact: true }).click();
  }

  const themeToggle = page.getByTestId('theme-toggle');
  await expect(themeToggle).toBeVisible();
  for (const mode of ['Dark', 'Light', 'System']) {
    // 单选输入被图标覆盖，真实用户点的是包住它的 label；Playwright 直接
    // 点隐藏 input 会被图标拦截，这不是 UI 缺陷。
    await page.locator(`label[title="${mode}"]`).click();
    await expect(page.getByRole('radio', { name: mode, exact: true })).toBeChecked();
  }
  const theme = await page.evaluate(() => document.documentElement.dataset.theme);
  expect(theme, '主题切换必须落到 data-theme').toBeTruthy();

  for (const route of ['/webui/chat', '/webui/documents', '/webui/files', '/webui/settings']) {
    await page.goto(route, { waitUntil: 'networkidle' });
  }

  const cspViolations = await page.evaluate(() =>
    (window as unknown as { __cspViolations?: string[] }).__cspViolations ?? []);
  expect(cspViolations, `CSP 违规:\n${cspViolations.join('\n')}`).toEqual([]);

  const unexpectedConsole = consoleErrors.filter(message =>
    !/favicon|Failed to load resource: the server responded with a status of 404/i
      .test(message));
  expect(unexpectedConsole, `console error:\n${unexpectedConsole.join('\n')}`).toEqual([]);
  expect(pageErrors, `page error:\n${pageErrors.join('\n')}`).toEqual([]);

  for (const route of ['/webui/chat', '/webui/documents']) {
    await page.goto(route, { waitUntil: 'networkidle' });
    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
    const blocking = results.violations.filter(
      violation => violation.impact === 'critical' || violation.impact === 'serious',
    );
    expect(
      blocking,
      blocking.map(violation =>
        `${violation.id} (${violation.impact}): ${violation.help} → ${violation.nodes
          .slice(0, 3).map(node => node.target.join(' ')).join(' | ')}`).join('\n'),
    ).toEqual([]);
  }
});
