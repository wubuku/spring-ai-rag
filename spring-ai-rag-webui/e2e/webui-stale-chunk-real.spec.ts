import { expect, test } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { rootHeaders } from './support/chat-lifecycle';

const REAL = process.env.REAL_WEBUI_E2E === '1';
const STATIC_ROOT = process.env.REAL_WEBUI_STATIC_DIR
  ?? path.resolve(process.cwd(), '../spring-ai-rag-core/target/classes/static/webui');
const TIMEOUT = 120_000;

test.setTimeout(TIMEOUT);

/**
 * Simulates a deployment that removed a lazy route chunk while an old tab is
 * still open. The browser is already running the packaged build; only the
 * referenced file disappears from the served classpath, exactly as a hash
 * rotation does in production.
 */
test('a missing lazy chunk triggers the refresh-now banner', async ({ page }) => {
  test.skip(!REAL, 'REAL_WEBUI_E2E=1 is required');
  test.skip(!fs.existsSync(path.join(STATIC_ROOT, 'index.html')),
    `packaged WebUI not found at ${STATIC_ROOT}`);

  const assets = fs.readdirSync(path.join(STATIC_ROOT, 'assets'));
  const documentsChunk = assets.find(name => /^Documents-[^/]+\.js$/.test(name));
  test.skip(!documentsChunk, 'packaged build has no lazy Documents chunk');

  const chunkPath = path.join(STATIC_ROOT, 'assets', documentsChunk!);
  const stashPath = `${chunkPath}.stale-test`;
  if (fs.existsSync(stashPath)) fs.rmSync(stashPath);

  try {
    await page.goto('/webui/chat', { waitUntil: 'networkidle' });
    if (new URL(page.url()).pathname === '/webui/unlock') {
      await page.getByTestId('root-api-key').fill(rootHeaders()['X-API-Key']);
      await page.getByRole('button', { name: 'Unlock', exact: true }).click();
    }
    await expect(page.getByRole('heading', { name: 'Chat', exact: true }))
      .toBeVisible();

    fs.renameSync(chunkPath, stashPath);

    const chunkResponse = page.waitForResponse(response =>
      response.url().includes(documentsChunk!)
      && response.status() === 404,
      { timeout: 30_000 },
    );
    await page.getByRole('link', { name: /Documents/ }).click();
    await chunkResponse;

    const banner = page.getByRole('status').filter({
      hasText: /A new version is available/,
    });
    await expect(banner, '缺失 chunk 的 404 必须立即提示刷新').toBeVisible();
    await expect(banner.getByRole('button', { name: 'Refresh' })).toBeVisible();
  } finally {
    if (fs.existsSync(stashPath)) {
      fs.renameSync(stashPath, chunkPath);
    }
  }
});
