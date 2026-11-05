import { expect, test, type APIResponse } from '@playwright/test';
import {
  API_PREFIX,
  completedTurn,
  rootHeaders,
  unlockChat,
} from './support/chat-lifecycle';
import { createPdfBuffer } from './support/pdf-fixture';

const REAL = process.env.REAL_WEBUI_E2E === '1';
const REQUEST_TIMEOUT = 300_000;

test.describe.configure({ mode: 'serial' });
test.setTimeout(REQUEST_TIMEOUT);

/**
 * Real-backend WebUI acceptance: upload -> parse -> index -> retrieval/chat ->
 * clickable citation. Everything below talks to the packaged WebUI served by a
 * running backend; no route interception and no fixture API stubs.
 */
test('main journey: real upload, index, streamed answer and clickable citation', async ({
  page,
  request,
}) => {
  test.skip(!REAL, 'REAL_WEBUI_E2E=1 is required (nightly or explicit switch)');

  const runId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const codename = `NEBULA-${runId}`;
  const filename = `WebUI 真实链路-${runId}.pdf`;
  const collectionKey = `webui-real-${runId}`;
  let chatSessionId: string | undefined;

  await request.post(`${API_PREFIX}/collections`, {
    headers: rootHeaders(),
    data: {
      collectionKey,
      name: `WebUI real acceptance ${runId}`,
      description: 'Disposable real WebUI acceptance collection',
      domainId: 'default',
    },
    timeout: REQUEST_TIMEOUT,
  }).then(response => expectApiSuccess(response, 'create WebUI acceptance collection'));

  try {
    await page.goto('/webui/files', { waitUntil: 'networkidle' });
    await unlockIfNeeded(page);
    await expect(page.getByRole('heading', { name: 'Files' })).toBeVisible();

    const importPromise = page.waitForResponse(response =>
      response.url().endsWith(`${API_PREFIX}/files/pdf`)
      && response.request().method() === 'POST',
    );
    await page.locator('input[type="file"]').setInputFiles({
      name: filename,
      mimeType: 'application/pdf',
      buffer: createPdfBuffer(
        `The internal codename for this acceptance run is ${codename}. `
        + 'This document is the only source that contains this codename.',
      ),
    });
    const importResponse = await importPromise;
    expectApiSuccess(importResponse, 'import PDF through the real Files UI');

    await expect(page.getByRole('button', {
      name: filename,
      exact: true,
    })).toBeVisible({ timeout: 60_000 });
    await page.getByTestId('files-rag-collection-select').selectOption(collectionKey);

    const embedPromise = page.waitForResponse(response =>
      response.url().includes(`${API_PREFIX}/files/`)
      && response.url().includes('/embed?')
      && response.request().method() === 'POST',
    );
    await page.getByRole('button', { name: 'Add to RAG', exact: true }).click();
    const embedResponse = await embedPromise;
    expectApiSuccess(embedResponse, 'index imported PDF through the real UI');
    const embedded = await embedResponse.json();
    expect(embedded.embedStatus, '真实索引必须完成而不是排队').toBe('COMPLETED');
    expect(embedded.documentId).toBeGreaterThan(0);

    // Retrieval must already see the real indexed content before the chat turn.
    const searchResponse = await request.post(`${API_PREFIX}/search`, {
      headers: rootHeaders(),
      data: {
        query: codename,
        collectionKeys: [collectionKey],
        documentIds: [embedded.documentId],
        config: { maxResults: 3, minScore: 0, useHybridSearch: true },
      },
      timeout: REQUEST_TIMEOUT,
    });
    expectApiSuccess(searchResponse, 'retrieve the freshly indexed document');
    const searchPayload = await searchResponse.json();
    const results = Array.isArray(searchPayload)
      ? searchPayload
      : searchPayload.results ?? searchPayload.data ?? [];
    expect(results.some((result: { chunkText?: string }) =>
      result.chunkText?.includes(codename))).toBe(true);

    await unlockChat(page, '/webui/chat');
    const record = await completedTurn(
      page,
      request,
      `According to the knowledge base, what is the internal codename for acceptance run ${runId}? `
      + 'Answer with the codename only.',
      {},
    );
    chatSessionId = new URL(page.url()).pathname.match(/^\/webui\/chat\/([^/]+)$/)?.[1];
    expect(record.aiResponse).toContain(codename);

    const citation = page.getByRole('link').filter({ hasText: collectionKey }).first();
    await expect(citation, '回答必须展示可点击的引用').toBeVisible();
    const citedTitle = (await citation.innerText())
      .replace(/^\s*S\d+\s*/, '')
      .split('·')[0]
      .trim();
    await citation.click();

    await expect(page).toHaveURL(/\/webui\/documents\?previewDocumentId=/);
    await expect(page.getByRole('heading', { name: 'Documents', exact: true }))
      .toBeVisible();
    await expect(page.getByRole('dialog', { name: new RegExp(citedTitle) }))
      .toBeVisible();
    await expect(page.getByRole('dialog')).toContainText(codename);
  } finally {
    if (chatSessionId) {
      await request.delete(
        `${API_PREFIX}/chat/history/${encodeURIComponent(chatSessionId)}`,
        { headers: rootHeaders(), timeout: REQUEST_TIMEOUT },
      ).catch(() => undefined);
    }
    await request.delete(
      `${API_PREFIX}/collections/by-key?collectionKey=${encodeURIComponent(collectionKey)}`,
      { headers: rootHeaders(), timeout: REQUEST_TIMEOUT },
    ).catch(() => undefined);
  }
});

async function unlockIfNeeded(page: import('@playwright/test').Page) {
  if (new URL(page.url()).pathname !== '/webui/unlock') return;
  await page.getByTestId('root-api-key').fill(rootHeaders()['X-API-Key']);
  await page.getByRole('button', { name: 'Unlock', exact: true }).click();
}

async function expectApiSuccess(response: APIResponse, operation: string) {
  if (!response.ok()) {
    throw new Error(
      `${operation} failed with HTTP ${response.status()}: ${await response.text()}`,
    );
  }
}
