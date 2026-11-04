import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

function racyDocuments(offset: number) {
  return Array.from({ length: 20 }, (_, index) => {
    const id = offset + index + 1;
    return {
      id,
      title: `Race doc ${id}`,
      content: `content ${id}`,
      source: 'webui',
      contentHash: `hash-${id}`,
      documentType: 'TEXT',
      metadata: {},
      createdAt: '2026-08-19T01:00:00Z',
      updatedAt: '2026-08-19T01:00:00Z',
      collectionId: 1,
      collectionKey: 'sample-collection',
      collectionName: 'Sample Collection',
      chunkCount: 1,
      processingStatus: 'COMPLETED',
      embeddingFresh: true,
      enabled: true,
    };
  });
}

async function mainScroll(page: Page): Promise<number> {
  return page.getByRole('main').evaluate(element => element.scrollTop);
}

test.describe('WebUI resilience', () => {
  test('an unknown route renders a 404 page and keeps navigation usable', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/unknown-route-xyz');

    await expect(page).toHaveURL(/\/webui\/unknown-route-xyz$/);
    await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
    await expect(page.locator('aside')).toBeVisible();

    // 导航必须仍然可用，用户能从 404 自己走出去。
    await page.getByRole('link', { name: 'Dashboard', exact: true }).click();
    await expect(page).toHaveURL(/\/webui\/dashboard$/);
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  });

  test('a page chunk failure shows a recoverable error and the next page still renders', async ({ page }) => {
    await mockAllApiCalls(page);
    await openProtectedPage(page, '/webui/dashboard');
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();

    // 模拟部署后旧 chunk 失效：只让 Files 的懒加载分块失败。
    await page.route(/\/assets\/Files-[^/]+\.js$/, route => route.abort());
    await page.getByRole('link', { name: 'Files', exact: true }).click();

    await expect(page.getByText('Something went wrong')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
    // 左侧导航在错误界面之外，因此不会跟着白屏。
    await expect(page.locator('aside')).toBeVisible();

    // 切到已经加载过的页面：错误状态必须被清掉，而不是继续挡住新页面。
    await page.getByRole('link', { name: 'Dashboard', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await expect(page.getByText('Something went wrong')).toHaveCount(0);
  });

  test('back restores the scroll position of the page being returned to', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.unroute(/\/api\/v1\/rag\/api-keys(?:\/.*)?$/);
    await page.route('/api/v1/rag/api-keys/principals', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(Array.from({ length: 80 }, (_, index) => ({
          principalId: `rag_p_scroll_${index}`,
          name: `Scroll principal ${index}`,
          role: 'NORMAL',
          capabilities: ['RAG_READ'],
          collectionAccessMode: 'UNRESTRICTED',
          policyVersion: 1,
          status: 'ACTIVE',
          expiresAt: '2099-12-31T23:59:59Z',
          createdAt: '2026-08-28T00:00:00Z',
          updatedAt: '2026-08-28T00:00:00Z',
        }))),
      });
    });

    await openProtectedPage(page, '/webui/api-keys');
    await expect(page.getByText('Scroll principal 79')).toBeVisible();

    await page.getByRole('main').evaluate(element => {
      element.scrollTop = element.scrollHeight;
    });
    const scrolled = await mainScroll(page);
    expect(scrolled).toBeGreaterThan(0);

    // 普通跳转仍然回到顶部。
    await page.getByRole('link', { name: 'Files', exact: true }).click();
    await expect(page).toHaveURL(/\/webui\/files/);
    await expect.poll(() => mainScroll(page)).toBe(0);

    // 浏览器后退回到上一页时恢复离开时的位置。
    await page.goBack();
    await expect(page).toHaveURL(/\/webui\/api-keys/);
    await expect(page.getByText('Scroll principal 79')).toBeVisible();
    await expect.poll(() => mainScroll(page)).toBeGreaterThan(0);

    // 前进到 Files 仍然从顶部开始。
    await page.goForward();
    await expect(page).toHaveURL(/\/webui\/files/);
    await expect.poll(() => mainScroll(page)).toBe(0);
  });

  test('a late response from a page the user already left cannot overwrite the current page', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.route(/\/api\/v1\/rag\/documents(?:\?.*)?$/, async route => {
      const url = new URL(route.request().url());
      const offset = Number(url.searchParams.get('offset') ?? 0);
      // 第二页故意慢：用户在它返回之前已经切走了。
      await new Promise(resolve => setTimeout(resolve, offset === 20 ? 900 : 0));
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          documents: racyDocuments(offset),
          total: 60,
          offset,
          limit: 20,
        }),
      }).catch(() => {
        // 请求被取消时 fulfill 会失败，这正是竞态用例想确认的行为。
      });
    });

    await openProtectedPage(page, '/webui/documents');
    await expect(page.getByText('Race doc 1', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: 'Next' }).click();
    // 不等第二页返回就切到检索页。
    await page.getByRole('link', { name: 'Search', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Search' })).toBeVisible();

    await page.waitForTimeout(1200);

    await expect(page.getByRole('heading', { name: 'Search' })).toBeVisible();
    await expect(page.getByText('Race doc 21', { exact: true })).toHaveCount(0);
  });

  test('leaving the page aborts an in-flight collection export', async ({ page }) => {
    await mockAllApiCalls(page);
    // 不在 Playwright 的 requestfailed 上断言：page.route 拦截后的请求被 abort 时，
    // Chromium 可能把它记成"路由已 fulfill"，事件不出现，而 Firefox 会出现——
    // 那是测试夹具差异，不是产品行为。改为在页面内监听传给 fetch 的 AbortSignal，
    // 直接验证组件卸载时确实中断了导出请求。
    await page.addInitScript(() => {
      type ProbeWindow = Window & {
        __exportAborted: boolean;
        __exportFetchStarted: boolean;
        showSaveFilePicker?: unknown;
      };
      const probe = window as unknown as ProbeWindow;
      probe.__exportAborted = false;
      probe.__exportFetchStarted = false;
      // Chromium 有 File System Access API；无头模式下真实选择器没有 UI，会一直挂起，
      // 导出请求根本发不出去。这里换成内存 writer，让请求照常发出。
      probe.showSaveFilePicker = async () => ({
        createWritable: async () => ({
          write: async () => {},
          close: async () => {},
          abort: async () => {},
        }),
      });
      const originalFetch = window.fetch;
      window.fetch = function patchedFetch(input: RequestInfo | URL, init?: RequestInit) {
        const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
        if (url.includes('/export?')) {
          probe.__exportFetchStarted = true;
          init?.signal?.addEventListener('abort', () => {
            probe.__exportAborted = true;
          });
        }
        return originalFetch.call(window, input, init);
      };
    });
    await page.route(/\/api\/v1\/rag\/collections\/by-key\/export/, async route => {
      await new Promise(resolve => setTimeout(resolve, 800));
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: '{"documents":[]}',
      }).catch(() => {
        // 离开页面后请求被 AbortController 取消，fulfill 失败是预期结果。
      });
    });

    await openProtectedPage(page, '/webui/collections');
    await page.getByRole('button', { name: 'Export', exact: true }).first().click();
    await expect.poll(
      () => page.evaluate(() => (window as unknown as { __exportFetchStarted: boolean }).__exportFetchStarted),
    ).toBe(true);
    await page.getByRole('link', { name: 'Files', exact: true }).click();
    await expect(page).toHaveURL(/\/webui\/files/);

    await expect.poll(
      () => page.evaluate(() => (window as unknown as { __exportAborted: boolean }).__exportAborted),
    ).toBe(true);
  });
});
