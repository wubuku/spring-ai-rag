import { test, expect } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

/**
 * 响应式布局与慢/断网加载态。
 *
 * 三档宽度：375（手机）、768（平板）、1440（桌面）。断言：
 *  - 页面级不出现横向滚动条；
 *  - 宽表格由局部滚动容器承载，而不是把整页撑开；
 *  - 375px 下主要按钮触达区不小于 24x24（WCAG 2.5.8）。
 */
const WIDTHS = [375, 768, 1440] as const;

const PAGES: Array<{ name: string; path: string }> = [
  { name: 'dashboard', path: '/webui/dashboard' },
  { name: 'documents', path: '/webui/documents' },
  { name: 'collections', path: '/webui/collections' },
  { name: 'chat', path: '/webui/chat' },
  { name: 'search', path: '/webui/search' },
  { name: 'metrics', path: '/webui/metrics' },
  { name: 'evaluation', path: '/webui/evaluation' },
  { name: 'embeddings', path: '/webui/embeddings' },
  { name: 'alerts', path: '/webui/alerts' },
  { name: 'abtest', path: '/webui/abtest' },
  { name: 'api-keys', path: '/webui/api-keys' },
  { name: 'files', path: '/webui/files' },
  { name: 'settings', path: '/webui/settings' },
];

for (const width of WIDTHS) {
  test.describe(`responsive ${width}px`, () => {
    for (const target of PAGES) {
      test(`${target.name} does not overflow the page or trap table content`, async ({ page }) => {
        await page.setViewportSize({ width, height: 812 });
        await mockAllApiCalls(page);
        await openProtectedPage(page, target.path);
        await expect(page.getByText('Loading…', { exact: true })).toHaveCount(0);

        const layout = await page.evaluate(() => {
          const overflowingTables = Array.from(
            document.querySelectorAll('table'),
          ).filter(table => {
            const rect = table.getBoundingClientRect();
            const parent = table.parentElement;
            if (!parent) return false;
            const style = getComputedStyle(parent);
            return (
              rect.width > parent.clientWidth + 1 &&
              !['auto', 'scroll'].includes(style.overflowX)
            );
          }).length;

          return {
            scrollWidth: document.documentElement.scrollWidth,
            innerWidth: window.innerWidth,
            overflowingTables,
          };
        });

        expect(
          layout.scrollWidth,
          'page-level horizontal overflow',
        ).toBeLessThanOrEqual(layout.innerWidth + 1);
        expect(
          layout.overflowingTables,
          'table wider than its non-scrolling container',
        ).toBe(0);
      });
    }
  });
}

test('375px primary controls meet the 24px target size', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await mockAllApiCalls(page);
  await openProtectedPage(page, '/webui/dashboard');

  const tooSmall = await page.evaluate(() =>
    Array.from(document.querySelectorAll('button, select'))
      .filter(element => {
        const style = getComputedStyle(element);
        if (style.display === 'none' || style.visibility === 'hidden') return false;
        const rect = element.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0 && (rect.width < 24 || rect.height < 24);
      })
      .map(element => {
        const rect = element.getBoundingClientRect();
        return `${element.tagName.toLowerCase()} "${element.textContent?.trim().slice(0, 30) ?? ''}" ${Math.round(rect.width)}x${Math.round(rect.height)}`;
      }),
  );

  expect(tooSmall).toEqual([]);
});

test('documents loading state survives a slow network and resolves', async ({ page }) => {
  await mockAllApiCalls(page);
  let release!: () => void;
  const gate = new Promise<void>(resolve => {
    release = resolve;
  });
  await page.route(/\/api\/v1\/rag\/documents.*/, async route => {
    await gate;
    await route.fallback();
  });

  await openProtectedPage(page, '/webui/documents');
  const loading = page.getByTestId('documents-loading');
  await expect(loading).toBeVisible();

  release();
  await expect(loading).toHaveCount(0);
  await expect(page.locator('table')).toBeVisible();
});

test('offline documents request ends in an error with a retry action', async ({ page }) => {
  await mockAllApiCalls(page);
  await page.route(/\/api\/v1\/rag\/documents.*/, route => route.abort('internetdisconnected'));

  await openProtectedPage(page, '/webui/documents');

  // axios 对网络错误重试 3 次（1s/2s/3s），React Query 再重试 1 次，所以断网时
  // 失败态在 10s 量级出现，不是"立刻"但也不会永远挂着。这里断言它有界地结束，
  // 并且错误横幅里带着可操作的"重试"，而不是把用户留在一个永不结束的骨架屏。
  const alert = page
    .getByRole('alert')
    .filter({ hasText: /加载文档失败|Failed to load documents/i });
  await expect(alert).toBeVisible({ timeout: 20_000 });
  await expect(
    alert.getByRole('button', { name: /重试|Retry/i }),
  ).toBeVisible();
  await expect(page.getByTestId('documents-loading')).toHaveCount(0);
});
