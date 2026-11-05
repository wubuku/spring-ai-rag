// 文档-代码真相对账的纯逻辑部分。
//
// 为什么要机器化
// -------------
// 这份仓库的事实（迁移范围、端口、profile、脚本名、配置键、端点路径）在
// 文档里被复制了很多份。复制就会漂移，而漂移后的文档比没有文档更危险：
// 读者会照着已不存在的脚本或已改名的配置键操作。
//
// 这里只放“可判定的规则”，不读磁盘：入口脚本把仓库内容收集成参数传进来，
// 自检则用构造出来的小型输入证明每条规则既能放行也能拒绝。

/** 历史叙述行：记录当时发生的事实，不应随当前迁移号变化。 */
const HISTORICAL_MARKERS = /Batch|批次|历史|historical|当时|at the time|旧版|previous/;

/** 必须声明当前迁移范围、端口、维度、profile 的权威文档。 */
export const FACT_DOCS = [
  'AGENTS.md',
  'docs/developer-reference.md',
  'docs/developer-reference-zh-CN.md',
];

/**
 * 从迁移文件名里取最新版本号。
 * @param {string[]} names 形如 V71__xxx.sql 的文件名
 * @returns {number|null}
 */
export function latestMigrationNumber(names) {
  let latest = null;
  for (const name of names) {
    const match = /^V(\d+)__.+\.sql$/.exec(name);
    if (!match) continue;
    const version = Number(match[1]);
    if (latest === null || version > latest) latest = version;
  }
  return latest;
}

/**
 * 文档里出现的当前 Flyway 范围必须等于仓库实际的最新版本。
 * 历史叙述行（`Batch 9xx`、`当时`）被跳过：它们记录的是过去的事实。
 * @returns {{file: string, line: number, stated: number}[]}
 */
export function findStaleFlywayRanges({ docs, latest }) {
  const stale = [];
  for (const { file, text } of docs) {
    text.split('\n').forEach((line, index) => {
      if (!/flyway/i.test(line) || HISTORICAL_MARKERS.test(line)) return;
      const match = /V1[–-]V(\d+)/.exec(line);
      if (!match) return;
      if (Number(match[1]) !== latest) {
        stale.push({ file, line: index + 1, stated: Number(match[1]) });
      }
    });
  }
  return stale;
}

/**
 * 权威文档必须包含每个派生出来的事实。端口、维度、profile 都不允许
 * 在测试里再写一份字面量——所有值都从源码/脚本里解析出来。
 * @param {{name: string, value: string|number}[]} facts
 * @returns {string[]} 缺失说明
 */
export function findMissingFacts({ facts, docs }) {
  const missing = [];
  const textByFile = new Map(docs.map(({ file, text }) => [file, text]));
  for (const file of FACT_DOCS) {
    const text = textByFile.get(file);
    if (text === undefined) {
      missing.push(`${file}: 文档不存在，无法核对事实`);
      continue;
    }
    for (const { name, value } of facts) {
      if (value === null || value === undefined || value === '') {
        missing.push(`${name}: 无法从源码/脚本解析出实际值`);
        continue;
      }
      if (!text.includes(String(value))) missing.push(`${file}: 缺少 ${name}=${value}`);
    }
  }
  return missing;
}

/**
 * 正式文档里提到的 scripts/*.{sh,py,mjs} 必须真实存在。
 * @returns {string[]}
 */
