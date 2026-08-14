import { test, expect } from '@playwright/test';
import {
  MOCK_BUSINESS_API_KEY,
  MOCK_ROOT_API_KEY,
  mockAllApiCalls,
} from './api-mocks';

interface MockKey {
  keyId: string;
  name: string;
  role: 'NORMAL';
  createdAt: string;
  expiresAt: string;
  enabled: boolean;
  allowedCollectionIds?: number[];
}

test('root unlock manages shown-once business keys without browser persistence', async ({ page }) => {
  const createdRawKey = `${MOCK_BUSINESS_API_KEY}_created`;
  const rotatedRawKey = `${MOCK_BUSINESS_API_KEY}_rotated`;
  const consoleMessages: string[] = [];
  const requestUrls: string[] = [];
  const managementCredentials: Array<string | undefined> = [];
  const keys: MockKey[] = [];

  page.on('console', message => consoleMessages.push(message.text()));
  page.on('request', request => requestUrls.push(request.url()));
  await page.addInitScript(() => {
    localStorage.setItem('rag-api-key', 'legacy-browser-secret');
    localStorage.setItem('rag-api-key-role', 'ADMIN');
  });
  await mockAllApiCalls(page);

  await page.route(/\/api\/v1\/rag\/api-keys(?:\/.*)?$/, async route => {
    const request = route.request();
    const credential = request.headers()['x-api-key'];
    managementCredentials.push(credential);
    if (credential !== MOCK_ROOT_API_KEY) {
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'FORBIDDEN' }),
      });
      return;
    }

    const url = new URL(request.url());
    const method = request.method();
    if (method === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(keys) });
      return;
    }

    if (method === 'POST' && url.pathname.endsWith('/rotate')) {
      const oldKeyId = url.pathname.split('/').at(-2)!;
      const oldKey = keys.find(key => key.keyId === oldKeyId);
      if (oldKey) oldKey.enabled = false;
      keys.push({
        keyId: 'rag_k_rotated',
        name: oldKey?.name ?? 'Service Key',
        role: 'NORMAL',
        createdAt: '2026-08-14T12:10:00',
        expiresAt: oldKey?.expiresAt ?? '2026-11-12T12:00:00',
        enabled: true,
      });
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          keyId: 'rag_k_rotated',
          rawKey: rotatedRawKey,
          name: oldKey?.name ?? 'Service Key',
          expiresAt: oldKey?.expiresAt ?? '2026-11-12T12:00:00',
          warning: 'Store this key securely. It will not be shown again.',
        }),
      });
      return;
    }

    if (method === 'POST') {
      const body = request.postDataJSON() as {
        name: string;
        expiresAt: string;
        allowedCollectionIds?: number[];
      };
      keys.push({
        keyId: 'rag_k_created',
        name: body.name,
        role: 'NORMAL',
        createdAt: '2026-08-14T12:00:00',
        expiresAt: body.expiresAt,
        enabled: true,
        allowedCollectionIds: body.allowedCollectionIds,
      });
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          keyId: 'rag_k_created',
          rawKey: createdRawKey,
          name: body.name,
          expiresAt: body.expiresAt,
          allowedCollectionIds: body.allowedCollectionIds,
          warning: 'Store this key securely. It will not be shown again.',
        }),
      });
      return;
    }

    if (method === 'DELETE') {
      const keyId = url.pathname.split('/').at(-1)!;
      const key = keys.find(candidate => candidate.keyId === keyId);
      if (key) key.enabled = false;
      await route.fulfill({ status: 204, body: '' });
      return;
    }

    await route.fulfill({ status: 405, body: '' });
  });

  await page.goto('/webui/api-keys', { waitUntil: 'networkidle' });
  await expect(page).toHaveURL(/\/webui\/unlock$/);

  await page.getByTestId('root-api-key').fill(MOCK_BUSINESS_API_KEY);
  await page.getByRole('button', { name: 'Unlock' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page).toHaveURL(/\/webui\/unlock$/);

  await page.getByTestId('root-api-key').fill(MOCK_ROOT_API_KEY);
  await page.getByRole('button', { name: 'Unlock' }).click();
  await expect(page).toHaveURL(/\/webui\/api-keys$/);
  await expect(page.getByRole('heading', { name: 'API Keys' })).toBeVisible();

  expect(await page.evaluate(() => ({
    legacyKey: localStorage.getItem('rag-api-key'),
    legacyRole: localStorage.getItem('rag-api-key-role'),
    sessionValues: Object.values(sessionStorage),
  }))).toEqual({
    legacyKey: null,
    legacyRole: null,
    sessionValues: [],
  });

  await page.getByRole('button', { name: 'Create Key' }).first().click();
  await page.getByPlaceholder('e.g. Production Server').fill('Indexer Service');
  const expiry = page.locator('input[type="datetime-local"]');
  await expect(expiry).not.toHaveValue('');
  await page.getByRole('button', { name: 'Create', exact: true }).click();

  await expect(page.getByText(createdRawKey)).toBeVisible();
  await page.getByRole('button', { name: 'Close' }).click();
  await expect(page.getByText(createdRawKey)).toHaveCount(0);
  await expect(page.getByText('rag_k_created')).toBeVisible();

  const createdRow = page.getByText('rag_k_created').locator('..');
  await createdRow.getByRole('button', { name: 'Rotate' }).click();
  await page.getByRole('button', { name: 'Rotate Key' }).click();
  await expect(page.getByText(rotatedRawKey)).toBeVisible();
  await page.getByRole('button', { name: 'Close' }).click();
  await expect(page.getByText(rotatedRawKey)).toHaveCount(0);

  const rotatedRow = page.getByText('rag_k_rotated').locator('..');
  await rotatedRow.getByRole('button', { name: 'Revoke' }).click();
  await expect(rotatedRow.getByText('Revoked')).toBeVisible();

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/webui\/unlock$/);

  expect(managementCredentials.length).toBeGreaterThan(0);
  expect(managementCredentials.every(value => value === MOCK_ROOT_API_KEY)).toBe(true);
  expect(requestUrls.every(url =>
    !url.includes(MOCK_ROOT_API_KEY)
    && !url.includes(MOCK_BUSINESS_API_KEY)
    && !url.includes(createdRawKey)
    && !url.includes(rotatedRawKey)
  )).toBe(true);
  expect(consoleMessages.join('\n')).not.toContain(MOCK_ROOT_API_KEY);
  expect(consoleMessages.join('\n')).not.toContain(createdRawKey);
  expect(await page.evaluate(() => JSON.stringify({
    local: { ...localStorage },
    session: { ...sessionStorage },
  }))).not.toContain('rag_sk_');
});
