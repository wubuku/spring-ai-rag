import { expect, test, type Page } from '@playwright/test';
import {
  MOCK_CHAT_TURN_ID,
  mockAllApiCalls,
  openProtectedPage,
} from './api-mocks';

/**
 * SSE 断线续传的真实验收。
 *
 * 服务端的 keyed 快照流给每条事件发 `id:`（= sequence），并按
 * `Last-Event-ID` 跳过已投递事件。Playwright 的 `route.fulfill` 只能一次性
 * 返回整段 body，无法在指定事件后掐断连接，所以这里在页面里包一层 `window.fetch`，
 * 用可控的 `ReadableStream` 复刻"发完 content 就断网"的时序。这样跑的仍然是
 * 浏览器里真实的 useChatSSE / Chat 页面代码，只有传输层是合成的。
 */

type AttemptPlan = {
  /** 本次尝试要投递的完整 SSE 帧。 */
  frames: string[];
  /** 投递完帧后让流以网络错误中止（模拟断线）。 */
  failAfter?: boolean;
};

type SseAttempt = {
  lastEventId: string | null;
  idempotencyKey: string | null;
};

const contentFrame = (text: string) => sseFrame(1, 'content', {
  sequence: 1,
  choices: [{ delta: { content: text } }],
});

const sourcesFrame = () => sseFrame(2, 'sources', {
  sequence: 2,
  sessionId: 'resume-session',
  sources: [{
    citationId: 'S1',
    documentId: 1,
    title: 'RAG Guide',
    collectionKey: 'sample-collection',
    documentType: 'TEXT',
  }],
});

const doneFrame = (
  status: 'complete' | 'incomplete',
  id = 3,
) => sseFrame(id, 'done', {
  sequence: id,
  sessionId: 'resume-session',
  status,
  turnId: MOCK_CHAT_TURN_ID,
  mode: 'KNOWLEDGE',
});

