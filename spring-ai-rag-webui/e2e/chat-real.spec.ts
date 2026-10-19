import { test, expect, type APIRequestContext } from '@playwright/test';
import {
  API_PREFIX, REQUEST_TIMEOUT, apiJson, clearPageSession, completedTurn,
  recoveredHistory, rootHeaders, unlockChat,
} from './support/chat-lifecycle';

type ProbeData = {
  collectionKey: string;
  documentId: number;
  documentRevision: number;
  documentTitle: string;
  openingTime: string;
};

test.setTimeout(REQUEST_TIMEOUT);

test('lifecycle: real PLAIN SSE completes, persists and recovers history', async ({ page, request }) => {
  try {
    await unlockChat(page);
    await page.getByTestId('chat-mode-select').selectOption('PLAIN');
    const record = await test.step('只验证业务生命周期，不宣称事实或检索成功', () =>
      completedTurn(page, request, 'Write one short plain-text sentence about visiting a public library.', { mode: 'PLAIN' }));
    await recoveredHistory(page, record);
  } finally {
    await clearPageSession(page, request);
  }
});

test('positive-answer: real Agent retrieves a public fact with sources', async ({ page, request }) => {
  let probe: ProbeData | undefined;
  try {
    probe = await createProbe(request);
    const modelList = await apiJson(request, 'GET', '/models');
    const agentModel = modelList.models?.find(
      (model: any) => model.available && model.capabilities?.toolCalling === true,
    );
    expect(agentModel?.ref, 'Agent正向验收需要可用的tool-calling模型').toBeTruthy();
    await unlockChat(page);
    await page.getByTestId('chat-model-select').selectOption(agentModel.ref);
    await expect(page.getByTestId('chat-model-select')).toHaveValue(agentModel.ref);
    await page.getByTestId('chat-mode-select').selectOption('AGENT');
    await expect(page.getByTestId('chat-mode-select')).toHaveValue('AGENT');
    await page.getByTestId('chat-scope-SELECTED_COLLECTIONS').check();
    await page.locator(`input[type="checkbox"][value="${probe.collectionKey}"]`).check();

    const record = await test.step('先确认请求终态、正常展示与持久化', () => completedTurn(
      page, request,
      'Use searchKnowledge on the selected collection. At what time does Cedar Observatory open to visitors? Give the time in HH:mm format and cite the guide.',
      { mode: 'AGENT', model: agentModel.ref, collectionScopeMode: 'SELECTED_COLLECTIONS', collectionKeys: [probe!.collectionKey] },
    ));
    await recoveredHistory(page, record);
    await test.step('严格验证正向事实、真实工具调用和来源；拒答不能替代成功', async () => {
      const metadata = record.metadata as Record<string, any>;
      expect(metadata?.answerOutcome?.status, '明确安全拒答不算检索成功').not.toBe('MODEL_SAFETY_REFUSAL');
      expect(record.aiResponse).toContain(probe!.openingTime);
      expect(record.sources).toEqual(expect.arrayContaining([
        expect.objectContaining({ documentId: String(probe!.documentId) }),
      ]));
      expect(metadata?.executionBudget).toEqual(expect.objectContaining({
        toolCalls: expect.any(Number), toolRounds: expect.any(Number), modelCalls: expect.any(Number),
      }));
      expect(metadata!.executionBudget.toolCalls).toBeGreaterThan(0);
      expect(metadata!.executionBudget.toolRounds).toBeGreaterThan(0);
      expect(metadata!.executionBudget.modelCalls).toBeGreaterThanOrEqual(2);
      await expect(page.getByText(probe!.documentTitle, { exact: true })).toBeVisible();
    });
  } finally {
    try {
      await clearPageSession(page, request);
    } finally {
      if (probe) await deleteProbe(request, probe);
    }
  }
});