export function findMissingScripts({ docs, scriptExists }) {
  const missing = [];
  for (const { file, text } of docs) {
    // `./scripts/foo.sh` 与 `scripts/foo.sh` 指向同一个文件，边界里要允许 `/`；
    // `.github/scripts/` 是另一套脚本目录，不能被误当成仓库根的 scripts/。
    for (const match of text.matchAll(/(?:^|[\s(`'"[/(])((?:\.github\/)?(?:\.\/)?scripts\/[\w.-]+\.(?:sh|py|mjs))/g)) {
      const script = match[1];
      if (!scriptExists(script)) missing.push(`${file}: ${script}`);
    }
  }
  return [...new Set(missing)];
}

/**
 * 文档里写的 `npm run x` 必须在 WebUI 的 package.json 里存在。
 * @returns {string[]}
 */
export function findMissingNpmScripts({ docs, npmScripts }) {
  const missing = [];
  for (const { file, text } of docs) {
    for (const match of text.matchAll(/npm run ([\w:.-]+)/g)) {
      if (!npmScripts.has(match[1])) missing.push(`${file}: npm run ${match[1]}`);
    }
  }
  return [...new Set(missing)];
}

/**
 * rest-api 文档里反引号包住的 `/api/v1/rag/...` 路径，去掉 `/api/v1/rag`
 * 前缀后的字面片段必须能在 Java 源码里找到。
 *
 * 不直接匹配完整路径，是因为控制器常把 `/api/v1/rag` 放在类级
 * `@RequestMapping`，方法级注解只有 `/documents/{id}`。比对片段既能抓到
 * 拼写/删除导致的漂移，又不会把合法的拆分注解判成错误。
 * @returns {string[]}
 */
export function findMissingEndpoints({ docs, javaText }) {
  const missing = [];
  for (const { file, text } of docs) {
    for (const match of text.matchAll(/`(\/api\/v1\/rag\/[^`\s]+)`/g)) {
      const path = match[1].replace(/[).,;]+$/, '');
      const suffix = path.replace(/^\/api\/v1\/rag/, '').split('{')[0].replace(/\/$/, '');
      if (!suffix || javaText.includes(suffix)) continue;
      missing.push(`${file}: ${path}`);
    }
  }
  return missing;
}

/** 解析 `@ConfigurationProperties(prefix = "x")`，返回 前缀 → 类名。 */
export function parsePropertyPrefixes(javaSources) {
  const prefixes = new Map();
  for (const { text } of javaSources) {
    const match = /@ConfigurationProperties\(prefix\s*=\s*"([^"]+)"\)[\s\S]{0,400}?\b(?:class|record)\s+(\w+)/.exec(text);
    if (match) prefixes.set(match[1], match[2]);
  }
  return prefixes;
}

const toCamel = (segment) => segment.replace(/-([a-z0-9])/g, (_, c) => c.toUpperCase());

 /**
 * 文档里的 `rag.*` 配置键必须能被绑定到某个属性类的字段上。
 *
 * 判定顺序：
 *   1. application.yml 的真实嵌套键直接命中；
 *   2. 用 @ConfigurationProperties 的前缀定位属性类，逐段把 kebab-case
 *      转成字段名，并在嵌套类之间移动；
 *   3. 最后一段允许出现在该类文件里（校验字符串、Map 键等也算声明）。
 * @returns {string[]}
 */
export function findUnboundConfigKeys({ keys, yamlKeys, yamlText, javaSources }) {
  const prefixes = parsePropertyPrefixes(javaSources);
  // 嵌套属性类常常写在同一个文件里（RagChatProperties 的 StaticKnowledgeProperties
  // 就是 internal class），所以要按声明位置切片，而不是只取文件里的第一个类。
  const classSource = new Map();
  for (const { text } of javaSources) {
    const declarations = [...text.matchAll(/\b(?:class|record)\s+(\w+)/g)];
    declarations.forEach((declaration, index) => {
      const name = declaration[1];
      if (classSource.has(name)) return;
      const end = index + 1 < declarations.length ? declarations[index + 1].index : text.length;
      classSource.set(name, text.slice(declaration.index, end));
    });
  }

  const unbound = [];
  for (const key of keys) {
    if (yamlKeys.has(key) || yamlText.includes(`${key}:`)) continue;

    let matchedPrefix = null;
    for (const prefix of prefixes.keys()) {
      if (!key.startsWith(`${prefix}.`)) continue;
      if (matchedPrefix === null || prefix.length > matchedPrefix.length) matchedPrefix = prefix;
    }

    if (matchedPrefix) {
      let className = prefixes.get(matchedPrefix);
      let source = classSource.get(className);
      const segments = key.slice(matchedPrefix.length + 1).split('.');
      let resolved = source !== undefined;
      for (let i = 0; i < segments.length && resolved; i += 1) {
        const field = toCamel(segments[i]);
        if (i === segments.length - 1) {
          resolved = new RegExp(`\\b${field}\\b`).test(source);
          break;
        }
        const type = new RegExp(`([A-Z]\\w+)\\s+${field}\\s*[;=)]`).exec(source);
        if (type && classSource.has(type[1])) {
          className = type[1];
          source = classSource.get(className);
        } else if (!new RegExp(`\\b${field}\\b`).test(source)) {
          resolved = false;
        }
      }
      if (resolved) continue;
    }

    if (javaSources.some(({ text }) => text.includes(key))) continue;
    unbound.push(key);
  }
  return unbound;
}

/**
 * 文档里引用的模块目录必须存在。
 * @returns {string[]}
 */
export function findMissingDirectories({ docs, directoryExists }) {
  const missing = [];
  for (const { file, text } of docs) {
    for (const match of text.matchAll(/\b(spring-ai-rag-[a-z]+)\//g)) {
      if (!directoryExists(match[1])) missing.push(`${file}: ${match[1]}/`);
    }
  }
  return [...new Set(missing)];
}

/** 自检：每条规则都必须能放行干净输入，也能拒绝漂移输入。 */
export function runSelfTest() {
  const cases = [];
  const expect = (name, actual, expected) => {
    const ok = JSON.stringify(actual) === JSON.stringify(expected);
    cases.push({ name, ok, actual, expected });
  };

  expect('最新迁移号取最大值',
    latestMigrationNumber(['V1__a.sql', 'V73__b.sql', 'V9__c.sql']), 73);
  expect('非迁移文件被忽略', latestMigrationNumber(['notes.sql', 'V2__x.sql']), 2);

  expect('当前范围一致时不报错', findStaleFlywayRanges({
    docs: [{ file: 'AGENTS.md', text: 'Flyway 当前 V1–V74' }],
    latest: 74,
  }), []);
  expect('当前范围漂移时报错', findStaleFlywayRanges({
    docs: [{ file: 'AGENTS.md', text: 'Flyway 当前 V1–V34' }],
    latest: 74,
  }).length, 1);
  expect('历史叙述不报错', findStaleFlywayRanges({
    docs: [{ file: 'docs/testing-guide.md', text: 'Batch 900 当时执行 Flyway V1-V52' }],
    latest: 74,
  }), []);

  expect('事实齐全时通过', findMissingFacts({
    facts: [{ name: '端口', value: 8081 }],
    docs: FACT_DOCS.map((file) => ({ file, text: '端口 8081' })),
  }), []);
  expect('事实缺失时报错', findMissingFacts({
    facts: [{ name: '端口', value: 8081 }],
    docs: FACT_DOCS.map((file) => ({ file, text: '端口 8080' })),
  }).length, FACT_DOCS.length);

  expect('存在的脚本通过', findMissingScripts({
    docs: [{ file: 'a.md', text: '运行 ./scripts/dev.sh' }],
    scriptExists: () => true,
  }), []);
  expect('不存在的脚本报错', findMissingScripts({
    docs: [{ file: 'a.md', text: '运行 ./scripts/missing.sh' }],
    scriptExists: () => false,
  }).length, 1);

  expect('npm 脚本存在时通过', findMissingNpmScripts({
    docs: [{ file: 'a.md', text: 'npm run test:visual' }],
    npmScripts: new Set(['test:visual']),
  }), []);
  expect('npm 脚本缺失时报错', findMissingNpmScripts({
    docs: [{ file: 'a.md', text: 'npm run nope' }],
    npmScripts: new Set(['test:visual']),
  }).length, 1);

  expect('端点片段存在时通过', findMissingEndpoints({
    docs: [{ file: 'r.md', text: '`/api/v1/rag/documents/{id}`' }],
    javaText: '@GetMapping("/documents/{id}")',
  }), []);
  expect('端点片段缺失时报错', findMissingEndpoints({
    docs: [{ file: 'r.md', text: '`/api/v1/rag/documents/{id}`' }],
    javaText: '@GetMapping("/docs/{id}")',
  }).length, 1);

  expect('配置键能绑定时通过', findUnboundConfigKeys({
    keys: ['rag.admission.max-requests'],
    yamlKeys: new Set(),
    yamlText: '',
    javaSources: [{
      text: '@ConfigurationProperties(prefix = "rag")\npublic class RagProperties {\n'
        + '  private final RagAdmissionProperties admission = new RagAdmissionProperties();\n}\n'
        + 'public class RagAdmissionProperties {\n  private int maxRequests = 10;\n}\n',
    }],
  }), []);
  expect('配置键无法绑定时报错', findUnboundConfigKeys({
    keys: ['rag.admission.nope-count'],
    yamlKeys: new Set(),
    yamlText: '',
    javaSources: [{
      text: '@ConfigurationProperties(prefix = "rag")\npublic class RagProperties {\n'
        + '  private final RagAdmissionProperties admission = new RagAdmissionProperties();\n}\n'
        + 'public class RagAdmissionProperties {\n  private int maxRequests = 10;\n}\n',
    }],
  }).length, 1);

  expect('模块目录存在时通过', findMissingDirectories({
    docs: [{ file: 'i.md', text: '`spring-ai-rag-core/`' }],
    directoryExists: () => true,
  }), []);
  expect('模块目录缺失时报错', findMissingDirectories({
    docs: [{ file: 'i.md', text: '`spring-ai-rag-gone/`' }],
    directoryExists: () => false,
  }).length, 1);

  const failed = cases.filter((item) => !item.ok);
  if (failed.length > 0) {
    for (const item of failed) {
      console.error(`FAIL ${item.name}\n  actual:   ${JSON.stringify(item.actual)}`
        + `\n  expected: ${JSON.stringify(item.expected)}`);
    }
    throw new Error(`${failed.length}/${cases.length} 条自检未通过`);
  }
  console.log(`文档-代码真相自检全部通过（${cases.length} 条）`);
}