function sseFrame(
  id: number,
  event: string,
  data: Record<string, unknown>,
): string {
  return `id:${id}\nevent:${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

async function installControlledSse(
  page: Page,
  plans: AttemptPlan[],
): Promise<void> {
  await page.addInitScript(({ plans: attemptPlans, turnId }) => {
    const probe = window as unknown as {
      __sseAttempts: Array<{
        lastEventId: string | null;
        idempotencyKey: string | null;
      }>;
    };
    probe.__sseAttempts = [];
    const originalFetch = window.fetch.bind(window);
    let attemptIndex = 0;

    window.fetch = async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ): Promise<Response> => {
      const url = typeof input === 'string'
        ? input
        : input instanceof URL ? input.href : input.url;
      if (!url.includes('/chat/stream')) {
        return originalFetch(input, init);
      }

      const headers = new Headers(init?.headers ?? {});
      probe.__sseAttempts.push({
        lastEventId: headers.get('Last-Event-ID'),
        idempotencyKey: headers.get('Idempotency-Key'),
      });

      // 超出计划的请求继续复用最后一份计划，这样"重连次数有界"的用例
      // 能在第 3 次请求发生时立刻看出来，而不是悄悄成功。
      const plan = attemptPlans[Math.min(attemptIndex, attemptPlans.length - 1)];
      attemptIndex += 1;

      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          for (const frame of plan.frames) {
            controller.enqueue(new TextEncoder().encode(frame));
          }
          if (plan.failAfter) {
            // 先让已入队的帧被读走，再以网络错误结束，复刻"收到一半就断线"。
            setTimeout(() => controller.error(new Error('simulated network cut')), 50);
          } else {
            controller.close();
          }
        },
      });

      return new Response(body, {
        status: 200,
        headers: {
          'Content-Type': 'text/event-stream',
          'X-RAG-Turn-Id': turnId,
        },
      });
    };
  }, { plans, turnId: MOCK_CHAT_TURN_ID });
}

async function readAttempts(page: Page): Promise<SseAttempt[]> {
  return page.evaluate(() =>
    (window as unknown as { __sseAttempts: SseAttempt[] }).__sseAttempts);
}

async function sendChat(page: Page, message: string): Promise<void> {
  await page.locator('textarea').fill(message);
  await page.getByRole('button', { name: 'Send' }).click();
}

test.describe('SSE resume after disconnection', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiCalls(page);
  });

  test('resumes after content with Last-Event-ID and fills only the gap', async ({ page }) => {
    await installControlledSse(page, [
      { frames: [contentFrame('Complete stored answer')], failAfter: true },
      { frames: [sourcesFrame(), doneFrame('complete')] },
    ]);
    await openProtectedPage(page, '/webui/chat');
    await sendChat(page, 'Resume after the first event');

    // 正文只在第一次尝试投递；续传补 sources 与 done，页面必须保留正文。
    await expect(page.getByText('Complete stored answer', { exact: true }))
      .toHaveCount(1);
    await expect(page.getByText('Sources:')).toBeVisible();
    await expect(page.getByText('RAG Guide')).toBeVisible();
    // 终态 complete 生效：不再显示停止按钮，也没有错误横幅。
    await expect(page.getByRole('button', { name: 'Send' })).toBeVisible();

    const attempts = await readAttempts(page);
    expect(attempts).toHaveLength(2);
    // 第一条请求没有断点；重试必须精确带上已投递事件的 id。
    expect(attempts[0].lastEventId).toBeNull();
    expect(attempts[1].lastEventId).toBe('1');
    // 续传沿用同一轮 turn，才能命中服务端的快照重放。
    expect(attempts[1].idempotencyKey).toBe(attempts[0].idempotencyKey);
  });

  test('reconnects without a cursor when the first attempt delivered nothing', async ({ page }) => {
    await installControlledSse(page, [
      { frames: [], failAfter: true },
      { frames: [contentFrame('Recovered answer'), sourcesFrame(), doneFrame('complete')] },
    ]);
    await openProtectedPage(page, '/webui/chat');
    await sendChat(page, 'Reconnect before any event');

    await expect(page.getByText('Recovered answer', { exact: true })).toHaveCount(1);
    const attempts = await readAttempts(page);
    expect(attempts).toHaveLength(2);
    expect(attempts[1].lastEventId).toBeNull();
  });

  test('drops events the server re-sends during resume', async ({ page }) => {
    // 非 compliant 的中间层（或配置漂移）可能在续传时重放已投递事件。
    // 客户端必须按 sequence 丢弃重复 content，页面不能出现两条回答。
    await installControlledSse(page, [
      { frames: [contentFrame('Only once')], failAfter: true },
      { frames: [contentFrame('Only once'), sourcesFrame(), doneFrame('complete')] },
    ]);
    await openProtectedPage(page, '/webui/chat');
    await sendChat(page, 'Resume with a duplicate');

    await expect(page.getByText('Only once', { exact: true })).toHaveCount(1);
    const attempts = await readAttempts(page);
    expect(attempts).toHaveLength(2);
  });

  test('keeps an incomplete terminal incomplete across resume', async ({ page }) => {
    await installControlledSse(page, [
      { frames: [contentFrame('Partial answer')], failAfter: true },
      { frames: [doneFrame('incomplete', 2)] },
    ]);
    await openProtectedPage(page, '/webui/chat');
    await sendChat(page, 'Resume into an incomplete turn');

    // incomplete 是终态：不能因为重连就升级成 complete，也不能再触发第三次请求。
    await expect(page.getByText('Incomplete')).toBeVisible();
    await expect(page.getByText('Partial answer', { exact: true })).toHaveCount(1);
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
    const attempts = await readAttempts(page);
    expect(attempts).toHaveLength(2);
  });

  test('bounds reconnect attempts when the resumed stream also truncates', async ({ page }) => {
    await installControlledSse(page, [
      { frames: [contentFrame('Partial answer')], failAfter: true },
      { frames: [] },
    ]);
    await openProtectedPage(page, '/webui/chat');
    await sendChat(page, 'Exhaust the retry budget');

    await expect(page.getByRole('alert')
      .getByText('The answer stream stopped before it finished.'))
      .toBeVisible();
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();

    const attempts = await readAttempts(page);
    expect(attempts).toHaveLength(2);
    // 停止后不自旋：再等一段时间，仍不应出现第三次自动请求。
    await page.waitForTimeout(1_000);
    expect(await readAttempts(page)).toHaveLength(2);
  });
});
