import { expect, type APIRequestContext, type Page } from '@playwright/test';
import type { ChatHistoryRecord } from '../../src/types/api';

export const API_PREFIX = '/api/v1/rag';
export const REQUEST_TIMEOUT = 180_000;

type CapturedChatWindow = Window & { chatAcceptanceBodies?: Promise<string>[] };

export function rootHeaders(): Record<string, string> {
  const key = process.env.RAG_ROOT_API_KEY ?? process.env.REAL_E2E_API_KEY;
  if (!key) throw new Error('RAG_ROOT_API_KEY or REAL_E2E_API_KEY is required');
  return { 'X-API-Key': key, 'Content-Type': 'application/json' };
}

export async function apiJson<Result = any>(
  request: APIRequestContext,
  method: 'GET' | 'POST' | 'DELETE',
  path: string,
  data?: Record<string, unknown>,
): Promise<Result> {
  const response = await request.fetch(`${API_PREFIX}${path}`, {
    method, headers: rootHeaders(), data, timeout: REQUEST_TIMEOUT,
  });
  if (!response.ok()) {
    throw new Error(`${method} ${path} failed with HTTP ${response.status()}: ${await response.text()}`);
  }
  return response.status() === 204 ? undefined as Result : response.json();
}

export async function unlockChat(page: Page, path = '/webui/chat') {
  await page.addInitScript(() => {
    const captured = window as CapturedChatWindow;
    if (captured.chatAcceptanceBodies) return;
    captured.chatAcceptanceBodies = [];
    const originalFetch = window.fetch.bind(window);
    window.fetch = async (...args) => {
      const response = await originalFetch(...args);
      const target = args[0] instanceof Request ? args[0].url : String(args[0]);
      if (target.includes('/api/v1/rag/chat/stream')) {
        const body = response.clone().text();
        captured.chatAcceptanceBodies!.push(body);
        void body.catch(() => undefined);
      }
      return response;
    };
  });
  await page.goto(path, { waitUntil: 'networkidle' });
  if (new URL(page.url()).pathname === '/webui/unlock') {
    await page.getByTestId('root-api-key').fill(rootHeaders()['X-API-Key']);
    await page.getByRole('button', { name: 'Unlock', exact: true }).click();
  }
  await expect(page.getByRole('heading', { name: 'Chat', exact: true })).toBeVisible();
  await expect(page.locator('textarea')).toBeEnabled();
}

export async function completedTurn(
  page: Page,
  request: APIRequestContext,
  message: string,
  expectedRequest: Record<string, unknown>,
): Promise<ChatHistoryRecord> {
  const captureIndex = await page.evaluate(() => (window as CapturedChatWindow).chatAcceptanceBodies!.length);
  const outbound = page.waitForRequest(candidate =>
    candidate.url().includes(`${API_PREFIX}/chat/stream`) && candidate.method() === 'POST');
  const inbound = page.waitForResponse(candidate =>
    candidate.url().includes(`${API_PREFIX}/chat/stream`) && candidate.request().method() === 'POST');
  await page.locator('textarea').fill(message);
  await page.getByRole('button', { name: 'Send', exact: true }).click();
  expect((await outbound).postDataJSON()).toMatchObject({ message, ...expectedRequest });
  const response = await inbound;
  expect(response.status(), '正常业务结果应使用HTTP200').toBe(200);
  expect(await response.finished(), 'SSE连接应正常结束').toBeNull();
  expect(response.headers()['x-rag-turn-id']).toBeTruthy();
  const body = await page.evaluate(async index => {
    const captures = (window as CapturedChatWindow).chatAcceptanceBodies!;
    if (!captures[index]) throw new Error('浏览器未捕获本次真实SSE响应');
    return captures[index];
  }, captureIndex);
  const frames = body.replace(/\r\n/g, '\n').split('\n\n')
    .filter(block => block.split('\n').some(line => line.startsWith('data:')))
    .map(block => {
      const lines = block.split('\n');
      return {
        event: lines.find(line => line.startsWith('event:'))?.slice(6).trim(),
        data: JSON.parse(lines.filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart()).join('\n')),
      };
    });
  expect(frames.filter(frame => frame.event === 'error'), '正常拒答不是服务异常').toHaveLength(0);
  const done = frames.filter(frame => frame.event === 'done');
  expect(done, '成功或正常拒答应恰好有一个done终态').toHaveLength(1);
  expect(done[0].data.status).toBe('complete');
  const sessionId = String(done[0].data.sessionId ?? '');
  expect(sessionId).not.toBe('');
  await expect.poll(() => new URL(page.url()).pathname).toBe(`/webui/chat/${sessionId}`);
  await expect(page.locator('textarea')).toBeEnabled();
  await expect(page.getByText(/^Error:/)).toHaveCount(0);
  const history = await apiJson<ChatHistoryRecord[]>(request, 'GET', `/chat/history/${encodeURIComponent(sessionId)}`);
  const record = history.find(candidate => candidate.userMessage === message);
  expect(record, '正常结果必须持久化为历史记录').toBeTruthy();
  expect(record!.status).toBe('COMPLETE');
  const delivered = frames.filter(frame => frame.event === 'content')
    .map(frame => frame.data.choices?.[0]?.delta?.content ?? frame.data.content ?? '').join('');
  expect(delivered.trim()).not.toBe('');
  expect(record!.aiResponse).toBe(delivered);
  await expect(page.getByText(record!.aiResponse, { exact: true })).toBeVisible();
  return record!;
}

export async function recoveredHistory(page: Page, record: ChatHistoryRecord) {
  const path = new URL(page.url()).pathname + new URL(page.url()).search;
  await unlockChat(page, path);
  await expect(page.getByText(record.userMessage, { exact: true })).toBeVisible();
  await expect(page.getByText(record.aiResponse, { exact: true })).toBeVisible();
  await expect(page.locator('textarea')).toBeEnabled();
  await expect(page.getByText(/^Error:/)).toHaveCount(0);
}

export async function clearPageSession(page: Page, request: APIRequestContext) {
  const sessionId = new URL(page.url()).pathname.match(/^\/webui\/chat\/([^/]+)$/)?.[1];
  if (sessionId) await apiJson(request, 'DELETE', `/chat/history/${sessionId}`);
}