test('positive-answer: real KNOWLEDGE API returns grounded facts and retrieval diagnostics', async ({ request }) => {
  let probe: ProbeData | undefined;
  let sessionId: string | undefined;
  try {
    probe = await createProbe(request);
    const response = await apiJson(request, 'POST', '/chat', {
      message: 'At what time does Cedar Observatory open to visitors? Give the time in HH:mm format and cite the guide.',
      mode: 'KNOWLEDGE', collectionScopeMode: 'SELECTED_COLLECTIONS', collectionKeys: [probe.collectionKey],
      maxResults: 5, useHybridSearch: true, useRerank: true,
    });
    sessionId = response.sessionId;
    expect(sessionId).toBeTruthy();
    expect(response.answer).toContain(probe.openingTime);
    expect(response.sources).toEqual(expect.arrayContaining([
      expect.objectContaining({ documentId: String(probe.documentId) }),
    ]));
    expect(response.metadata?.retrievalExecuted).toBe(true);
    const documentJoin = response.metadata?.retrieval?.documentJoin;
    expect(documentJoin).toBeTruthy();
    expect(Object.keys(documentJoin).sort()).toEqual([
      'duplicateDocumentsRemoved', 'inputDocuments', 'scoreReplacements', 'uniqueDocuments',
    ]);
    for (const value of Object.values(documentJoin)) {
      expect(Number.isInteger(value)).toBe(true);
      expect(value).toBeGreaterThanOrEqual(0);
    }
    expect(documentJoin.inputDocuments).toBeGreaterThanOrEqual(documentJoin.uniqueDocuments);
    expect(documentJoin.duplicateDocumentsRemoved).toBe(documentJoin.inputDocuments - documentJoin.uniqueDocuments);
    expect(documentJoin.scoreReplacements).toBeLessThanOrEqual(documentJoin.duplicateDocumentsRemoved);
    const serialized = JSON.stringify(documentJoin);
    for (const privateValue of ['documentId', probe.collectionKey, probe.documentTitle]) {
      expect(serialized).not.toContain(privateValue);
    }
    if (response.metadata?.citationValidation?.status) {
      expect(response.metadata.citationValidation.status).not.toBe('INVALID_CITATION');
    }
  } finally {
    try {
      if (sessionId) await apiJson(request, 'DELETE', `/chat/history/${encodeURIComponent(sessionId)}`);
    } finally {
      if (probe) await deleteProbe(request, probe);
    }
  }
});

async function createProbe(request: APIRequestContext): Promise<ProbeData> {
  const runId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const collectionKey = `real-browser-${runId}`;
  const documentTitle = `Cedar Observatory visitor guide ${runId}`;
  const openingTime = '09:40';
  await apiJson(request, 'POST', '/collections', {
    collectionKey, name: `Public visitor information ${runId}`,
    description: 'Disposable public-information browser acceptance data', domainId: 'default',
  });
  let document: { id: number; documentRevision: number } | undefined;
  try {
    const created = await apiJson<{ id: number; documentRevision: number }>(request, 'POST', '/documents', {
      title: documentTitle,
      content: `Cedar Observatory opens to visitors at ${openingTime} every day. The morning tour starts at 10:20. Ticket pickup is at the north entrance.`,
      collectionKey,
    });
    document = created;
    await apiJson(request, 'POST', `/collections/by-key/documents?collectionKey=${encodeURIComponent(collectionKey)}`, {
      documentId: created.id, expectedDocumentRevision: created.documentRevision,
    });
    const embedded = await apiJson(request, 'POST', `/documents/${created.id}/embed?force=true`);
    expect(String(embedded.status ?? '').toUpperCase()).toMatch(/^(COMPLETED|CACHED)$/);
    const current = await apiJson(request, 'GET', `/documents/${created.id}`);
    expect(Number(current.documentRevision)).toBeGreaterThanOrEqual(1);
    const search = await request.post(`${API_PREFIX}/search`, {
      headers: rootHeaders(), timeout: REQUEST_TIMEOUT,
      data: { query: 'Cedar Observatory opening time', documentIds: [Number(created.id)],
        config: { maxResults: 3, minScore: 0, useHybridSearch: true, useRerank: true } },
    });
    expect(search.ok(), '真实Embedding索引必须能通过Search检索').toBe(true);
    const result = await search.json();
    expect(JSON.stringify(result)).toContain(openingTime);
    return { collectionKey, documentId: Number(created.id), documentRevision: Number(current.documentRevision), documentTitle, openingTime };
  } catch (error) {
    const errors = [error];
    if (document) {
      try {
        const current = await apiJson(request, 'GET', `/documents/${document.id}`);
        await apiJson(request, 'DELETE', `/documents/${document.id}?expectedDocumentRevision=${current.documentRevision}`);
      } catch (cleanupError) { errors.push(cleanupError); }
    }
    try {
      await apiJson(request, 'DELETE', `/collections/by-key?collectionKey=${encodeURIComponent(collectionKey)}`);
    } catch (cleanupError) { errors.push(cleanupError); }
    if (errors.length > 1) throw new AggregateError(errors, '探针创建失败且清理未完成');
    throw error;
  }
}

async function deleteProbe(request: APIRequestContext, probe: ProbeData) {
  await apiJson(request, 'DELETE', `/documents/${probe.documentId}?expectedDocumentRevision=${probe.documentRevision}`);
  await apiJson(request, 'DELETE', `/collections/by-key?collectionKey=${encodeURIComponent(probe.collectionKey)}`);
}
