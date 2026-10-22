import { randomUUID } from 'node:crypto';

const baseUrl = (process.env.RAG_BASE_URL ?? 'http://localhost:8081').replace(/\/$/u, '');
const apiKey = process.env.RAG_API_KEY;
const collectionKey = process.env.RAG_COLLECTION_KEY;

if (!apiKey || !collectionKey) {
  throw new Error('Set RAG_API_KEY and RAG_COLLECTION_KEY before running this example.');
}

async function requestJson(path, { method = 'GET', body, headers = {}, timeoutMs = 30_000 } = {}) {
  const response = await fetch(new URL(path, `${baseUrl}/`), {
    method,
    headers: {
      Authorization: `Bearer ${apiKey}`,
      Accept: 'application/json',
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const value = await response.json();
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}${value.code ? ` (${value.code})` : ''}`);
  }
  return { response, value };
}

const query = new URLSearchParams({ collectionKey });
const { value: preview } = await requestJson(
  `/api/v1/rag/collections/by-key/purge/async/preview?${query}`,
  { method: 'POST', timeoutMs: 150_000 },
);

if (!process.stdin.isTTY) {
  throw new Error('Run this destructive example in an interactive terminal.');
}
process.stdout.write(`Type ${collectionKey} to permanently purge this Collection: `);
const { createInterface } = await import('node:readline/promises');
const input = createInterface({ input: process.stdin, output: process.stdout });
const confirmation = await input.question('');
input.close();
if (confirmation !== collectionKey) {
  throw new Error('Confirmation did not match; nothing was submitted.');
}

const idempotencyKey = randomUUID();
const body = {
  collectionKey: preview.collectionKey,
  previewId: preview.previewId,
  confirmationToken: preview.confirmationToken,
  fingerprint: preview.fingerprint,
  expectedCollectionVersion: preview.collectionVersion,
  expectedChatCommitFenceVersion: preview.chatCommitFenceVersion,
};
let { response, value: receipt } = await requestJson(
  '/api/v1/rag/collections/by-key/purge/async',
  { method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } },
);

while (!['SUCCEEDED', 'FAILED', 'DEGRADED', 'STALE', 'CANCELLED'].includes(receipt.status)) {
  const delaySeconds = Number(response.headers.get('Retry-After') ?? receipt.retryAfterSeconds ?? 2);
  await new Promise((resolve) => setTimeout(resolve, Math.max(1, delaySeconds) * 1000));
  ({ response, value: receipt } = await requestJson(receipt.statusUrl));
}

if (receipt.status !== 'SUCCEEDED') {
  throw new Error(`Purge operation ended with status ${receipt.status}`);
}

const { value: result } = await requestJson(receipt.resultUrl);
console.log(JSON.stringify(result, null, 2));
