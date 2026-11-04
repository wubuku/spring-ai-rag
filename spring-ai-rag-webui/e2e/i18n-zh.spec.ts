import { expect, test } from '@playwright/test';
import { mockAllApiCalls } from './api-mocks';
import {
  expectChineseDate,
  expectChineseUi,
  openChinesePage,
  useChineseUi,
} from './support/chinese-ui';

const AGENT_TURN_ID = '55555555-5555-4555-8555-555555555555';
const PAGE_SIZE = 20;
const TOTAL_DOCUMENTS = 1_234;

function chineseDocuments(offset: number) {
  return Array.from({ length: Math.min(PAGE_SIZE, TOTAL_DOCUMENTS - offset) }, (_, index) => {
    const id = 1000 + offset + index;
    return {
      id,
      title: `中文文档 ${offset + index + 1}`,
      content: `第 ${offset + index + 1} 份文档`,
      source: 'webui',
      contentHash: `hash-${id}`,
      documentType: 'TEXT',
      metadata: {},
      createdAt: '2026-08-19T01:00:00Z',
      updatedAt: '2026-08-19T01:00:00Z',
      collectionId: 1,
      collectionKey: 'sample-collection',
      collectionName: '示例集合',
      chunkCount: 1,
      processingStatus: 'COMPLETED',
      embeddingFresh: true,
      enabled: true,
    };
  });
}

