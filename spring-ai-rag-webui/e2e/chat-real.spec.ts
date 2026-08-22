import { test, expect, type APIRequestContext, type APIResponse } from '@playwright/test';

const ROOT_API_KEY = process.env.RAG_ROOT_API_KEY ?? process.env.REAL_E2E_API_KEY;
const API_PREFIX = '/api/v1/rag';
const REQUEST_TIMEOUT = 180_000;

type ProbeData = {
  token: string;
  collectionId: number;
  collectionKey: string;
  collectionName: string;
  documentId: number;
  documentRevision: number;
  documentTitle: string;
};

test.describe.configure({ mode: 'serial' });
test.setTimeout(REQUEST_TIMEOUT);

test('uses the real WebUI proxy for bounded Agent SSE and history recovery', async ({
  page,
  request,
}) => {
  if (!ROOT_API_KEY) {
    throw new Error(
      'RAG_ROOT_API_KEY or REAL_E2E_API_KEY is required for chat-real.spec.ts',
    );
  }

  const headers = {
    'X-API-Key': ROOT_API_KEY,
    'Content-Type': 'application/json',
  };
  let probe: ProbeData | undefined;
  let sessionId: string | undefined;

  const apiJson = async (
    method: 'GET' | 'POST' | 'DELETE',
    path: string,
    data?: Record<string, unknown>,
  ): Promise<any> => {
    const response = await request.fetch(`${API_PREFIX}${path}`, {
      method,
      headers,
      data,
      timeout: REQUEST_TIMEOUT,
    });
    await expectApiSuccess(response, `${method} ${path}`);
    return response.status() === 204 ? undefined : response.json();
  };

  try {
    probe = await createProbe(request, headers);

    const modelList = await apiJson('GET', '/models');
    const agentModel = modelList.models?.find(
      (model: any) => model.available && model.capabilities?.toolCalling === true,
    );
    if (!agentModel?.ref) {
      throw new Error(
        'No available tool-calling model is exposed by /models; real Agent browser acceptance cannot run',
      );
    }

    await page.goto('/webui/chat', { waitUntil: 'networkidle' });
    await expect(page).toHaveURL(/\/webui\/unlock$/);
    await page.getByTestId('root-api-key').fill(ROOT_API_KEY);
    await page.getByRole('button', { name: 'Unlock' }).click();
    await expect(page).not.toHaveURL(/\/webui\/unlock$/);
    await expect(page.getByRole('heading', { name: 'Chat' })).toBeVisible();

    await expect(page.getByTestId('chat-model-select')).toBeVisible();
    await page.getByTestId('chat-model-select').selectOption(agentModel.ref);
    await page.getByTestId('chat-mode-select').selectOption('AGENT');
    await expect(page.getByTestId('chat-mode-select')).toHaveValue('AGENT');

    await page.getByTestId('chat-scope-SELECTED_COLLECTIONS').check();
    const collectionOption = page.locator(
      `input[type="checkbox"][value="${probe.collectionKey}"]`,
    );
    await expect(collectionOption).toBeVisible({ timeout: REQUEST_TIMEOUT });
    await collectionOption.check();

    const message =
      `Use searchKnowledge on the selected collection and return the release verification code ` +
      `exactly. The code is ${probe.token}.`;
    const streamRequestPromise = page.waitForRequest(
      request => request.url().includes(`${API_PREFIX}/chat/stream`),
    );
    const streamResponsePromise = page.waitForResponse(
      response =>
        response.url().includes(`${API_PREFIX}/chat/stream`) && response.status() === 200,
    );
    await page.locator('textarea').fill(message);
    await page.getByRole('button', { name: 'Send' }).click();

    const streamRequest = await streamRequestPromise;
    const requestBody = streamRequest.postDataJSON();
    expect(requestBody).toMatchObject({
      message,
      mode: 'AGENT',
      model: agentModel.ref,
      collectionScopeMode: 'SELECTED_COLLECTIONS',
      collectionKeys: [probe.collectionKey],
    });
    await streamResponsePromise;

    await expect(
      page.getByLabel(/Retrieval activity|检索活动/),
    ).toBeVisible({ timeout: REQUEST_TIMEOUT });
    await expect(page.getByText(probe.token, { exact: false }).last()).toBeVisible({
      timeout: REQUEST_TIMEOUT,
    });
    await expect(page.getByText(probe.documentTitle, { exact: true })).toBeVisible({
      timeout: REQUEST_TIMEOUT,
    });
    await expect(page).toHaveURL(/\/webui\/chat\/[^/]+$/);

    sessionId = decodeURIComponent(new URL(page.url()).pathname.split('/').pop() ?? '');
    expect(sessionId).toBeTruthy();

    const history = await apiJson(
      'GET',
      `/chat/history/${encodeURIComponent(sessionId)}`,
    );
    expect(Array.isArray(history)).toBeTruthy();
    const completedTurn = history.find(
      (record: any) => record.userMessage === message,
    );
    expect(completedTurn).toBeTruthy();
    expect(completedTurn.status).toBe('COMPLETE');
    expect(completedTurn.sources).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          documentId: String(probe?.documentId),
        }),
      ]),
    );
    expect(completedTurn.metadata?.executionBudget).toEqual(
      expect.objectContaining({
        toolCalls: expect.any(Number),
        toolRounds: expect.any(Number),
        modelCalls: expect.any(Number),
      }),
    );
    expect(completedTurn.metadata.executionBudget.toolCalls).toBeGreaterThan(0);
    expect(completedTurn.metadata.executionBudget.toolRounds).toBeGreaterThan(0);
    expect(completedTurn.metadata.executionBudget.modelCalls).toBeGreaterThanOrEqual(2);

    const sessionUrl = page.url();
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page).toHaveURL(/\/webui\/(?:unlock|chat\/[^/]+)$/, {
      timeout: REQUEST_TIMEOUT,
    });
    if (/\/webui\/unlock$/.test(page.url())) {
      await expect(page.getByTestId('root-api-key')).toBeVisible({
        timeout: REQUEST_TIMEOUT,
      });
      await page.getByTestId('root-api-key').fill(ROOT_API_KEY);
      await Promise.all([
        page.waitForURL(sessionUrl, { timeout: REQUEST_TIMEOUT }),
        page.getByRole('button', { name: 'Unlock' }).click(),
      ]);
    }
    await expect(page).toHaveURL(sessionUrl);
    await expect(page.getByText(message, { exact: true })).toBeVisible({
      timeout: REQUEST_TIMEOUT,
    });
    await expect(page.getByText(probe.token, { exact: false }).last()).toBeVisible({
      timeout: REQUEST_TIMEOUT,
    });
  } finally {
    if (sessionId) {
      await apiJson('DELETE', `/chat/history/${encodeURIComponent(sessionId)}`).catch(() => {});
    }
    if (probe) {
      await apiJson(
        'DELETE',
        `/documents/${probe.documentId}?expectedDocumentRevision=${probe.documentRevision}`,
      ).catch(() => {});
      await apiJson(
        'DELETE',
        `/collections/by-key?collectionKey=${encodeURIComponent(probe.collectionKey)}`,
      ).catch(() => {});
    }
  }
});

