import { expect, test, type APIResponse } from '@playwright/test';

const ROOT_API_KEY = process.env.RAG_ROOT_API_KEY ?? process.env.REAL_E2E_API_KEY;
const API_PREFIX = '/api/v1/rag';
const REQUEST_TIMEOUT = 240_000;

test.describe.configure({ mode: 'serial' });
test.setTimeout(REQUEST_TIMEOUT);

test('imports a readable PDF and preserves a stable file-manager workspace', async ({
  page,
  request,
}) => {
  if (!ROOT_API_KEY) {
    throw new Error('RAG_ROOT_API_KEY or REAL_E2E_API_KEY is required');
  }

  const runId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const token = `FILES_REAL_${runId.replace(/-/g, '_')}`;
  const filename = `客户售后条款-${runId}.pdf`;
  const collectionKey = `files-real-${runId}`;
  const headers = {
    'X-API-Key': ROOT_API_KEY,
    'Content-Type': 'application/json',
  };
  let chatSessionId: string | undefined;

  try {
    const collectionResponse = await request.post(`${API_PREFIX}/collections`, {
      headers,
      data: {
        collectionKey,
        name: `Files real acceptance ${runId}`,
        description: 'Disposable real Files browser acceptance collection',
        domainId: 'default',
      },
      timeout: REQUEST_TIMEOUT,
    });
    await expectApiSuccess(collectionResponse, 'create Files acceptance collection');

    await page.goto('/webui/files', { waitUntil: 'networkidle' });
    await expect(page).toHaveURL(/\/webui\/unlock$/);
    await page.getByTestId('root-api-key').fill(ROOT_API_KEY);
    await page.getByRole('button', { name: 'Unlock' }).click();
    await expect(page).toHaveURL(/\/webui\/files$/);
    await expect(page.getByRole('heading', { name: 'Files' })).toBeVisible();

    const commandBar = page.getByTestId('files-command-bar');
    const workspace = page.getByTestId('files-workspace');
    const treePanel = page.getByTestId('files-tree-panel');
    const previewPanel = page.getByTestId('files-preview-panel');
    const splitter = page.getByTestId('files-tree-splitter');
    const collectionSelect = page.getByTestId('files-rag-collection-select');
    const addToRag = page.getByRole('button', { name: 'Add to RAG' });

    await expect(commandBar).toBeVisible();
    await expect(page.getByTestId('files-rag-actions')).toHaveCount(0);

    const rootWorkspaceBox = await requiredBox(workspace);
    const rootCommandBox = await requiredBox(commandBar);
    const rootTreeBox = await requiredBox(treePanel);
    const rootPreviewBox = await requiredBox(previewPanel);
    const splitterBox = await requiredBox(splitter);

    await page.mouse.move(
      splitterBox.x + splitterBox.width / 2,
      splitterBox.y + splitterBox.height / 2,
    );
    await page.mouse.down();
    await page.mouse.move(
      splitterBox.x + splitterBox.width / 2 + 64,
      splitterBox.y + splitterBox.height / 2,
    );
    await page.mouse.up();
    const resizedTreeBox = await requiredBox(treePanel);
    expect(resizedTreeBox.width).toBeGreaterThan(rootTreeBox.width + 50);
    const rememberedWidth = Number(await splitter.getAttribute('aria-valuenow'));

    const importResponsePromise = page.waitForResponse(response =>
      response.url().endsWith(`${API_PREFIX}/files/pdf`)
      && response.request().method() === 'POST',
    );
    await page.locator('input[type="file"]').setInputFiles({
      name: filename,
      mimeType: 'application/pdf',
      buffer: createPdfBuffer(
        `Customer warranty policy verification code ${token}`,
      ),
    });
    const importResponse = await importResponsePromise;
    await expectApiSuccess(importResponse, 'import PDF through Files WebUI');
    const imported = await importResponse.json();
    expect(imported).toMatchObject({
      originalFilename: filename,
      displayName: filename,
      filesStored: 2,
    });
    expect(imported.uuid).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );

    await expect.poll(
      () => new URL(page.url()).searchParams.get('path'),
      { timeout: REQUEST_TIMEOUT },
    ).toBe(`${imported.uuid}/`);
    await expect(page.getByRole('button', {
      name: filename,
      exact: true,
    })).toBeVisible();
    await expect(collectionSelect).toBeEnabled();
    await expect(addToRag).toBeEnabled();

    const childWorkspaceBox = await requiredBox(workspace);
    const childCommandBox = await requiredBox(commandBar);
    const childTreeBox = await requiredBox(treePanel);
    const childPreviewBox = await requiredBox(previewPanel);
    expect(Math.abs(childWorkspaceBox.y - rootWorkspaceBox.y)).toBeLessThan(1);
    expect(Math.abs(childWorkspaceBox.height - rootWorkspaceBox.height)).toBeLessThan(1);
    expect(Math.abs(childCommandBox.height - rootCommandBox.height)).toBeLessThan(1);
    expect(Math.abs(childTreeBox.width - resizedTreeBox.width)).toBeLessThan(1);
    expect(Math.abs(childPreviewBox.x - rootPreviewBox.x - 64)).toBeLessThan(3);

    const rootTreeResponse = await request.get(`${API_PREFIX}/files/tree`, {
      headers,
      timeout: REQUEST_TIMEOUT,
    });
    await expectApiSuccess(rootTreeResponse, 'read root tree after PDF import');
    const rootTree = await rootTreeResponse.json();
    expect(rootTree.entries).toEqual(expect.arrayContaining([
      expect.objectContaining({
        name: imported.uuid,
        path: `${imported.uuid}/`,
        displayName: filename,
        originalFilename: filename,
        importId: imported.uuid,
      }),
    ]));

    await collectionSelect.selectOption(collectionKey);
    const embedResponsePromise = page.waitForResponse(response =>
      response.url().includes(`${API_PREFIX}/files/${imported.uuid}/embed?`)
      && response.request().method() === 'POST',
    );
    await addToRag.click();
    const embedResponse = await embedResponsePromise;
    await expectApiSuccess(embedResponse, 'add imported PDF to RAG');
    const embedded = await embedResponse.json();
    expect(embedded).toMatchObject({
      uuid: imported.uuid,
      embedStatus: 'COMPLETED',
    });
    expect(embedded.documentId).toBeGreaterThan(0);
    expect(embedded.chunksCreated).toBeGreaterThan(0);

    const previewResponsePromise = page.waitForResponse(response =>
      response.url().includes(`${API_PREFIX}/files/preview?`)
      && response.request().method() === 'GET',
    );
    await page.getByTitle('default.md').click();
    const previewResponse = await previewResponsePromise;
    await expectApiSuccess(previewResponse, 'preview imported Markdown');
    await expect(page.getByText(token, { exact: false })).toBeVisible();

    await page.getByRole('button', { name: 'Root', exact: true }).click();
    await expect(page).toHaveURL(/\/webui\/files$/);
    await expect(splitter).toHaveAttribute('aria-valuenow', String(rememberedWidth));
    const importedRow = page.getByTestId('file-tree-entry').filter({
      hasText: filename,
    });
    await expect(importedRow).toBeVisible();
    await expect(importedRow).toContainText(imported.uuid);

    const query = page.getByLabel('Find in this folder');
    await query.dispatchEvent('compositionstart', { data: '售' });
    await query.evaluate((element, value) => {
      const input = element as HTMLInputElement;
      const setter = Object.getOwnPropertyDescriptor(
        HTMLInputElement.prototype,
        'value',
      )?.set;
      setter?.call(input, value);
      input.dispatchEvent(new InputEvent('input', {
        bubbles: true,
        data: value,
        inputType: 'insertCompositionText',
        isComposing: true,
      }));
    }, '售后条款');
    await expect(query).toHaveValue('售后条款');
    await page.waitForTimeout(350);
    expect(new URL(page.url()).searchParams.get('q')).toBeNull();
    await query.dispatchEvent('compositionend', { data: '售后条款' });
    await expect.poll(() => new URL(page.url()).searchParams.get('q'))
      .toBe('售后条款');
    await expect(importedRow).toBeVisible();

    const searchResponse = await request.post(`${API_PREFIX}/search`, {
      headers,
      data: {
        query: token,
        collectionKeys: [collectionKey],
        documentIds: [embedded.documentId],
        config: {
          maxResults: 5,
          minScore: 0,
          useHybridSearch: true,
          useRerank: true,
        },
      },
      timeout: REQUEST_TIMEOUT,
    });
    await expectApiSuccess(searchResponse, 'search imported PDF content');
    const searchPayload = await searchResponse.json();
    const results = Array.isArray(searchPayload)
      ? searchPayload
      : searchPayload.results ?? searchPayload.data ?? [];
    expect(results).toEqual(expect.arrayContaining([
      expect.objectContaining({
        documentId: String(embedded.documentId),
      }),
    ]));
    expect(results.some((result: { chunkText?: string }) =>
      result.chunkText?.includes(token))).toBe(true);

    const chatResponse = await request.post(`${API_PREFIX}/chat/ask`, {
      headers,
      data: {
        message:
          `Using only the selected imported policy document, return the verification code `
          + `that begins with FILES_REAL_.`,
        maxResults: 5,
        useHybridSearch: true,
        useRerank: true,
        collectionKeys: [collectionKey],
        documentIds: [embedded.documentId],
      },
      timeout: REQUEST_TIMEOUT,
    });
    await expectApiSuccess(chatResponse, 'answer from imported PDF content');
    const chatPayload = await chatResponse.json();
    chatSessionId = chatPayload.sessionId;
    expect(chatPayload.answer).toContain(token);
    expect(chatPayload.sources).toEqual(expect.arrayContaining([
      expect.objectContaining({
        documentId: String(embedded.documentId),
      }),
    ]));
  } finally {
    if (chatSessionId) {
      await request.delete(
        `${API_PREFIX}/chat/history/${encodeURIComponent(chatSessionId)}`,
        { headers, timeout: REQUEST_TIMEOUT },
      ).catch(() => undefined);
    }
    await request.delete(
      `${API_PREFIX}/collections/by-key?collectionKey=${encodeURIComponent(collectionKey)}`,
      { headers, timeout: REQUEST_TIMEOUT },
    ).catch(() => undefined);
  }
});

