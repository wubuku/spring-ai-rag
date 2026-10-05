import { expect, test } from '@playwright/test';

const ROOT_API_KEY = process.env.RAG_ROOT_API_KEY;
const EXPECTED_ALERT_ID = process.env.ALERT_DELIVERY_EXPECTED_ALERT_ID;
const EXPECTED_DELIVERY_ID = process.env.ALERT_DELIVERY_EXPECTED_DELIVERY_ID;
const EXPECTED_STATUS = process.env.ALERT_DELIVERY_EXPECTED_STATUS ?? 'DELIVERED';
const API_PATH = '/api/v1/rag/alerts/notification-deliveries';

test.describe.configure({ mode: 'serial' });
test.setTimeout(60_000);

test('renders a real durable notification receipt from the backend contract', async ({
  page,
}) => {
  if (!ROOT_API_KEY || !EXPECTED_ALERT_ID || !EXPECTED_DELIVERY_ID) {
    throw new Error(
      'RAG_ROOT_API_KEY, ALERT_DELIVERY_EXPECTED_ALERT_ID and '
      + 'ALERT_DELIVERY_EXPECTED_DELIVERY_ID are required',
    );
  }

  const responsePromise = page.waitForResponse(response =>
    response.url().includes(API_PATH)
    && response.request().method() === 'GET',
  );
  await page.goto('/webui/alerts?tab=notification-deliveries', {
    waitUntil: 'networkidle',
  });
  await expect(page).toHaveURL(/\/webui\/unlock$/);
  await page.getByTestId('root-api-key').fill(ROOT_API_KEY);
  await page.getByRole('button', { name: 'Unlock' }).click();
  await expect(page).toHaveURL(
    /\/webui\/alerts\?tab=notification-deliveries$/,
  );

  const response = await responsePromise;
  expect(response.status()).toBe(200);
  const envelope = await response.json();
  expect(envelope).toMatchObject({
    notificationsEnabled: true,
    durableDeliveryEnabled: true,
    configuredProviders: ['DINGTALK'],
  });
  const receipt = envelope.items.find(
    (item: { id: string }) => item.id === EXPECTED_DELIVERY_ID,
  );
  expect(receipt).toMatchObject({
    id: EXPECTED_DELIVERY_ID,
    alertId: Number(EXPECTED_ALERT_ID),
    provider: 'DINGTALK',
    status: EXPECTED_STATUS,
  });
  expect(receipt).not.toHaveProperty('payload');
  expect(receipt).not.toHaveProperty('leaseToken');
  expect(receipt).not.toHaveProperty('leaseUntil');

  const row = page.getByRole('row', {
    name: `DINGTALK ${EXPECTED_STATUS} ${EXPECTED_ALERT_ID}`,
  });
  await expect(row).toBeVisible();
  // Batch 923. This used to be
  //   row.getByRole('cell').filter({ hasText: EXPECTED_ALERT_ID })
  // and `hasText` matches on **substring**, so on 2026-10-06 it resolved to three
  // cells instead of one: the alert id "1", and the two timestamps rendered
  // that minute — `10/6/2026, 2:29:14 AM` and `…2:29:18 AM` — both of which
  // contain the digit. Playwright then refused the whole locator in strict mode
  // and the run failed.
  //
  // It passed on 2026-08-28 because that day's stamps read `8/28/2026`, which
  // happens to contain no "1". **The assertion was never wrong about the
  // product — it was wrong about `hasText`, and the calendar decided which.**
  // An exact cell name is what "this cell holds the alert id" actually means,
  // and it cannot be satisfied by a timestamp.
  await expect(
    row.getByRole('cell', { name: EXPECTED_ALERT_ID, exact: true }),
  ).toBeVisible();
  await expect(row.getByText('DINGTALK', { exact: true })).toBeVisible();
  await expect(row.getByText(EXPECTED_STATUS, { exact: true })).toBeVisible();

  const visibleText = await page.locator('body').innerText();
  expect(visibleText).not.toContain('webhook-url');
  expect(visibleText).not.toContain('leaseToken');
  expect(visibleText).not.toContain('payload');
});