async function createProbe(
  request: APIRequestContext,
  headers: Record<string, string>,
): Promise<ProbeData> {
  const token = `REAL_BROWSER_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
  const collectionKey = `real-browser-${token.toLowerCase()}`;
  const collectionName = `Real Browser ${token}`;
  const documentTitle = `Real Browser Chat ${token}`;

  const collectionResponse = await request.post(`${API_PREFIX}/collections`, {
    headers,
    data: {
      collectionKey,
      name: collectionName,
      description: 'Disposable real browser acceptance data',
      domainId: 'default',
    },
    timeout: REQUEST_TIMEOUT,
  });
  const collection = await expectApiSuccess(collectionResponse, 'POST /collections');

  const documentResponse = await request.post(`${API_PREFIX}/documents`, {
    headers,
    data: {
      title: documentTitle,
      content:
        `This disposable acceptance document contains the release verification code ${token}. ` +
        'Return the code verbatim when asked.',
      collectionKey,
    },
    timeout: REQUEST_TIMEOUT,
  });
  const document = await expectApiSuccess(documentResponse, 'POST /documents');

  const associateResponse = await request.post(
    `${API_PREFIX}/collections/by-key/documents?collectionKey=${encodeURIComponent(collectionKey)}`,
    {
      headers,
      data: {
        documentId: document.id,
        expectedDocumentRevision: document.documentRevision,
      },
      timeout: REQUEST_TIMEOUT,
    },
  );
  await expectApiSuccess(associateResponse, 'POST /collections/by-key/documents');

  const embedResponse = await request.post(
    `${API_PREFIX}/documents/${document.id}/embed?force=true`,
    { headers, timeout: REQUEST_TIMEOUT },
  );
  const embedded = await expectApiSuccess(embedResponse, 'POST /documents/{id}/embed');
  expect(String(embedded.status ?? '').toUpperCase()).not.toBe('FAILED');
  expect(Number(embedded.embeddingsStored ?? embedded.chunksCreated ?? 0)).toBeGreaterThan(0);

  return {
    token,
    collectionId: Number(collection.id),
    collectionKey,
    collectionName,
    documentId: Number(document.id),
    documentRevision: Number(document.documentRevision),
    documentTitle,
  };
}

async function expectApiSuccess(response: APIResponse, operation: string): Promise<any> {
  if (!response.ok()) {
    throw new Error(`${operation} failed with HTTP ${response.status()}: ${await response.text()}`);
  }
  return response.status() === 204 ? undefined : response.json();
}