async function requiredBox(locator: {
  boundingBox(): Promise<{
    x: number;
    y: number;
    width: number;
    height: number;
  } | null>;
}) {
  const box = await locator.boundingBox();
  expect(box).not.toBeNull();
  return box!;
}

async function expectApiSuccess(
  response: APIResponse,
  operation: string,
): Promise<void> {
  if (!response.ok()) {
    throw new Error(
      `${operation} failed with HTTP ${response.status()}: ${await response.text()}`,
    );
  }
}

function createPdfBuffer(text: string): Buffer {
  const escapedText = text
    .replaceAll('\\', '\\\\')
    .replaceAll('(', '\\(')
    .replaceAll(')', '\\)');
  const stream = `BT\n/F1 12 Tf\n72 720 Td\n(${escapedText}) Tj\nET\n`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] '
      + '/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${Buffer.byteLength(stream, 'ascii')} >>\nstream\n${stream}endstream`,
  ];

  let pdf = '%PDF-1.4\n';
  const offsets = [0];
  for (const [index, object] of objects.entries()) {
    offsets.push(Buffer.byteLength(pdf, 'ascii'));
    pdf += `${index + 1} 0 obj\n${object}\nendobj\n`;
  }
  const xrefOffset = Buffer.byteLength(pdf, 'ascii');
  pdf += `xref\n0 ${objects.length + 1}\n`;
  pdf += '0000000000 65535 f \n';
  for (const offset of offsets.slice(1)) {
    pdf += `${String(offset).padStart(10, '0')} 00000 n \n`;
  }
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\n`;
  pdf += `startxref\n${xrefOffset}\n%%EOF\n`;
  return Buffer.from(pdf, 'ascii');
}
