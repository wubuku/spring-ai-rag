#!/usr/bin/env node
// Self-test for `scripts/verify-write-endpoint-idempotency.mjs`.
//
// 门禁的核心断言是"新增写端点不能悄悄绕开幂等语义登记"。这里直接运行门禁
// 内置的 fixture 用例：未登记端点、HTTP 方法/路径漂移、证据失效、声明了
// Idempotency-Key 却登记为只读，都必须被拒绝；合法登记必须放行。

import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const gate = path.join(repoRoot, 'scripts/verify-write-endpoint-idempotency.mjs');

execFileSync(process.execPath, [gate, '--self-test'], { stdio: 'inherit' });
