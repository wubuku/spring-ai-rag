import { test, expect } from '@playwright/test';
import {
  REQUEST_TIMEOUT, clearPageSession, completedTurn, recoveredHistory, unlockChat,
} from './support/chat-lifecycle';

test.setTimeout(REQUEST_TIMEOUT);

for (const kind of ['text', 'structured', 'filtered'] as const) {
  test(`refusal-handling: ${kind} refusal completes, persists and permits follow-up`, async ({ page, request }) => {
    const control = process.env.RAG_CONTROLLED_PROVIDER_URL;
    if (!control || !/^http:\/\/127\.0\.0\.1:\d+$/.test(control)) {
      throw new Error('Use scripts/verify-chat-outcomes-e2e.py; an explicit loopback controlled provider is required');
    }
    const refusal = 'I cannot provide private account credentials. Please ask about public visitor information.';
    const configure = async (body: Record<string, unknown>) => {
      const response = await request.post(`${control}/control/chat`, { data: body });
      expect(response.ok()).toBe(true);
    };
    const calls = async () => (await (await request.get(`${control}/control/state`)).json()).chat.calls;
    await configure({
      status: 200, delayMs: 0, answerOverride: kind === 'filtered' ? '' : refusal,
      refusalOverride: kind === 'structured' ? refusal : null,
      finishReasonOverride: kind === 'filtered' ? 'content_filter' : null,
    });
    try {
      await unlockChat(page);
      await page.getByTestId('chat-mode-select').selectOption('PLAIN');
      const before = await calls();
      const record = await completedTurn(page, request, `Public visitor information request (${kind}).`, { mode: 'PLAIN' });
      expect(await calls(), '拒答不应触发重试或切模型规避').toBe(before + 1);
      expect(record.aiResponse).toContain(kind === 'filtered' ? '模型因安全策略未回答该请求' : refusal);
      const metadata = record.metadata as Record<string, any>;
      if (kind === 'text') {
        expect(metadata?.answerOutcome?.status).not.toBe('MODEL_SAFETY_REFUSAL');
        expect(metadata?.modelSafetyRefusal).not.toBe(true);
      } else {
        expect(metadata?.answerOutcome?.status).toBe('MODEL_SAFETY_REFUSAL');
        expect(metadata?.modelSafetyRefusal).toBe(true);
      }
      expect(record.sources ?? [], '拒答不得伪造来源').toHaveLength(0);
      await recoveredHistory(page, record);
      expect(await calls(), '刷新历史不得重新调用模型').toBe(before + 1);
      await configure({ answerOverride: 'The public visitor desk is open.', refusalOverride: null, finishReasonOverride: null });
      const followup = await completedTurn(page, request, `Follow-up about the public visitor desk (${kind}).`, { mode: 'PLAIN' });
      expect(followup.sessionId).toBe(record.sessionId);
      expect(followup.aiResponse).toBe('The public visitor desk is open.');
      expect(await calls()).toBe(before + 2);
    } finally {
      await configure({ answerOverride: null, refusalOverride: null, finishReasonOverride: null });
      await clearPageSession(page, request);
    }
  });
}
