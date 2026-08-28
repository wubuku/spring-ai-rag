import { expect, test } from '@playwright/test';
import {
  MOCK_ROOT_API_KEY,
  mockAllApiCalls,
  openProtectedPage,
} from './api-mocks';

test.describe('Managed API principal expiry alerts', () => {
  test('renders the server-owned phase and low-sensitivity principal projection', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.unroute(/\/api\/v1\/rag\/alerts.*/);
    await page.route('/api/v1/rag/alerts/active', async route => {
      expect(route.request().headers()['x-api-key']).toBe(MOCK_ROOT_API_KEY);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            id: 57,
            alertType: 'API_PRINCIPAL_EXPIRY',
            alertName: 'Managed API principal expiry',
            message:
              'Managed API principal principal-customer-api is in CRITICAL phase',
            severity: 'CRITICAL',
            status: 'ACTIVE',
            conditionState: 'CRITICAL',
            metrics: {
              principalId: 'principal-customer-api',
              principalRole: 'NORMAL',
              expiresAt: '2026-09-01T12:30:00+08:00[Asia/Shanghai]',
              timeZone: 'Asia/Shanghai',
              phase: 'CRITICAL',
              secondsRemaining: 3600,
              policyVersion: 3,
            },
            firedAt: '2026-08-27T04:00:00Z',
          },
          {
            id: 58,
            alertType: 'SLO_BREACH',
            alertName: 'Latency objective',
            message: 'Latency objective is not met',
            severity: 'WARNING',
            status: 'ACTIVE',
            metrics: {},
            firedAt: 'not-a-time',
          },
        ]),
      });
    });

    const responsePromise = page.waitForResponse(
      response =>
        response.url().endsWith('/api/v1/rag/alerts/active')
        && response.request().method() === 'GET',
    );
    await openProtectedPage(page, '/webui/alerts');
    const response = await responsePromise;
    expect(response.status()).toBe(200);
    const payload = await response.json();
    expect(payload[0]).toMatchObject({
      alertType: 'API_PRINCIPAL_EXPIRY',
      conditionState: 'CRITICAL',
      firedAt: '2026-08-27T04:00:00Z',
      metrics: {
        principalId: 'principal-customer-api',
        expiresAt: '2026-09-01T12:30:00+08:00[Asia/Shanghai]',
      },
    });
    expect(payload[0]).not.toHaveProperty('triggeredAt');

    const expiryAlert = page.getByRole('article', {
      name: 'Managed API principal expiry CRITICAL',
    });
    await expect(expiryAlert).toBeVisible();
    await expect(expiryAlert).toContainText('Type: API_PRINCIPAL_EXPIRY');
    await expect(expiryAlert).toContainText('Phase: CRITICAL');
    await expect(expiryAlert).toContainText(
      'Principal: principal-customer-api',
    );
    await expect(expiryAlert).toContainText(
      'Expires At: 2026-09-01T12:30:00+08:00[Asia/Shanghai]',
    );
    await expect(expiryAlert).toContainText('Triggered At:');
    await expect(expiryAlert).not.toContainText('Invalid Date');

    const invalidTimeAlert = page.getByRole('article', {
      name: 'Latency objective WARNING',
    });
    await expect(invalidTimeAlert).toContainText(
      'Triggered At: Unavailable',
    );
    await expect(page.getByText('Confidential customer administration'))
      .toHaveCount(0);
  });
});

