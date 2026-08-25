import { expect, test } from '@playwright/test';

const ROOT_API_KEY = process.env.RAG_ROOT_API_KEY ?? process.env.REAL_E2E_API_KEY;
const API_PREFIX = '/api/v1/rag';

test.describe.configure({ mode: 'serial' });
test.setTimeout(120_000);

test('manages a real stable principal without persisting shown-once credentials', async ({
  page,
  request,
}) => {
  if (!ROOT_API_KEY) {
    throw new Error('RAG_ROOT_API_KEY or REAL_E2E_API_KEY is required');
  }

  const runId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const principalName = `Real Principal ${runId}`;
  const consoleMessages: string[] = [];
  const requestUrls: string[] = [];
  let principalId: string | undefined;
  let currentCredentialId: string | undefined;
  let firstRawKey: string | undefined;
  let rotatedRawKey: string | undefined;

  page.on('console', message => consoleMessages.push(message.text()));
  page.on('request', outbound => requestUrls.push(outbound.url()));

  const rootHeaders = {
    'X-API-Key': ROOT_API_KEY,
    'Content-Type': 'application/json',
  };

  try {
    await page.goto('/webui/api-keys', { waitUntil: 'networkidle' });
    await expect(page).toHaveURL(/\/webui\/unlock$/);
    await page.getByTestId('root-api-key').fill(ROOT_API_KEY);
    await page.getByRole('button', { name: 'Unlock' }).click();
    await expect(page).toHaveURL(/\/webui\/api-keys$/);
    await expect(page.getByRole('heading', { name: 'API Keys' })).toBeVisible();

    await page.getByRole('button', { name: 'Create Key' }).first().click();
    await page.getByPlaceholder('e.g. Production Server').fill(principalName);
    await page.locator('#create-key-quota').fill('12');
    const createResponsePromise = page.waitForResponse(response =>
      response.url().endsWith(`${API_PREFIX}/api-keys`)
      && response.request().method() === 'POST',
    );
    await page.getByRole('button', { name: 'Create', exact: true }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    expect(createResponse.headers()['cache-control']).toContain('no-store');
    const created = await createResponse.json();
    principalId = created.principalId;
    currentCredentialId = created.keyId;
    firstRawKey = created.rawKey;
    expect(principalId).toBeTruthy();
    expect(currentCredentialId).toBeTruthy();
    expect(firstRawKey).toMatch(/^rag_sk_/);
    expect(created.credentialVersion).toBe(1);
    expect(created.requestsPerMinute).toBe(12);
    await expect(page.getByText(firstRawKey!, { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Close' }).click();
    await expect(page.getByText(firstRawKey!, { exact: true })).toHaveCount(0);

    const principalRow = page.getByText(principalName, { exact: true }).locator('..');
    await expect(principalRow.getByRole('code')).toHaveText(currentCredentialId!);
    await expect(principalRow.getByText('v1', { exact: true })).toBeVisible();
    await principalRow.getByRole('button', { name: 'Edit' }).click();
    await page.locator('#policy-name').fill(`${principalName} Updated`);
    await page.locator('#policy-quota').fill('18');
    const policyRequestPromise = page.waitForRequest(outbound =>
      outbound.url().includes(`/api-keys/principals/${principalId}/policy`)
      && outbound.method() === 'PUT',
    );
    const policyResponsePromise = page.waitForResponse(response =>
      response.url().includes(`/api-keys/principals/${principalId}/policy`)
      && response.request().method() === 'PUT',
    );
    await page.getByRole('button', { name: 'Save' }).click();
    const policyRequest = await policyRequestPromise;
    const policyResponse = await policyResponsePromise;
    expect(policyRequest.postDataJSON()).toMatchObject({
      expectedPolicyVersion: 1,
      name: `${principalName} Updated`,
      requestsPerMinute: 18,
    });
    expect(policyResponse.status()).toBe(200);
    expect((await policyResponse.json()).policyVersion).toBe(2);
    await expect(page.getByText(`${principalName} Updated`, { exact: true })).toBeVisible();

    const editedRow = page.getByText(`${principalName} Updated`, { exact: true }).locator('..');
    await editedRow.getByRole('button', { name: 'Rotate' }).click();
    const rotateResponsePromise = page.waitForResponse(response =>
      response.url().endsWith(`/api-keys/${currentCredentialId}/rotate`)
      && response.request().method() === 'POST',
    );
    await page.getByRole('button', { name: 'Rotate Key' }).click();
    const rotateResponse = await rotateResponsePromise;
    expect(rotateResponse.status()).toBe(201);
    expect(rotateResponse.headers()['cache-control']).toContain('no-store');
    const rotated = await rotateResponse.json();
    rotatedRawKey = rotated.rawKey;
    currentCredentialId = rotated.keyId;
    expect(rotated.principalId).toBe(principalId);
    expect(rotated.credentialVersion).toBe(2);
    expect(rotated.policyVersion).toBe(2);
    await expect(page.getByText(rotatedRawKey, { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Close' }).click();

    const oldIdentity = await request.get(`${API_PREFIX}/auth/me`, {
      headers: { 'X-API-Key': firstRawKey! },
    });
    expect(oldIdentity.status()).toBe(401);
    const newIdentity = await request.get(`${API_PREFIX}/auth/me`, {
      headers: { 'X-API-Key': rotatedRawKey },
    });
    expect(newIdentity.status()).toBe(200);
    expect(await newIdentity.json()).toMatchObject({
      principalId,
      credentialId: currentCredentialId,
      credentialVersion: 2,
      policyVersion: 2,
      principalRole: 'NORMAL',
      collectionAccessMode: 'UNRESTRICTED',
      allowedCollectionKeys: null,
    });

    const rotatedRow = page.getByText(`${principalName} Updated`, { exact: true }).locator('..');
    await expect(rotatedRow.getByRole('code')).toHaveText(currentCredentialId!);
    await expect(rotatedRow.getByText('v2', { exact: true })).toBeVisible();
    await rotatedRow.getByRole('button', { name: 'Revoke' }).click();
    await expect(rotatedRow.getByText('Revoked', { exact: true })).toBeVisible();
    const revokedIdentity = await request.get(`${API_PREFIX}/auth/me`, {
      headers: { 'X-API-Key': rotatedRawKey },
    });
    expect(revokedIdentity.status()).toBe(401);

    const browserStorage = await page.evaluate(() => JSON.stringify({
      local: { ...localStorage },
      session: { ...sessionStorage },
    }));
    for (const secret of [firstRawKey, rotatedRawKey]) {
      expect(requestUrls.join('\n')).not.toContain(secret);
      expect(consoleMessages.join('\n')).not.toContain(secret);
      expect(browserStorage).not.toContain(secret);
    }
  } finally {
    if (currentCredentialId) {
      await request.delete(`${API_PREFIX}/api-keys/${currentCredentialId}`, {
        headers: rootHeaders,
      }).catch(() => undefined);
    }
  }
});
