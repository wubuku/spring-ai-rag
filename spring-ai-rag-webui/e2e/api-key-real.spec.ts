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
  let stagedRawKey: string | undefined;
  let retiringCredentialId: string | undefined;
  let rotationId: string | undefined;

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
    await page.getByRole('radio', { name: 'RAG_READ', exact: true }).check();
    const createRequestPromise = page.waitForRequest(outbound =>
      outbound.url().endsWith(`${API_PREFIX}/api-keys`)
      && outbound.method() === 'POST',
    );
    const createResponsePromise = page.waitForResponse(response =>
      response.url().endsWith(`${API_PREFIX}/api-keys`)
      && response.request().method() === 'POST',
    );
    await page.getByRole('button', { name: 'Create', exact: true }).click();
    const createRequest = await createRequestPromise;
    const createResponse = await createResponsePromise;
    expect(createRequest.postDataJSON()).toMatchObject({
      name: principalName,
      requestsPerMinute: 12,
      capabilities: ['RAG_READ'],
    });
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
    expect(created.capabilities).toEqual(['RAG_READ']);
    await expect(page.getByText(firstRawKey!, { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Close' }).click();
    await expect(page.getByText(firstRawKey!, { exact: true })).toHaveCount(0);

    const principalRow = page.locator('[class*="tableRow"]').filter({
      hasText: principalName,
    });
    await expect(
      principalRow.locator('code').filter({ hasText: currentCredentialId! }),
    ).toBeVisible();
    await expect(principalRow.getByText('RAG_READ', { exact: true })).toBeVisible();
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
      capabilities: ['RAG_READ'],
    });
    expect(policyResponse.status()).toBe(200);
    expect((await policyResponse.json()).policyVersion).toBe(2);
    await expect(page.getByText(`${principalName} Updated`, { exact: true })).toBeVisible();

    const editedRow = page.locator('[class*="tableRow"]').filter({
      hasText: `${principalName} Updated`,
    });
    await editedRow.getByRole('button', { name: 'Rotate' }).click();
    await expect(page.getByRole('radio', {
      name: /Staged rotation/,
    })).toBeChecked();
    await page.locator('#rotation-overlap').fill('120');
    const prepareRequestPromise = page.waitForRequest(outbound =>
      outbound.url().endsWith(`/api-keys/${currentCredentialId}/rotations`)
      && outbound.method() === 'POST',
    );
    const prepareResponsePromise = page.waitForResponse(response =>
      response.url().endsWith(`/api-keys/${currentCredentialId}/rotations`)
      && response.request().method() === 'POST',
    );
    await page.getByRole('button', { name: 'Prepare rotation' }).click();
    const prepareRequest = await prepareRequestPromise;
    const prepareResponse = await prepareResponsePromise;
    expect(prepareRequest.headers()['idempotency-key']).toBeTruthy();
    expect(prepareRequest.postDataJSON()).toEqual({ overlapSeconds: 120 });
    expect(prepareResponse.status()).toBe(201);
    expect(prepareResponse.headers()['cache-control']).toContain('no-store');
    const prepared = await prepareResponse.json();
    stagedRawKey = prepared.rawKey;
    retiringCredentialId = prepared.retiringCredentialId;
    currentCredentialId = prepared.keyId;
    rotationId = prepared.rotationId;
    expect(prepared.principalId).toBe(principalId);
    expect(prepared.credentialVersion).toBe(2);
    expect(prepared.status).toBe('PENDING');
    expect(prepared.secretAvailable).toBe(true);
    expect(prepared.idempotentReplay).toBe(false);
    expect(retiringCredentialId).toBeTruthy();
    expect(rotationId).toBeTruthy();
    await expect(page.getByText(stagedRawKey, { exact: true })).toBeVisible();
    await expect(page.getByText('Overlap deadline')).toBeVisible();
    await page.getByRole('button', { name: 'Close' }).click();
    await expect(page.getByText(stagedRawKey, { exact: true })).toHaveCount(0);

    const oldIdentity = await request.get(`${API_PREFIX}/auth/me`, {
      headers: { 'X-API-Key': firstRawKey! },
    });
    expect(oldIdentity.status()).toBe(200);
    const newIdentity = await request.get(`${API_PREFIX}/auth/me`, {
      headers: { 'X-API-Key': stagedRawKey },
    });
    expect(newIdentity.status()).toBe(200);
    expect(await newIdentity.json()).toMatchObject({
      principalId,
      credentialId: currentCredentialId,
      credentialVersion: 2,
      policyVersion: 2,
      principalRole: 'NORMAL',
      capabilities: ['RAG_READ'],
      collectionAccessMode: 'UNRESTRICTED',
      allowedCollectionKeys: null,
    });

    const pendingRow = page.locator('[class*="tableRow"]').filter({
      hasText: `${principalName} Updated`,
    });
    await expect(
      pendingRow.locator('code').filter({ hasText: currentCredentialId! }),
    ).toBeVisible();
    await expect(
      pendingRow.locator('code').filter({ hasText: retiringCredentialId! }),
    ).toBeVisible();
    await expect(pendingRow.getByText('Rotation pending', {
      exact: true,
    })).toBeVisible();
    await expect(pendingRow.getByRole('button', {
      name: 'Rotate',
    })).toBeDisabled();

    const completeResponsePromise = page.waitForResponse(response =>
      response.url().endsWith(`/api-keys/rotations/${rotationId}/complete`)
      && response.request().method() === 'POST',
    );
    await pendingRow.getByRole('button', { name: 'Complete' }).click();
    const completeResponse = await completeResponsePromise;
    expect(completeResponse.status()).toBe(200);
    expect(completeResponse.headers()['cache-control']).toContain('no-store');
    expect(await completeResponse.json()).toMatchObject({
      rotationId,
      status: 'COMPLETED',
      principalId,
      keyId: currentCredentialId,
      rotationPending: false,
      rawKey: null,
    });
    await expect(pendingRow.getByText('Rotation pending', {
      exact: true,
    })).toHaveCount(0);

    const retiredIdentity = await request.get(`${API_PREFIX}/auth/me`, {
      headers: { 'X-API-Key': firstRawKey! },
    });
    expect(retiredIdentity.status()).toBe(401);
    const completedIdentity = await request.get(`${API_PREFIX}/auth/me`, {
      headers: { 'X-API-Key': stagedRawKey },
    });
    expect(completedIdentity.status()).toBe(200);

    const completedRow = page.locator('[class*="tableRow"]').filter({
      hasText: `${principalName} Updated`,
    });
    await completedRow.getByRole('button', { name: 'Revoke' }).click();
    await expect(completedRow.getByText('Revoked', {
      exact: true,
    })).toBeVisible();
    const revokedIdentity = await request.get(`${API_PREFIX}/auth/me`, {
      headers: { 'X-API-Key': stagedRawKey },
    });
    expect(revokedIdentity.status()).toBe(401);

    const browserStorage = await page.evaluate(() => JSON.stringify({
      local: { ...localStorage },
      session: { ...sessionStorage },
    }));
    for (const secret of [firstRawKey, stagedRawKey]) {
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