test.describe('Durable alert notification delivery receipts', () => {
  test('filters low-sensitivity receipts and retries failed delivery', async ({ page }) => {
    await mockAllApiCalls(page);
    await page.unroute(/\/api\/v1\/rag\/alerts.*/);
    const deliveryId = '8abf1f68-7ed4-4fca-b33e-2c9cb22c8d87';
    await page.route(
      /\/api\/v1\/rag\/alerts\/notification-deliveries.*/,
      async route => {
        const request = route.request();
        expect(request.headers()['x-api-key']).toBe(MOCK_ROOT_API_KEY);
        if (request.method() === 'POST') {
          expect(request.url()).toContain(`/${deliveryId}/retry`);
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              id: deliveryId,
              alertId: 42,
              notificationVersion: 1,
              provider: 'DINGTALK',
              status: 'PENDING',
              attemptCount: 8,
              attemptBudget: 16,
              manualRetryCount: 1,
              nextAttemptAt: '2026-08-28T08:02:00Z',
              createdAt: '2026-08-28T08:00:00Z',
              updatedAt: '2026-08-28T08:02:00Z',
            }),
          });
          return;
        }
        const url = new URL(request.url());
        expect(url.searchParams.get('status')).toBe('FAILED');
        expect(url.searchParams.get('provider')).toBe('DINGTALK');
        expect(url.searchParams.get('limit')).toBe('50');
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            notificationsEnabled: true,
            durableDeliveryEnabled: true,
            configuredProviders: ['DINGTALK'],
            items: [{
              id: deliveryId,
              alertId: 42,
              notificationVersion: 1,
              provider: 'DINGTALK',
              status: 'FAILED',
              attemptCount: 8,
              attemptBudget: 8,
              manualRetryCount: 0,
              nextAttemptAt: '2026-08-28T08:01:00Z',
              lastErrorCode: 'TRANSIENT_PROVIDER_5XX',
              lastHttpStatus: 503,
              lastAttemptAt: '2026-08-28T08:00:30Z',
              createdAt: '2026-08-28T08:00:00Z',
              updatedAt: '2026-08-28T08:01:00Z',
            }],
            limit: 50,
            hasMore: false,
            nextCursor: null,
          }),
        });
      },
    );

    const responsePromise = page.waitForResponse(response =>
      response.url().includes('/notification-deliveries?')
      && response.request().method() === 'GET',
    );
    await openProtectedPage(
      page,
      '/webui/alerts?tab=notification-deliveries&status=FAILED&provider=DINGTALK',
    );
    const response = await responsePromise;
    const json = await response.json();
    expect(json.items[0]).not.toHaveProperty('payload');
    expect(json.items[0]).not.toHaveProperty('leaseToken');
    expect(json.items[0]).not.toHaveProperty('endpoint');

    await expect(page.getByRole('table')).toBeVisible();
    const row = page.getByRole('row', { name: 'DINGTALK FAILED 42' });
    await expect(row).toContainText('TRANSIENT_PROVIDER_5XX');
    await expect(page.getByText(/secret|access_token|webhook/i)).toHaveCount(0);

    const retryResponse = page.waitForResponse(candidate =>
      candidate.url().endsWith(`/${deliveryId}/retry`)
      && candidate.request().method() === 'POST',
    );
    await row.getByRole('button', {
      name: `Retry delivery ${deliveryId}`,
    }).click();
    expect((await retryResponse).status()).toBe(200);
  });

  for (const fixture of [
    {
      name: 'disabled',
      notificationsEnabled: false,
      durableDeliveryEnabled: false,
      configuredProviders: [],
      message: 'Alert notifications are disabled.',
    },
    {
      name: 'direct',
      notificationsEnabled: true,
      durableDeliveryEnabled: false,
      configuredProviders: ['DINGTALK'],
      message:
        'Notifications use compatibility direct delivery; durable receipts are not being created.',
    },
    {
      name: 'no provider',
      notificationsEnabled: true,
      durableDeliveryEnabled: true,
      configuredProviders: [],
      message: 'Durable delivery is enabled, but no provider is configured.',
    },
  ]) {
    test(`explains ${fixture.name} notification mode`, async ({ page }) => {
      await mockAllApiCalls(page);
      await page.unroute(/\/api\/v1\/rag\/alerts.*/);
      await page.route(
        /\/api\/v1\/rag\/alerts\/notification-deliveries.*/,
        route => route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            notificationsEnabled: fixture.notificationsEnabled,
            durableDeliveryEnabled: fixture.durableDeliveryEnabled,
            configuredProviders: fixture.configuredProviders,
            items: [],
            limit: 50,
            hasMore: false,
            nextCursor: null,
          }),
        }),
      );

      await openProtectedPage(
        page,
        '/webui/alerts?tab=notification-deliveries',
      );
      await expect(page.getByRole('status')).toHaveText(fixture.message);
      await expect(page.getByText('No notification delivery receipts'))
        .toBeVisible();
    });
  }
});
