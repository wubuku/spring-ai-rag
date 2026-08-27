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
