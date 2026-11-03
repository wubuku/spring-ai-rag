#!/usr/bin/env node
/**
 * OpenAPI 破坏性变更门禁。
 *
 * 用法：
 *   node scripts/verify-openapi-breaking-change.mjs --current /tmp/openapi.json
 *   node scripts/verify-openapi-breaking-change.mjs --url http://127.0.0.1:18090/v3/api-docs
 *   node scripts/verify-openapi-breaking-change.mjs --update --current /tmp/openapi.json
 *   node scripts/verify-openapi-breaking-change.mjs --self-test
 *
 * 基线：testdata/api-contract/openapi-baseline.json（springdoc 原始输出）。
 * 退出码 0 表示无破坏性变更；1 表示存在破坏或基线缺失。
 */

import { readFileSync } from 'node:fs';
import process from 'node:process';
import { compareSpecs } from './lib/openapi-breaking-change.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

const DEFAULT_BASELINE = new URL('../testdata/api-contract/openapi-baseline.json', import.meta.url);

async function loadJson(source) {
  if (source.startsWith('http://') || source.startsWith('https://')) {
    const response = await fetch(source);
    if (!response.ok) throw new Error(`无法获取 ${source}：HTTP ${response.status}`);
    return response.json();
  }
  return JSON.parse(readFileSync(source, 'utf8'));
}

function parseArgs(argv) {
  const options = { baseline: DEFAULT_BASELINE.pathname, current: null, url: null, update: false, selfTest: false };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--baseline') options.baseline = argv[++index];
    else if (arg === '--current') options.current = argv[++index];
    else if (arg === '--url') options.url = argv[++index];
    else if (arg === '--update') options.update = true;
    else if (arg === '--self-test') options.selfTest = true;
    else throw new Error(`未知参数：${arg}`);
  }
  return options;
}

function selfTest() {
  const baseline = {
    paths: {
      '/items': {
        get: {
          parameters: [
            { name: 'limit', in: 'query', required: false, schema: { type: 'integer' } },
            { name: 'mode', in: 'query', schema: { type: 'string', enum: ['fast', 'safe'] } },
          ],
          responses: {
            200: {
              content: {
                'application/json': {
                  schema: { $ref: '#/components/schemas/ItemPage' },
                },
              },
            },
          },
        },
        post: {
          requestBody: {
            required: true,
            content: { 'application/json': { schema: { $ref: '#/components/schemas/Item' } } },
          },
          responses: { 200: { content: { 'application/json': { schema: { $ref: '#/components/schemas/Item' } } } } },
        },
      },
    },
    components: {
      schemas: {
        ItemPage: {
          type: 'object',
          required: ['items'],
          properties: {
            items: { type: 'array', items: { $ref: '#/components/schemas/Item' } },
            total: { type: 'integer' },
          },
        },
        Item: {
          type: 'object',
          required: ['id', 'name'],
          properties: {
            id: { type: 'integer' },
            name: { type: 'string' },
            description: { type: 'string' },
          },
        },
      },
    },
  };
  const clone = (value) => JSON.parse(JSON.stringify(value));
  const cases = [];

  const addedOptional = clone(baseline);
  addedOptional.components.schemas.Item.properties.note = { type: 'string' };
  cases.push(['新增可选字段不算破坏', addedOptional, false]);

  const removedField = clone(baseline);
  delete removedField.components.schemas.Item.properties.description;
  cases.push(['删除字段算破坏', removedField, true]);

  const typeChange = clone(baseline);
  typeChange.components.schemas.Item.properties.id.type = 'string';
  cases.push(['改类型算破坏', typeChange, true]);

  const newlyRequired = clone(baseline);
  newlyRequired.components.schemas.Item.required.push('description');
  cases.push(['改必填算破坏', newlyRequired, true]);

  const removedOperation = clone(baseline);
  delete removedOperation.paths['/items'].post;
  cases.push(['删除 operation 算破坏', removedOperation, true]);

  const removedResponse = clone(baseline);
  delete removedResponse.paths['/items'].get.responses[200];
  cases.push(['删除响应码算破坏', removedResponse, true]);

  // 403 → 404：新增 404 是允许的，但删除基线声明过的 403 仍然是破坏。
  const baselineWith403 = clone(baseline);
  baselineWith403.paths['/items'].get.responses[403] = { content: null };
  const replaced403 = clone(baselineWith403);
  delete replaced403.paths['/items'].get.responses[403];
  replaced403.paths['/items'].get.responses[404] = { content: null };
  cases.push(['响应码 403 被 404 替换算破坏', replaced403, true, baselineWith403]);

  const removedEnum = clone(baseline);
  removedEnum.paths['/items'].get.parameters[1].schema.enum = ['fast'];
  cases.push(['删除枚举值算破坏', removedEnum, true]);

  const relaxedParameter = clone(baseline);
  relaxedParameter.paths['/items'].get.parameters[0].required = true;
  cases.push(['参数变必填算破坏', relaxedParameter, true]);

  // 可空化是放宽：原来只接受 integer，现在额外接受 null，旧请求全部仍然合法。
  // swagger-core 2.2.47 把 `@Schema(nullable = true)` 渲染成 type 数组，工具升级
  // 不应被读成契约破坏。
  const widenedNullable = clone(baseline);
  widenedNullable.components.schemas.Item.properties.id.type = ['integer', 'null'];
  cases.push(['类型可空化不算破坏', widenedNullable, false]);

  let failures = 0;
  for (const [label, current, breaking, caseBaseline] of cases) {
    const findings = compareSpecs(caseBaseline ?? baseline, current);
    const ok = breaking ? findings.length > 0 : findings.length === 0;
    console.log(`${ok ? 'PASS' : 'FAIL'} ${label}（findings=${findings.length}）`);
    if (!ok) {
      failures += 1;
      for (const finding of findings) console.log(`  - ${finding.path}: ${finding.message}`);
    }
  }
  return failures === 0;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.selfTest) {
    const ok = selfTest();
    process.exit(ok ? 0 : 1);
  }
  const currentSource = options.current
    ?? options.url
    ?? (() => { throw new Error('必须提供 --current 或 --url'); })();
  const current = await loadJson(currentSource);

  if (options.update) {
    const { writeFileSync } = await import('node:fs');
    writeFileSync(options.baseline, `${JSON.stringify(current, null, 2)}\n`);
    console.log(`已更新基线：${options.baseline}`);
    process.exit(0);
  }

  let baseline;
  try {
    baseline = await loadJson(options.baseline);
  } catch (error) {
    console.error(`找不到可用基线 ${options.baseline}：${error.message}`);
    console.error('先用 --update 生成基线并提交。');
    process.exit(1);
  }

  const findings = compareSpecs(baseline, current);
  if (findings.length === 0) {
    console.log('PASS OpenAPI 无破坏性变更');
    process.exit(0);
  }
  console.error(`FAIL 发现 ${findings.length} 项破坏性变更：`);
  for (const finding of findings) {
    console.error(`  - ${finding.path}: ${finding.message}`);
  }
  process.exit(1);
}

if (isMainModule(import.meta.url)) {
  main().catch((error) => {
    console.error(`ERROR ${error.message}`);
    process.exit(1);
  });
}
