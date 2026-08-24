import fs from 'node:fs';
import path from 'node:path';
import { expect, test, type APIResponse } from '@playwright/test';

const ROOT_API_KEY = process.env.RAG_ROOT_API_KEY;
const FIXTURE_FILE = process.env.RERANK_DIVERSITY_FIXTURE_FILE;
const API_PREFIX = '/api/v1/rag';
const REQUEST_TIMEOUT = 180_000;

type FixtureDocument = {
  id: number;
  title: string;
  chunksCreated: number;
};

type DiversityFixture = {
  query: string;
  collectionKey: string;
  maxResults: number;
  preferredMaxChunksPerDocument: number;
  documents: FixtureDocument[];
};

type SearchResult = {
  documentId: string;
  chunkIndex: number;
  chunkText: string;
  title: string;
  score: number;
};

test.describe.configure({ mode: 'serial' });
test.setTimeout(REQUEST_TIMEOUT);

test('verifies rerank diversity through the real Vite proxy and Search DOM', async ({
  page,
  request,
}) => {
  if (!ROOT_API_KEY) {
    throw new Error('RAG_ROOT_API_KEY is required');
  }
  if (!FIXTURE_FILE) {
    throw new Error('RERANK_DIVERSITY_FIXTURE_FILE is required');
  }

  const fixture = readFixture(FIXTURE_FILE);
  const headers = {
    'X-API-Key': ROOT_API_KEY,
    'Content-Type': 'application/json',
  };
  const knownDocumentIds = new Set(
    fixture.documents.map(document => String(document.id)),
  );

  const postResponse = await request.post(`${API_PREFIX}/search`, {
    headers,
    data: {
      query: fixture.query,
      collectionScopeMode: 'SELECTED_COLLECTIONS',
      collectionKeys: [fixture.collectionKey],
      config: {
        maxResults: fixture.maxResults,
        minScore: 0,
        useHybridSearch: true,
        useRerank: true,
        vectorWeight: 0.55,
        fulltextWeight: 0.45,
      },
    },
    timeout: REQUEST_TIMEOUT,
  });
  await expectApiSuccess(postResponse, 'POST /search through Vite proxy');
  expect(postResponse.headers()['x-rag-retrieval-trace-id']).toBeTruthy();

  const reranked = await postResponse.json() as SearchResult[];
  expect(reranked).toHaveLength(fixture.maxResults);
  expect(new Set(reranked.map(result => result.documentId)).size)
    .toBeGreaterThanOrEqual(2);

  const counts = new Map<string, number>();
  for (const [index, result] of reranked.entries()) {
    expect(knownDocumentIds.has(String(result.documentId))).toBe(true);
    expect(Number.isInteger(result.chunkIndex)).toBe(true);
    expect(result.chunkIndex).toBeGreaterThanOrEqual(0);
    expect(typeof result.score).toBe('number');
    expect(Number.isFinite(result.score)).toBe(true);
    expect(result.title).toBeTruthy();
    expect(result.chunkText).toContain(fixture.query);
    if (index > 0) {
      expect(reranked[index - 1].score + Number.EPSILON)
        .toBeGreaterThanOrEqual(result.score);
    }
    counts.set(
      String(result.documentId),
      (counts.get(String(result.documentId)) ?? 0) + 1,
    );
  }
  expect(Math.max(...counts.values()))
    .toBeLessThanOrEqual(fixture.preferredMaxChunksPerDocument);

  const searchParams = new URLSearchParams({
    query: fixture.query,
    hybrid: 'true',
    scopeMode: 'SELECTED_COLLECTIONS',
  });
  searchParams.append('collectionKey', fixture.collectionKey);
  await page.goto(`/webui/search?${searchParams.toString()}`, {
    waitUntil: 'domcontentloaded',
  });
  await expect(page).toHaveURL(/\/webui\/unlock$/);
  await page.getByTestId('root-api-key').fill(ROOT_API_KEY);

  const getResponsePromise = page.waitForResponse(response =>
    response.url().includes(`${API_PREFIX}/search?`)
    && response.request().method() === 'GET'
    && response.status() === 200,
  );
  await page.getByRole('button', { name: 'Unlock' }).click();
  const getResponse = await getResponsePromise;
  const getRequest = getResponse.request();
  expect(getRequest.headers()['x-api-key']).toBeTruthy();
  const getUrl = new URL(getRequest.url());
  expect(getUrl.searchParams.get('collectionScopeMode'))
    .toBe('SELECTED_COLLECTIONS');
  expect(getUrl.searchParams.getAll('collectionKeys'))
    .toEqual([fixture.collectionKey]);

  const pagePayload = await getResponse.json();
  const pageResults = pagePayload.results as SearchResult[];
  expect(pageResults.length).toBeGreaterThan(0);
  expect(pageResults.length).toBeLessThanOrEqual(10);

  await expect(page).toHaveURL(/\/webui\/search\?/);
  await expect(page.getByRole('heading', { name: 'Search' })).toBeVisible();
  const countText =
    `${pageResults.length} result${pageResults.length === 1 ? '' : 's'} `
    + `for "${fixture.query}"`;
  const count = page.getByText(countText, { exact: true });
  await expect(count).toBeVisible();

  const children = count.locator('..').locator(':scope > div');
  await expect(children).toHaveCount(pageResults.length + 1);
  for (const [index, result] of pageResults.entries()) {
    const row = children.nth(index + 1);
    await expect(row.getByText(result.title, { exact: true })).toBeVisible();
    await expect(row).toContainText(result.chunkText.slice(0, 80));
  }
});

function readFixture(file: string): DiversityFixture {
  const fixturePath = path.resolve(file);
  const value = JSON.parse(fs.readFileSync(fixturePath, 'utf8')) as DiversityFixture;
  if (!value.query || !value.collectionKey || value.documents.length < 4) {
    throw new Error(`Invalid rerank diversity fixture: ${fixturePath}`);
  }
  return value;
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