test.describe('中文界面（zh-CN）', () => {
  test('仪表盘导航与绝对时间按中文习惯渲染', async ({ page }) => {
    await mockAllApiCalls(page);
    await useChineseUi(page);
    await openChinesePage(page, '/webui/dashboard');

    await expect(page.getByRole('heading', { name: '仪表盘' })).toBeVisible();
    for (const name of ['文档', '知识库', '对话', '检索', '设置']) {
      await expect(page.getByRole('link', { name, exact: true })).toBeVisible();
    }
    await expect(page.getByText('最后检查', { exact: true })).toBeVisible();
    // 后端 mock 固定为 2026-08-27T12:00:00Z；中文格式是 2026/8/27 或 2026年8月27日，
    // 英文格式才会出现 Aug 27, 2026 / 8:00:00 PM。
    await expectChineseDate(page, 2026, 8, 27);
    await expect(page.locator('body')).not.toContainText('Aug 27');
    await expectChineseUi(page);
  });

  test('文档列表、分页、数字与上传提示均为中文', async ({ page }) => {
    await mockAllApiCalls(page);
    await useChineseUi(page);
    await page.route(/\/api\/v1\/rag\/documents(?:\?.*)?$/u, async route => {
      const offset = Number(new URL(route.request().url()).searchParams.get('offset') ?? 0);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          documents: chineseDocuments(offset),
          total: TOTAL_DOCUMENTS,
          offset,
          limit: PAGE_SIZE,
        }),
      });
    });

    await openChinesePage(page, '/webui/documents?page=1');

    await expect(page.getByRole('heading', { name: '文档管理' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '文档ID' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '创建时间' })).toBeVisible();
    await expect(page.getByText('拖拽文件到此处或点击上传（TXT、PDF、MD）')).toBeVisible();
    await expect(page.getByText(/第 2 页 — 文档总数: 1,234/u)).toBeVisible();
    await expect(page.getByRole('button', { name: '上一页' })).toBeEnabled();
    await expect(page.getByRole('button', { name: '下一页' })).toBeEnabled();
    await expect(page.getByText('中文文档 21', { exact: true })).toBeVisible();
    await expectChineseDate(page, 2026, 8, 19);
    await expectChineseUi(page);
  });

  test('搜索页输入、空结果与提示文案均为中文', async ({ page }) => {
    await mockAllApiCalls(page);
    await useChineseUi(page);
    await page.route(/\/api\/v1\/rag\/search(?:\?.*)?$/u, route => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ query: '测试查询', total: 0, results: [] }),
      });
    });
    await openChinesePage(page, '/webui/search');

    await expect(page.getByRole('heading', { name: '文档检索' })).toBeVisible();
    await expect(page.getByLabel('搜索关键词')).toHaveAttribute('placeholder', '搜索文档内容…');
    await expect(page.getByRole('button', { name: '搜索', exact: true })).toBeVisible();

    await page.getByLabel('搜索关键词').fill('测试查询');
    await page.getByRole('button', { name: '搜索', exact: true }).click();

    await expect(page.getByText(/未找到与“测试查询”匹配的结果/u)).toBeVisible();
    await expect(page.getByText('换个关键词或调整检索条件再试。')).toBeVisible();
    await expectChineseUi(page);
  });

  test('搜索错误态显示中文失败分类与重试入口', async ({ page }) => {
    await mockAllApiCalls(page);
    await useChineseUi(page);
    await page.route(/\/api\/v1\/rag\/search(?:\?.*)?$/u, route => {
      route.fulfill({
        status: 400,
        contentType: 'application/problem+json',
        body: JSON.stringify({ code: 'INVALID_QUERY', message: 'bad query' }),
      });
    });
    await openChinesePage(page, '/webui/search');

    await page.getByLabel('搜索关键词').fill('会失败的查询');
    await page.getByRole('button', { name: '搜索', exact: true }).click();

    await expect(page.getByText('服务端拒绝了这次请求，请检查输入后重试。')).toBeVisible();
    await expect(page.getByRole('button', { name: '重试' })).toBeVisible();
    await expectChineseUi(page);
  });

  test('对话页 SSE 流式事件与相对时间均为中文', async ({ page }) => {
    await mockAllApiCalls(page);
    await useChineseUi(page, {
      chatSessions: [
        { id: 'recent-session', title: '最近的会话', minutesAgo: 5 },
        { id: 'older-session', title: '较早的会话', minutesAgo: 120 },
      ],
    });
    await page.route('/api/v1/rag/chat/stream', route => {
      route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'X-RAG-Turn-Id': AGENT_TURN_ID },
        body: [
          'event: tool_start',
          'data: {"tool":"searchKnowledge","query":"风格基调"}',
          '',
          'event: tool_result',
          'data: {"tool":"searchKnowledge","resultCount":2,"elapsedMs":8}',
          '',
          'event: content',
          'data: {"content":"根据知识库，风格基调是克制、清晰。"}',
          '',
          'event: sources',
          'data: {"sessionId":"agent-session-zh","sources":[{"citationId":"S1","documentId":7,"title":"品牌风格指南","collectionKey":"product-manual","documentType":"TEXT"}]}',
          '',
          'event: done',
          `data: {"sessionId":"agent-session-zh","status":"complete","turnId":"${AGENT_TURN_ID}","mode":"AGENT","resolvedModel":"minimax/MiniMax-M2.7"}`,
          '',
        ].join('\n'),
      });
    });

    await openChinesePage(page, '/webui/chat');

    await expect(page.getByRole('heading', { name: 'RAG 对话' })).toBeVisible();
    await expect(page.getByText('暂无消息，开始对话吧！')).toBeVisible();
    await expect(page.locator('textarea')).toHaveAttribute(
      'placeholder',
      '输入问题，开始检索增强对话…',
    );
    await expect(page.getByRole('button', { name: '发送' })).toBeDisabled();
    await page.getByRole('button', { name: '历史记录' }).click();
    await expect(page.getByText('5分钟前', { exact: true })).toBeVisible();
    await expect(page.getByText('2小时前', { exact: true })).toBeVisible();

    await page.locator('textarea').fill('知识库讲了什么？');
    await expect(page.getByRole('button', { name: '发送' })).toBeEnabled();
    await page.getByRole('button', { name: '发送' }).click();

    await expect(page.getByText('检索过程')).toBeVisible();
    await expect(page.getByText(/已找到 2 条结果（8 ms）/u)).toBeVisible();
    await expect(page.getByText('根据知识库，风格基调是克制、清晰。')).toBeVisible();
    await expect(page.getByText('参考资料')).toBeVisible();
    await expect(page.getByText('品牌风格指南')).toBeVisible();
    await expectChineseUi(page);
  });
});
