#!/usr/bin/env node
// Self-test for `scripts/verify-list-endpoint-pagination.mjs`.
//
// 门禁的核心断言是"新的列表端点不能悄悄绕过分页/排序契约"。这里的做法是
// 直接运行门禁内置的 fixture 用例：未登记端点、超上限、排序证据缺失都必须
// 被拒绝，合法契约必须放行。内置用例跑通即证明门禁仍能拒绝，而不是只在
// 干净仓库上碰巧通过。

import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const gate = path.join(repoRoot, 'scripts/verify-list-endpoint-pagination.mjs');

execFileSync(process.execPath, [gate, '--self-test'], { stdio: 'inherit' });
