import { expect, test } from '@playwright/test';
import { apiJson, unlockChat } from './support/chat-lifecycle';
import type { ChatHistoryRecord } from '../src/types/api';
import { startRealProxy } from './support/real-proxy';

const REAL = process.env.REAL_WEBUI_E2E === '1';
const BACKEND = process.env.REAL_WEBUI_BASE_URL ?? 'http://127.0.0.1:18081';
const TIMEOUT = 300_000;

test.setTimeout(TIMEOUT);

test('Last-Event-ID reconnect survives a real mid-stream network cut', async ({ page, request }) => {
  test.skip(!REAL, 'REAL_WEBUI_E2E=1 is required');

  const proxy = await startRealProxy(BACKEND);
  try {
    proxy.cutNextStreamOnce();
    await unlockChat(page, `${proxy.baseURL}/webui/chat`);

    const streamRequests: string[] = [];
    page.on('request', request => {
      if (request.url().includes('/api/v1/rag/chat/stream')
        && request.method() === 'POST') {
        streamRequests.push(request.headers()['last-event-id'] ?? '');
      }
    });

    const first = page.waitForRequest(request =>
      request.url().includes('/api/v1/rag/chat/stream')
      && request.method() === 'POST');
    const resumed = page.waitForRequest(request =>
      request.url().includes('/api/v1/rag/chat/stream')
      && request.method() === 'POST'
      && Boolean(request.headers()['last-event-id']));

    // 唯一 codename 避免语义缓存命中：缓存回放可能只发一个 done 帧，
    // 就绕过了"流式中断后续传"这条链路。
    const marker = `RECONNECT-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    await page.locator('textarea').fill(
      `Repeat this codename exactly twice, separated by a comma: ${marker}`,
    );
    await page.getByRole('button', { name: 'Send', exact: true }).click();

    await first;
    await resumed;

    expect(streamRequests.length, '断流后必须发生一次续传请求').toBeGreaterThanOrEqual(2);
    const secondIndex = streamRequests.findIndex((id, index) =>
      index > 0 && id !== '');
    expect(secondIndex, '第二次请求必须携带 Last-Event-ID').toBeGreaterThan(0);
    expect(streamRequests[secondIndex]).toMatch(/\d/);

    await expect.poll(
      () => new URL(page.url()).pathname,
      { timeout: TIMEOUT },
    ).toMatch(/^\/webui\/chat\/[^/]+$/);
    await expect(page.locator('textarea')).toBeEnabled({ timeout: TIMEOUT });
    await expect(page.getByText(/^Error:/)).toHaveCount(0);

    const turnId = new URL(page.url()).pathname.split('/').pop();
    expect(turnId).toBeTruthy();
    const captured = await page.evaluate(async () => {
      const captures = (window as unknown as {
        chatAcceptanceBodies?: Promise<string>[];
      }).chatAcceptanceBodies ?? [];
      const settled = await Promise.allSettled(captures);
      return settled.map(result => result.status === 'fulfilled' ? result.value : '');
    });
    const resumedBody = captured.join('\n');
    expect(resumedBody, '续传响应必须包含 done 终态').toMatch(/event:\s*done/);

    // 断流前的内容已经通过第一次响应到达浏览器，但被切断的 fetch body
    // 在浏览器侧无法完整 clone。真实证据来自服务端落库：同一轮对话在
    // 续传后必须持久化出非空回答，并且 UI 上能看到这段文本。
    await expect.poll(async () => {
      const history = await apiJson<ChatHistoryRecord[]>(
        request, 'GET', `/chat/history/${encodeURIComponent(turnId!)}`,
      );
      return history.find(candidate => candidate.status === 'COMPLETE')?.aiResponse ?? '';
    }, { timeout: TIMEOUT, message: '续传后必须落库并产出非空回答' }).not.toBe('');
    const history = await apiJson<ChatHistoryRecord[]>(
      request, 'GET', `/chat/history/${encodeURIComponent(turnId!)}`,
    );
    const record = history.find(candidate => candidate.status === 'COMPLETE');
    expect(record?.aiResponse.trim(), '续传后必须落库并产出非空回答').toBeTruthy();
    await expect(page.getByText(record!.aiResponse, { exact: true })).toBeVisible();
  } finally {
    await proxy.close();
  }
});
