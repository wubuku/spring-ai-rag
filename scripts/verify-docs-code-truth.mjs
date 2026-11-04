// 文档-代码真相对账门禁的入口：把仓库内容读出来，交给 lib 里的规则判定。
//
// 用法：
//   node scripts/verify-docs-code-truth.mjs
//   node scripts/verify-docs-code-truth.mjs --self-test
//
// 覆盖的事实：迁移版本范围、端口、默认 profile、向量维度、脚本名、
// npm 脚本名、REST 端点片段、rag.* 配置键、模块目录。

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import {
  findMissingDirectories,
  findMissingEndpoints,
  findMissingFacts,
  findMissingNpmScripts,
  findMissingScripts,
  findStaleFlywayRanges,
  findUnboundConfigKeys,
  latestMigrationNumber,
  runSelfTest,
} from './lib/docs-code-truth-check.mjs';

const root = path.resolve(import.meta.dirname, '..');
const read = (file) => fs.readFileSync(path.join(root, file), 'utf8');

if (process.argv.includes('--self-test')) {
  try {
    runSelfTest();
    process.exit(0);
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}

const tracked = (pattern) => execFileSync('git', ['ls-files', pattern], {
  cwd: root,
  encoding: 'utf8',
}).split('\n').filter(Boolean);

/** 可判定事实的正式文档：drafts 与按日期命名的历史报告不参与“当前状态”判断。 */
const formalDocs = tracked('*.md')
  .filter((file) => !file.startsWith('docs/drafts/'))
  .filter((file) => !file.startsWith('CHANGELOG'))
  .filter((file) => !/^docs\/\d{4}-\d{2}-\d{2}_/.test(file));

const docs = formalDocs.map((file) => ({ file, text: read(file) }));

/** 解析 application.yml：真实嵌套键集合 + 标量值（含 ${VAR:default} 的默认值）。 */
function parseYaml(file) {
  const keys = new Set();
  const values = new Map();
  const stack = [];
  for (const raw of read(file).split('\n')) {
    const trimmed = raw.trim();
    if (!trimmed || trimmed.startsWith('#') || trimmed.startsWith('-')) continue;
    const indent = raw.match(/^ */)[0].length;
    const match = /^ *([A-Za-z0-9_.-]+):(?:\s*(.*))?$/.exec(raw);
    if (!match) continue;
    while (stack.length > 0 && stack[stack.length - 1].indent >= indent) stack.pop();
    stack.push({ indent, name: match[1] });
    const key = stack.map((item) => item.name).join('.');
    keys.add(key);
    if (match[2] === undefined || match[2] === '') continue;
    const rawValue = match[2].replace(/\s+#.*$/, '').trim();
    const envDefault = /\$\{[A-Z0-9_]+:([^}]*)\}/.exec(rawValue);
    values.set(key, (envDefault ? envDefault[1] : rawValue).replace(/^["']|["']$/g, ''));
  }
  return { keys, values };
}

const yaml = parseYaml('spring-ai-rag-core/src/main/resources/application.yml');

const devSh = read('scripts/dev.sh');
const realE2eSh = read('scripts/start-real-e2e-server.sh');
const firstMatch = (text, pattern) => {
  const match = pattern.exec(text);
  return match ? match[1] : null;
};

const facts = [
  { name: '默认端口', value: yaml.values.get('server.port') },
  {
    name: '一键开发栈端口',
    value: firstMatch(devSh, /DEFAULT_BACKEND_PORT="(\d+)"/),
  },
  {
    name: '真实联调端口',
    value: firstMatch(realE2eSh, /SERVER_PORT="\$\{SERVER_PORT:-(\d+)\}"/),
  },
  {
    name: '默认 profile',
    value: firstMatch(devSh, /CALLER_PROFILE:-(postgresql)\}/),
  },
  { name: '向量维度', value: yaml.values.get('rag.embedding.dimensions') },
];

const javaSources = tracked('*.java').map((file) => ({ file, text: read(file) }));
const javaText = javaSources.map((source) => source.text).join('\n');

const violations = [
  ...findStaleFlywayRanges({
    docs,
    latest: latestMigrationNumber(tracked(
      'spring-ai-rag-core/src/main/resources/db/migration/V*__*.sql',
    ).map((file) => path.basename(file))),
  }).map((item) => `迁移范围过期 ${item.file}:${item.line}（写的是 V${item.stated}）`),
  ...findMissingFacts({ facts, docs }),
  ...findMissingScripts({
    docs,
    // WebUI 文档里的 scripts/* 是相对 spring-ai-rag-webui/ 的；`.github/scripts/`
    // 保持原路径。三种写法都按真实位置核对。
    scriptExists: (script) => fs.existsSync(path.join(root, script))
      || fs.existsSync(path.join(root, 'spring-ai-rag-webui', script)),
  }),
  ...findMissingNpmScripts({
    docs,
    npmScripts: new Set(Object.keys(
      JSON.parse(read('spring-ai-rag-webui/package.json')).scripts,
    )),
  }),
  ...findMissingEndpoints({
    docs: ['docs/rest-api.md', 'docs/rest-api-zh-CN.md'].map((file) => ({ file, text: read(file) })),
    javaText,
  }),
  ...(() => {
    const keys = new Set();
    for (const file of ['docs/configuration.md', 'docs/configuration-zh-CN.md']) {
      for (const match of read(file).matchAll(/`(rag\.[a-z0-9_.-]+)`/g)) keys.add(match[1]);
    }
    return findUnboundConfigKeys({
      keys: [...keys],
      yamlKeys: yaml.keys,
      yamlText: read('spring-ai-rag-core/src/main/resources/application.yml'),
      javaSources,
    }).map((key) => `配置键无法绑定 ${key}`);
  })(),
  ...findMissingDirectories({
    docs: ['docs/index.md', 'docs/index-zh-CN.md'].map((file) => ({ file, text: read(file) })),
    directoryExists: (dir) => fs.existsSync(path.join(root, dir)),
  }),
];

if (violations.length > 0) {
  console.error(`文档与代码存在 ${violations.length} 处漂移：`);
  for (const violation of violations) console.error(`  - ${violation}`);
  process.exit(1);
}

console.log('文档与代码事实一致（迁移范围/端口/profile/维度/脚本/端点/配置键/目录）');
