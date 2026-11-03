import { expect, test } from '@playwright/test';
import { mockAllApiCalls, openProtectedPage } from './api-mocks';

test.describe('Collection creation idempotency', () => {
  test('reuses the key across Axios retry and rotates it for a later submit',
    async ({ page }) => {
      await mockAllApiCalls(page);
      const attempts: Array<{
        key: string | undefined;
        body: unknown;
      }> = [];
      let postCount = 0;

      await page.route(/\/api\/v1\/rag\/collections$/, async route => {
        if (route.request().method() !== 'POST') {
          await route.fallback();
          return;
        }
        postCount += 1;
        attempts.push({
          key: route.request().headers()['idempotency-key'],
          body: route.request().postDataJSON(),
        });
        if (postCount === 1) {
          await route.fulfill({
            status: 503,
            contentType: 'application/json',
            body: JSON.stringify({
              errorCode: 'SERVICE_UNAVAILABLE',
              message: 'Injected retryable failure',
            }),
          });
          return;
        }
        const body = route.request().postDataJSON() as {
          collectionKey: string;
          name: string;
        };
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            id: postCount,
            collectionKey: body.collectionKey,
            name: body.name,
            embeddingModel: 'bge-m3',
            dimensions: 1024,
            enabled: true,
            metadata: {},
            documentCount: 0,
          }),
        });
      });

      await openProtectedPage(page, '/webui/collections');
      await createCollection(
        page, 'retry-safe-collection', 'Retry Safe Collection');
      await expect(page.getByText('Collection created successfully'))
        .toBeVisible();
      await expect.poll(() => attempts.length).toBe(2);

      expect(attempts[0].key).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
      );
      expect(attempts[1].key).toBe(attempts[0].key);
      expect(attempts[1].body).toEqual(attempts[0].body);

      await createCollection(
        page, 'later-submit-collection', 'Later Submit Collection');
      await expect.poll(() => attempts.length).toBe(3);
      expect(attempts[2].key).not.toBe(attempts[0].key);
    });
});

test.describe('Collection purge', () => {
  test('previews and applies the frozen plan while retaining the result',
    async ({ page }) => {
      await mockAllApiCalls(page);
      let collectionActive = true;
      let applyBody: Record<string, unknown> | null = null;
      let listRequests = 0;

      await page.route(/\/api\/v1\/rag\/collections(?:\?.*)?$/, async route => {
        if (route.request().method() !== 'GET') {
          await route.fallback();
          return;
        }
        listRequests += 1;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            collections: collectionActive ? [{
              id: 1,
              collectionKey: 'sample-collection',
              name: 'Sample Collection',
              embeddingModel: 'bge-m3',
              dimensions: 1024,
              documentCount: 5,
            }] : [],
            total: collectionActive ? 1 : 0,
            offset: 0,
            limit: 20,
          }),
        });
      });
      await page.route(
        '/api/v1/rag/collections/by-key/purge',
        async route => {
          applyBody = route.request().postDataJSON();
          collectionActive = false;
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              previewId: '33333333-3333-4333-8333-333333333333',
              status: 'RETIRED',
              collectionId: 1,
              collectionKey: 'sample-collection',
              purgedDocumentCount: 5,
              purgedExternalDocumentCount: 2,
              purgedLocalDocumentCount: 3,
              deletedAt: '2026-08-27T12:01:00',
              purgedAt: '2026-08-27T12:01:00',
              collectionVersion: 8,
            }),
          });
        },
      );

      await openProtectedPage(page, '/webui/collections');
      await page.getByRole('button', { name: 'Permanently purge' }).click();

      const dialog = page.getByRole('dialog', {
        name: 'Permanently purge and retire Collection',
      });
      await expect(dialog).toBeVisible();
      await expect(dialog.getByText('Documents', { exact: true })).toBeVisible();
      await expect(dialog.getByText('5', { exact: true })).toBeVisible();
      await expect(dialog.getByText('mock-confirmation-token')).toHaveCount(0);

      const confirmButton = dialog.getByRole('button', {
        name: 'Purge and retire',
      });
      await expect(confirmButton).toBeDisabled();
      await dialog.getByRole('textbox', {
        name: 'Type sample-collection to confirm',
      }).fill('sample-collection');
      await expect(confirmButton).toBeEnabled();
      await confirmButton.click();

      await expect(dialog.getByText('Collection retired', { exact: true }))
        .toBeVisible();
      await expect(dialog.getByText(
        'sample-collection was retired and 5 document(s) were permanently deleted.',
      )).toBeVisible();
      await expect(page.getByText('Sample Collection')).toHaveCount(0);
      await expect.poll(() => listRequests).toBeGreaterThan(1);
      expect(applyBody).toEqual({
        collectionKey: 'sample-collection',
        previewId: '33333333-3333-4333-8333-333333333333',
        confirmationToken: 'mock-confirmation-token',
        fingerprint: 'mock-purge-fingerprint',
        expectedCollectionVersion: 7,
        expectedChatCommitFenceVersion: 12,
      });
    });

  test('shows an apply conflict and does not resubmit automatically',
    async ({ page }) => {
      await mockAllApiCalls(page);
      let applyCalls = 0;
      await page.route(
        '/api/v1/rag/collections/by-key/purge',
        async route => {
          applyCalls += 1;
          await route.fulfill({
            status: 409,
            contentType: 'application/problem+json',
            body: JSON.stringify({
              type: 'about:blank',
              title: 'Conflict',
              status: 409,
              detail: 'Collection purge plan changed; create a new preview',
              error: 'COLLECTION_PURGE_CONFLICT',
            }),
          });
        },
      );

      await openProtectedPage(page, '/webui/collections');
      const targetCard = page.locator('div').filter({
        has: page.getByText('sample-collection', { exact: true }),
      }).filter({
        has: page.getByRole('button', { name: 'Permanently purge' }),
      }).last();
      await targetCard.getByRole('button', { name: 'Permanently purge' }).click();
      const dialog = page.getByRole('dialog', {
        name: 'Permanently purge and retire Collection',
      });
      await dialog.getByRole('textbox', {
        name: 'Type sample-collection to confirm',
      }).fill('sample-collection');
      await dialog.getByRole('button', { name: 'Purge and retire' }).click();

      await expect(dialog.getByText(
        'Collection purge plan changed; create a new preview',
      )).toBeVisible();
      await expect(dialog.getByRole('button', { name: 'Purge and retire' }))
        .toBeEnabled();
      expect(applyCalls).toBe(1);
    });
});

