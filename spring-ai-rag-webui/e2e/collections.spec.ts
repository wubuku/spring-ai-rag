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

async function createCollection(
  page: import('@playwright/test').Page,
  collectionKey: string,
  name: string,
) {
  await page.getByRole('button', { name: /Create Collection/ }).click();
  await page.getByRole('textbox', { name: /Collection key/ })
    .fill(collectionKey);
  await page.getByRole('textbox', { name: /^Name/ }).fill(name);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
}
