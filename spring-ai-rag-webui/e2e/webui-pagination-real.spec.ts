import { expect, test } from '@playwright/test';
import { API_PREFIX, apiJson, rootHeaders } from './support/chat-lifecycle';

const REAL = process.env.REAL_WEBUI_E2E === '1';
const TIMEOUT = 120_000;

test.setTimeout(TIMEOUT);

test('real offset/limit paging shows every document exactly once', async ({
  page,
  request,
}) => {
  test.skip(!REAL, 'REAL_WEBUI_E2E=1 is required');

  const runId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const prefix = `PAGING_REAL_${runId}`;
  const createdIds: number[] = [];

  try {
    for (let index = 0; index < 21; index += 1) {
      const created = await apiJson<{ id: number }>(request, 'POST', '/documents', {
        title: `${prefix}_${String(index).padStart(2, '0')}`,
        content: `Pagination acceptance document ${index} for ${runId}.`,
        embeddingPolicy: 'SKIP',
      });
      createdIds.push(created.id);
    }

    await page.goto(
      `/webui/documents?keyword=${encodeURIComponent(prefix)}`,
      { waitUntil: 'networkidle' },
    );
    if (new URL(page.url()).pathname === '/webui/unlock') {
      await page.getByTestId('root-api-key').fill(rootHeaders()['X-API-Key']);
      await page.getByRole('button', { name: 'Unlock', exact: true }).click();
    }
    await expect(page.getByRole('heading', { name: 'Documents', exact: true }))
      .toBeVisible();

    const rows = page.locator('tbody tr');
    await expect(rows).toHaveCount(20);

    const firstPageTitles = await page.locator('tbody tr td:nth-child(2) button')
      .allInnerTexts();
    expect(firstPageTitles).toHaveLength(20);
    expect(new Set(firstPageTitles).size, '第一页不能有重复行').toBe(20);

    await page.getByRole('button', { name: 'Next', exact: true }).click();
    await expect(page).toHaveURL(/page=1/);
    await expect(rows).toHaveCount(1);
    const secondPageTitles = await page.locator('tbody tr td:nth-child(2) button')
      .allInnerTexts();

    const combined = [...firstPageTitles, ...secondPageTitles];
    expect(new Set(combined).size, '翻页后不能重不漏').toBe(21);
    expect(combined.sort()).toEqual(
      Array.from({ length: 21 }, (_, index) =>
        `${prefix}_${String(index).padStart(2, '0')}`).sort(),
    );
  } finally {
    for (const id of createdIds) {
      await request.delete(`${API_PREFIX}/documents/${id}`, {
        headers: rootHeaders(),
        timeout: TIMEOUT,
      }).catch(() => undefined);
    }
  }
});