test.describe('Collection export streaming', () => {
  test('reports growing progress while a large export is still arriving',
    async ({ page }) => {
      await mockAllApiCalls(page);
      await openProtectedPage(page, '/webui/collections');
      await installStreamingExport(page, {
        chunks: 64,
        chunkSize: 128 * 1024,
        delayMs: 50,
      });

      let downloads = 0;
      page.on('download', () => {
        downloads += 1;
      });

      const downloadPromise = page.waitForEvent('download');
      await page.getByRole('button', { name: 'Export' }).first().click();

      const status = page.getByRole('status');
      await expect(status).toBeVisible();
      const firstProgress = await status.textContent();
      // 关键断言：流还没走完，字节数已经在变。一次性缓冲的实现这里会停在 0。
      await expect.poll(() => status.textContent(), { timeout: 2000 })
        .not.toBe(firstProgress);
      expect(firstProgress).toMatch(/\d/);
      expect(downloads).toBe(0);

      const download = await downloadPromise;
      expect(download.suggestedFilename()).toBe('sample-collection.json');
      await expect(status).toHaveCount(0);
    });

  test('cancels an in-flight export without producing a partial download',
    async ({ page }) => {
      await mockAllApiCalls(page);
      await openProtectedPage(page, '/webui/collections');
      await installStreamingExport(page, {
        chunks: 80,
        chunkSize: 128 * 1024,
        delayMs: 50,
      });

      let downloads = 0;
      page.on('download', () => {
        downloads += 1;
      });

      await page.getByRole('button', { name: 'Export' }).first().click();
      const status = page.getByRole('status');
      await expect(status).toBeVisible();
      await expect.poll(() => status.textContent(), { timeout: 2000 })
        .toMatch(/\d/);

      await page.getByRole('button', { name: 'Cancel export' }).click();

      await expect(status).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'Export', exact: true }).first())
        .toBeEnabled();
      // 取消之后不能有半截文件落盘。
      await page.waitForTimeout(300);
      expect(downloads).toBe(0);
    });
});

/**
 * 用一段可控的 ReadableStream 替代导出端点的 fetch。
 *
 * Playwright 的 `route.fulfill` 只能一次性给完整 body，无法让分块按时间到达，
 * 也就无法区分"边收边写"和"整体缓冲"。这里直接替身 fetch，把字节按
 * `delayMs` 的节奏推进；页面侧完全走真实的下载实现。
 */
async function installStreamingExport(
  page: import('@playwright/test').Page,
  options: { chunks: number; chunkSize: number; delayMs: number },
) {
  await page.evaluate(({ chunks, chunkSize, delayMs }) => {
    Object.defineProperty(window, 'showSaveFilePicker', {
      configurable: true,
      value: undefined,
    });
    const originalFetch = window.fetch.bind(window);
    window.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string'
        ? input
        : input instanceof Request ? input.url : input.toString();
      if (!url.includes('/collections/by-key/export')) {
        return originalFetch(input, init);
      }
      const signal = init?.signal;
      const stream = new ReadableStream<Uint8Array>({
        async start(controller) {
          try {
            for (let index = 0; index < chunks; index += 1) {
              if (signal?.aborted) {
                throw new DOMException('Aborted', 'AbortError');
              }
              await new Promise(resolve => setTimeout(resolve, delayMs));
              const payload = new Uint8Array(chunkSize);
              payload.fill(32);
              controller.enqueue(payload);
            }
            controller.close();
          } catch (error) {
            controller.error(error);
          }
        },
      });
      return new Response(stream, {
        status: 200,
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': String(chunks * chunkSize),
        },
      });
    }) as typeof fetch;
  }, options);
}

async function createCollection(
  page: import('@playwright/test').Page,
  collectionKey: string,
  name: string,
) {
  await page.getByRole('button', { name: /Create Collection/ }).click();
  await page.getByRole('textbox', { name: /Collection key/ })
    .fill(collectionKey);
  await page.getByRole('textbox', { name: /^Name/ }).fill(name);
  await page.getByRole('dialog', { name: 'Create Collection' })
    .getByRole('button', { name: 'Create Collection', exact: true })
    .click();
}
