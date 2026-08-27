import { test, expect } from '@playwright/test';
import {
  MOCK_BUSINESS_API_KEY,
  MOCK_ROOT_API_KEY,
  mockAllApiCalls,
} from './api-mocks';

interface MockPrincipal {
  principalId: string;
  name: string;
  role: 'NORMAL';
  policyVersion: number;
  status: 'ACTIVE' | 'REVOKED';
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  currentCredentialId?: string;
  currentCredentialVersion?: number;
  rotationPending?: boolean;
  pendingRotationId?: string;
  retiringCredentialId?: string;
  retiringCredentialVersion?: number;
  rotationExpiresAt?: string;
  requestsPerMinute?: number;
  capabilities: string[];
  allowedCollectionKeys?: string[];
}

test('root unlock manages shown-once business keys without browser persistence', async ({ page }) => {
  const createdRawKey = `${MOCK_BUSINESS_API_KEY}_created`;
  const stagedRawKey = `${MOCK_BUSINESS_API_KEY}_staged`;
  const lostResponseRawKey = `${MOCK_BUSINESS_API_KEY}_lost_response`;
  const immediateRawKey = `${MOCK_BUSINESS_API_KEY}_immediate`;
  const consoleMessages: string[] = [];
  const requestUrls: string[] = [];
  const managementCredentials: Array<string | undefined> = [];
  const prepareIdempotencyKeys: Array<string | undefined> = [];
  const principals: MockPrincipal[] = [];
  const createRequests: Array<{
    name: string;
    expiresAt: string;
    capabilities: string[];
    allowedCollectionKeys?: string[];
    requestsPerMinute?: number;
  }> = [];
  const policyUpdates: Array<Record<string, unknown>> = [];
  let rotationSequence = 0;
  let simulateLostPrepareResponse = false;
  let pendingRotation: {
    rotationId: string;
    principalId: string;
    sourceCredentialId: string;
    sourceCredentialVersion: number;
    targetCredentialId: string;
    targetCredentialVersion: number;
    rawKey: string;
    expiresAt: string;
    idempotencyKey: string;
  } | undefined;

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
    if (method === 'GET' && url.pathname.endsWith('/principals')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(principals),
      });
      return;
    }

    if (method === 'POST' && url.pathname.endsWith('/rotations')) {
      const idempotencyKey = request.headers()['idempotency-key'];
      prepareIdempotencyKeys.push(idempotencyKey);
      const currentKeyId = url.pathname.split('/').at(-2)!;
      if (!idempotencyKey) {
        await route.fulfill({
          status: 400,
          headers: { 'Cache-Control': 'no-store' },
          contentType: 'application/json',
          body: JSON.stringify({ error: 'INVALID_ROTATION' }),
        });
        return;
      }
      if (pendingRotation) {
        if (pendingRotation.idempotencyKey !== idempotencyKey) {
          await route.fulfill({
            status: 409,
            headers: { 'Cache-Control': 'no-store' },
            contentType: 'application/json',
            body: JSON.stringify({ error: 'ROTATION_PENDING' }),
          });
          return;
        }
        await route.fulfill({
          status: 200,
          headers: {
            'Cache-Control': 'no-store',
            'X-RAG-Idempotent-Replay': 'true',
          },
          contentType: 'application/json',
          body: JSON.stringify({
            rotationId: pendingRotation.rotationId,
            status: 'PENDING',
            principalId: pendingRotation.principalId,
            keyId: pendingRotation.targetCredentialId,
            credentialVersion: pendingRotation.targetCredentialVersion,
            rawKey: null,
            secretAvailable: false,
            idempotentReplay: true,
            currentCredentialActive: true,
            rotationPending: true,
            retiringCredentialId: pendingRotation.sourceCredentialId,
            retiringCredentialVersion: pendingRotation.sourceCredentialVersion,
            rotationExpiresAt: pendingRotation.expiresAt,
          }),
        });
        return;
      }
      const principal = principals.find(
        item => item.currentCredentialId === currentKeyId,
      );
      if (!principal) {
        await route.fulfill({
          status: 404,
          headers: { 'Cache-Control': 'no-store' },
          contentType: 'application/json',
          body: JSON.stringify({ error: 'CREDENTIAL_NOT_FOUND' }),
        });
        return;
      }

      rotationSequence += 1;
      const sourceCredentialId = principal.currentCredentialId!;
      const sourceCredentialVersion = principal.currentCredentialVersion!;
      const targetCredentialVersion = sourceCredentialVersion + 1;
      const targetCredentialId = `rag_k_staged_v${targetCredentialVersion}`;
      const rawKey = simulateLostPrepareResponse
        ? lostResponseRawKey
        : stagedRawKey;
      pendingRotation = {
        rotationId: `11111111-1111-4111-8111-${String(rotationSequence).padStart(12, '0')}`,
        principalId: principal.principalId,
        sourceCredentialId,
        sourceCredentialVersion,
        targetCredentialId,
        targetCredentialVersion,
        rawKey,
        expiresAt: '2099-12-31T23:59:59',
        idempotencyKey,
      };
      principal.currentCredentialId = targetCredentialId;
      principal.currentCredentialVersion = targetCredentialVersion;
      principal.rotationPending = true;
      principal.pendingRotationId = pendingRotation.rotationId;
      principal.retiringCredentialId = sourceCredentialId;
      principal.retiringCredentialVersion = sourceCredentialVersion;
      principal.rotationExpiresAt = pendingRotation.expiresAt;

      if (simulateLostPrepareResponse) {
        simulateLostPrepareResponse = false;
        await route.fulfill({
          status: 503,
          headers: { 'Cache-Control': 'no-store' },
          contentType: 'application/json',
          body: JSON.stringify({ error: 'RESPONSE_LOST_AFTER_COMMIT' }),
        });
        return;
      }

      await route.fulfill({
        status: 201,
        headers: { 'Cache-Control': 'no-store' },
        contentType: 'application/json',
        body: JSON.stringify({
          rotationId: pendingRotation.rotationId,
          status: 'PENDING',
          principalId: pendingRotation.principalId,
          keyId: pendingRotation.targetCredentialId,
          credentialVersion: pendingRotation.targetCredentialVersion,
          rawKey: pendingRotation.rawKey,
          secretAvailable: true,
          idempotentReplay: false,
          currentCredentialActive: true,
          rotationPending: true,
          retiringCredentialId: pendingRotation.sourceCredentialId,
          retiringCredentialVersion: pendingRotation.sourceCredentialVersion,
          rotationExpiresAt: pendingRotation.expiresAt,
        }),
      });
      return;
    }

    if (method === 'POST' && url.pathname.endsWith('/complete')) {
      const rotationId = url.pathname.split('/').at(-2)!;
      if (!pendingRotation || pendingRotation.rotationId !== rotationId) {
        await route.fulfill({ status: 404, body: '' });
        return;
      }
      const completed = pendingRotation;
      const principal = principals.find(
        item => item.principalId === completed.principalId,
      )!;
      principal.rotationPending = false;
      principal.pendingRotationId = undefined;
      principal.retiringCredentialId = undefined;
      principal.retiringCredentialVersion = undefined;
      principal.rotationExpiresAt = undefined;
      pendingRotation = undefined;
      await route.fulfill({
        status: 200,
        headers: { 'Cache-Control': 'no-store' },
        contentType: 'application/json',
        body: JSON.stringify({
          rotationId,
          status: 'COMPLETED',
          principalId: completed.principalId,
          keyId: completed.targetCredentialId,
          credentialVersion: completed.targetCredentialVersion,
          rawKey: null,
          secretAvailable: false,
          idempotentReplay: false,
          currentCredentialActive: true,
          rotationPending: false,
        }),
      });
      return;
    }

    if (method === 'POST' && url.pathname.endsWith('/cancel')) {
      const rotationId = url.pathname.split('/').at(-2)!;
      if (!pendingRotation || pendingRotation.rotationId !== rotationId) {
        await route.fulfill({ status: 404, body: '' });
        return;
      }
      const canceled = pendingRotation;
      const principal = principals.find(
        item => item.principalId === canceled.principalId,
      )!;
      principal.currentCredentialId = canceled.sourceCredentialId;
      principal.currentCredentialVersion = canceled.sourceCredentialVersion;
      principal.rotationPending = false;
      principal.pendingRotationId = undefined;
      principal.retiringCredentialId = undefined;
      principal.retiringCredentialVersion = undefined;
      principal.rotationExpiresAt = undefined;
      pendingRotation = undefined;
      await route.fulfill({
        status: 200,
        headers: { 'Cache-Control': 'no-store' },
        contentType: 'application/json',
        body: JSON.stringify({
          rotationId,
          status: 'CANCELED',
          principalId: canceled.principalId,
          keyId: canceled.sourceCredentialId,
          credentialVersion: canceled.sourceCredentialVersion,
          rawKey: null,
          secretAvailable: false,
          idempotentReplay: false,
          currentCredentialActive: true,
          rotationPending: false,
        }),
      });
      return;
    }

    if (method === 'POST' && url.pathname.endsWith('/rotate')) {
      const oldKeyId = url.pathname.split('/').at(-2)!;
      const principal = principals.find(item => item.currentCredentialId === oldKeyId);
      if (principal) {
        principal.currentCredentialId = 'rag_k_immediate';
        principal.currentCredentialVersion = (principal.currentCredentialVersion ?? 1) + 1;
        principal.updatedAt = '2026-08-14T12:10:00';
      }
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          keyId: 'rag_k_immediate',
          principalId: principal?.principalId ?? 'rag_p_created',
          credentialVersion: principal?.currentCredentialVersion ?? 2,
          policyVersion: principal?.policyVersion ?? 1,
          rawKey: immediateRawKey,
          name: principal?.name ?? 'Service Key',
          expiresAt: principal?.expiresAt ?? '2026-11-12T12:00:00',
          requestsPerMinute: principal?.requestsPerMinute,
          capabilities: principal?.capabilities ?? ['RAG_READ', 'RAG_WRITE'],
          warning: 'Store this key securely. It will not be shown again.',
        }),
      });
      return;
    }

    if (method === 'PUT' && url.pathname.endsWith('/policy')) {
      const principalId = url.pathname.split('/').at(-2)!;
      const body = request.postDataJSON() as Record<string, unknown>;
      policyUpdates.push(body);
      const principal = principals.find(item => item.principalId === principalId)!;
      principal.name = body.name as string;
      principal.expiresAt = body.expiresAt as string;
      principal.capabilities = body.capabilities as string[];
      principal.allowedCollectionKeys = body.allowedCollectionKeys as string[] | undefined;
      principal.requestsPerMinute = body.requestsPerMinute as number | undefined;
      principal.policyVersion += 1;
      principal.updatedAt = '2026-08-14T12:20:00';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(principal),
      });
      return;
    }

    if (method === 'POST' && url.pathname.endsWith('/api-keys')) {
      const body = request.postDataJSON() as {
        name: string;
        expiresAt: string;
        allowedCollectionKeys?: string[];
        requestsPerMinute?: number;
        capabilities: string[];
      };
      createRequests.push(body);
      principals.push({
        principalId: 'rag_p_created',
        name: body.name,
        role: 'NORMAL',
        policyVersion: 1,
        status: 'ACTIVE',
        createdAt: '2026-08-14T12:00:00',
        updatedAt: '2026-08-14T12:00:00',
        expiresAt: body.expiresAt,
        currentCredentialId: 'rag_k_created',
        currentCredentialVersion: 1,
        requestsPerMinute: body.requestsPerMinute,
        capabilities: body.capabilities,
        allowedCollectionKeys: body.allowedCollectionKeys,
      });
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          keyId: 'rag_k_created',
          principalId: 'rag_p_created',
          credentialVersion: 1,
          policyVersion: 1,
          rawKey: createdRawKey,
          name: body.name,
          expiresAt: body.expiresAt,
          allowedCollectionKeys: body.allowedCollectionKeys,
          requestsPerMinute: body.requestsPerMinute,
          capabilities: body.capabilities,
          warning: 'Store this key securely. It will not be shown again.',
        }),
      });
      return;
    }

    if (method === 'DELETE') {
      const keyId = url.pathname.split('/').at(-1)!;
      const principal = principals.find(candidate => candidate.currentCredentialId === keyId);
      if (principal) {
        principal.status = 'REVOKED';
        principal.currentCredentialId = undefined;
        principal.currentCredentialVersion = undefined;
      }
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
  await expect(expiry).not.toHaveAttribute('max');
  const longExpiry = '2099-12-31T23:59';
  await expiry.fill('2027-12-31T23:59');
  await expiry.click({ position: { x: 80, y: 20 } });
  await expiry.pressSequentially('2099', { delay: 250 });
  await expect(expiry).toHaveValue(longExpiry);
  await page.getByText('Selected collections', { exact: true }).click();
  await page.getByRole('checkbox', { name: /Sample Collection/ }).check();
  await page.locator('#create-key-quota').fill('75');
  await page.getByRole('radio', { name: 'RAG_READ', exact: true }).check();
  await page.getByRole('button', { name: 'Create', exact: true }).click();

  await expect(page.getByText(createdRawKey)).toBeVisible();
  expect(principals[0]?.expiresAt).toBe(`${longExpiry}:00`);
  expect(principals[0]?.allowedCollectionKeys).toEqual(['sample-collection']);
  expect(principals[0]?.requestsPerMinute).toBe(75);
  expect(principals[0]?.capabilities).toEqual(['RAG_READ']);
  expect(createRequests).toHaveLength(1);
  expect(createRequests[0]?.capabilities).toEqual(['RAG_READ']);
  expect(createRequests[0]?.allowedCollectionKeys).toEqual(['sample-collection']);
  expect(createRequests[0]).not.toHaveProperty('allowedCollectionIds');
  await expect(
    page.locator('[class*="_rawKeyBox_"]').getByText('sample-collection', { exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Close' }).click();
  await expect(page.getByText(createdRawKey)).toHaveCount(0);
  await expect(page.getByText('rag_p_created')).toBeVisible();
  await expect(page.getByText('rag_k_created')).toBeVisible();

  const principalRow = page.getByText('rag_p_created').locator('..');
  await principalRow.getByRole('button', { name: 'Edit' }).click();
  await page.locator('#policy-name').fill('Indexer Agent');
  await page.locator('#policy-quota').fill('120');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Indexer Agent')).toBeVisible();
  expect(policyUpdates).toEqual([
    expect.objectContaining({
      expectedPolicyVersion: 1,
      name: 'Indexer Agent',
      requestsPerMinute: 120,
      capabilities: ['RAG_READ'],
      allowedCollectionKeys: ['sample-collection'],
    }),
  ]);

  const editedRow = page.getByText('rag_p_created').locator('..');
  await editedRow.getByRole('button', { name: 'Rotate' }).click();
  await expect(page.getByRole('radio', {
    name: /Staged rotation/,
  })).toBeChecked();
  await page.locator('#rotation-overlap').fill('120');
  await page.getByRole('button', { name: 'Prepare rotation' }).click();
  await expect(page.getByText(stagedRawKey)).toBeVisible();
  await expect(page.getByText('Overlap deadline')).toBeVisible();
  await page.getByRole('button', { name: 'Close' }).click();
  await expect(page.getByText(stagedRawKey)).toHaveCount(0);

  let rotatedRow = page.getByText('rag_p_created').locator('..');
  await expect(rotatedRow.getByText('rag_k_staged_v2')).toBeVisible();
  await expect(rotatedRow.getByText('rag_k_created')).toBeVisible();
  await expect(rotatedRow.getByText('Rotation pending')).toBeVisible();
  await expect(rotatedRow.getByRole('button', { name: 'Rotate' })).toBeDisabled();
  await rotatedRow.getByRole('button', { name: 'Complete' }).click();
  await expect(rotatedRow.getByText('Rotation pending')).toHaveCount(0);
  await expect(rotatedRow.getByText('rag_k_staged_v2')).toBeVisible();

  simulateLostPrepareResponse = true;
  await rotatedRow.getByRole('button', { name: 'Rotate' }).click();
  const retryStartIndex = prepareIdempotencyKeys.length;
  await page.getByRole('button', { name: 'Prepare rotation' }).click();
  await expect(page.getByText(/idempotent replay/i)).toBeVisible();
  await expect(page.getByText(lostResponseRawKey)).toHaveCount(0);
  expect(prepareIdempotencyKeys.slice(retryStartIndex)).toHaveLength(2);
  expect(prepareIdempotencyKeys[retryStartIndex]).toBe(
    prepareIdempotencyKeys[retryStartIndex + 1],
  );
  await page.getByRole('button', { name: 'Close' }).click();

  rotatedRow = page.getByText('rag_p_created').locator('..');
  await expect(rotatedRow.getByText('rag_k_staged_v3')).toBeVisible();
  await expect(rotatedRow.getByText('rag_k_staged_v2')).toBeVisible();
  await rotatedRow.getByRole('button', { name: 'Cancel rotation' }).click();
  await expect(rotatedRow.getByText('rag_k_staged_v3')).toHaveCount(0);
  await expect(rotatedRow.getByText('rag_k_staged_v2')).toBeVisible();

  await rotatedRow.getByRole('button', { name: 'Rotate' }).click();
  await page.getByRole('radio', { name: /Immediate rotation/ }).check();
  await page.getByRole('button', { name: 'Rotate immediately' }).click();
  await expect(page.getByText(immediateRawKey)).toBeVisible();
  await page.getByRole('button', { name: 'Close' }).click();
  await expect(page.getByText(immediateRawKey)).toHaveCount(0);

  rotatedRow = page.getByText('rag_p_created').locator('..');
  await expect(rotatedRow.getByText('rag_k_immediate')).toBeVisible();
  await expect(rotatedRow.getByText('v3')).toBeVisible();
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
    && !url.includes(stagedRawKey)
    && !url.includes(lostResponseRawKey)
    && !url.includes(immediateRawKey)
  )).toBe(true);
  expect(consoleMessages.join('\n')).not.toContain(MOCK_ROOT_API_KEY);
  expect(consoleMessages.join('\n')).not.toContain(createdRawKey);
  expect(consoleMessages.join('\n')).not.toContain(stagedRawKey);
  expect(consoleMessages.join('\n')).not.toContain(lostResponseRawKey);
  expect(consoleMessages.join('\n')).not.toContain(immediateRawKey);
  expect(await page.evaluate(() => JSON.stringify({
    local: { ...localStorage },
    session: { ...sessionStorage },
  }))).not.toContain('rag_sk_');
});
