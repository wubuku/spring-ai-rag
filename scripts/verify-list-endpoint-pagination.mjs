#!/usr/bin/env node
/**
 * 列表端点分页一致性门禁。
 *
 * 背景：曾经出现过 offset/limit 与 page/size 换算不一致，导致"第 2 页永远
 * 返回第 1 页"；这类问题只靠修一个实例没有意义。本脚本从 controller 源码
 * 提取所有列表/历史/日志端点，要求每个端点在
 * `scripts/testdata/list-endpoints.json` 中声明：
 *
 *   - 分页模式（offset-limit / page-size / limit / cursor / none）
 *   - 参数名与默认值
 *   - 上限（防止 limit 无穷大）
 *   - 稳定排序（同值时的 tie-break）及证据
 *
 * 出现新的列表端点却没登记、上限超过全局阈值、排序证据消失，都会失败。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CONTROLLER_ROOT = path.join(ROOT, 'spring-ai-rag-core/src/main/java/com/springairag/core/controller');
const MANIFEST_FILE = path.join(ROOT, 'scripts/testdata/list-endpoints.json');

const PAGINATION_MODES = new Set(['offset-limit', 'page-size', 'limit', 'cursor', 'none']);

function fail(message) {
  throw new Error(message);
}

/** 收集目录下的 Java 控制器源码。 */
function readControllerSources(dir) {
  const files = [];
  const walk = current => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('Controller.java')) files.push(full);
    }
  };
  walk(dir);
  return files;
}

/**
 * 把参数列表按顶层逗号切开。注解里可能有逗号/括号，需要跟踪括号与引号深度。
 */
function splitParams(text) {
  const parts = [];
  let depth = 0;
  let quote = null;
  let current = '';
  for (const ch of text) {
    if (quote) {
      current += ch;
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === '\'') {
      quote = ch;
      current += ch;
      continue;
    }
    if ('(<['.includes(ch)) depth++;
    if (')>]'.includes(ch)) depth--;
    if (ch === ',' && depth === 0) {
      parts.push(current.trim());
      current = '';
      continue;
    }
    current += ch;
  }
  if (current.trim()) parts.push(current.trim());
  return parts;
}

/** 从参数声明里取变量名与 default 值。 */
function parseParams(paramText) {
  return splitParams(paramText).map(part => {
    const nameMatch = part.match(/([A-Za-z_$][\w$]*)\s*$/);
    const defaultMatch = part.match(/defaultValue\s*=\s*"([^"]*)"/);
    return {
      raw: part,
      name: nameMatch ? nameMatch[1].replace(/[^A-Za-z0-9_$]/g, '') : '',
      defaultValue: defaultMatch ? defaultMatch[1] : null,
    };
  }).filter(param => param.name);
}

/** 解析单个控制器：类级路径 + 每个 @GetMapping 方法的签名信息。 */
function parseController(source, controllerName) {
  const classPathMatch = source.match(
    /@RequestMapping\s*\(\s*(?:value\s*=\s*)?["']([^"']*)["']\s*\)[\s\S]{0,200}?public\s+class\s+\w+/
  );
  const classPath = classPathMatch ? classPathMatch[1] : '';
  // ApiVersionRequestMappingHandlerMapping 只给带 @ApiVersion 的控制器加 /api/{version} 前缀；
  // OpenAI 兼容入口（/v1/...）没有该注解，路径必须保持原样。
  const versionMatch = source.match(/@ApiVersion\s*\(\s*(?:value\s*=\s*)?["']([^"']+)["']/);
  const pathPrefix = versionMatch ? `/api/${versionMatch[1]}` : '';

  const methods = [];
  const lines = source.split('\n');
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].includes('@GetMapping')) continue;

    const sameLine = lines[i].match(/@GetMapping\s*\(\s*(?:value\s*=\s*)?["']([^"']*)["']\s*\)/);
    const methodPath = sameLine ? sameLine[1] : '';

    let cursor = i + 1;
    while (cursor < lines.length && !/\bpublic\s/.test(lines[cursor])) cursor++;
    if (cursor >= lines.length) continue;

    let signature = '';
    for (; cursor < lines.length; cursor++) {
      signature += lines[cursor] + '\n';
      const brace = signature.indexOf('{');
      if (brace !== -1) {
        signature = signature.slice(0, brace);
        break;
      }
    }

    const head = signature.match(/public\s+(?:static\s+|final\s+)*(.+?)\s+([A-Za-z_$][\w$]*)\s*\(/s);
    if (!head) continue;
    const returnType = head[1].trim();
    const methodName = head[2];

    const open = signature.indexOf('(');
    const close = signature.lastIndexOf(')');
    const paramText = open !== -1 && close > open ? signature.slice(open + 1, close) : '';
    const params = parseParams(paramText);

    methods.push({
      controller: controllerName,
      method: methodName,
      http: 'GET',
      path: pathPrefix + classPath + methodPath,
      returnType,
      params,
      signature,
    });
  }
  return methods;
}

/** 一个 GET 方法是否属于"列表/历史/日志"类端点。 */
function isListEndpoint(method) {
  const name = method.method;
  const params = new Set(method.params.map(param => param.name));
  const listType = /List<|Page|ListResponse|PageResult/.test(method.returnType);
  const listName = /^(list[A-Z].*|get[A-Z].*(History|Results|Alerts|Experiments|Suites|Configs|Schedules|Principals|Keys))$/.test(name)
    || /^auditLogs$/.test(name);
  const offsetLimit = params.has('offset') && params.has('limit');
  const exportWithLimit = /^export/.test(name) && params.has('limit');
  return listType || listName || offsetLimit || exportWithLimit;
}

function readEvidence(relativePath) {
  const full = path.join(ROOT, relativePath);
  if (!fs.existsSync(full)) return null;
  return fs.readFileSync(full, 'utf8');
}

function ensureEvidence(entry, field, errors) {
  const evidence = entry[field];
  if (!Array.isArray(evidence) || evidence.length === 0) {
    errors.push(`${entry.controller}#${entry.method}: ${field} 不能为空`);
    return;
  }
  for (const item of evidence) {
    const source = readEvidence(item.file);
    if (source === null) {
      errors.push(`${entry.controller}#${entry.method}: 证据文件不存在 ${item.file}`);
      continue;
    }
    if (!source.includes(item.token)) {
      errors.push(`${entry.controller}#${entry.method}: ${item.file} 中找不到证据 "${item.token}"`);
    }
  }
}

/** 校验 manifest 条目与源码是否一致，返回错误列表。 */
function validateManifest(manifest, controllerMethods, options = {}) {
  const errors = [];
  const entries = new Map(manifest.endpoints.map(entry => [`${entry.controller}#${entry.method}`, entry]));
  const detected = controllerMethods.filter(isListEndpoint);
  const actualKeys = new Set(controllerMethods.map(method => `${method.controller}#${method.method}`));

  for (const method of detected) {
    const key = `${method.controller}#${method.method}`;
    if (!entries.has(key)) {
      errors.push(`未登记列表端点：${key} (${method.path || method.returnType})，请在 ${path.relative(ROOT, MANIFEST_FILE)} 声明分页与排序契约`);
    }
  }
  for (const entry of manifest.endpoints) {
    const key = `${entry.controller}#${entry.method}`;
    if (!actualKeys.has(key)) {
      errors.push(`manifest 条目已过期或方法名写错：${key} 在 controller 源码中不是 GET 方法`);
      continue;
    }
    // 路径校验：源码路径 = @ApiVersion 前缀 + 类级路径 + 方法级路径。
    const method = controllerMethods.find(item => item.controller === entry.controller && item.method === entry.method);
    if (method && entry.path && method.path !== entry.path) {
      errors.push(`${key}: manifest 路径 ${entry.path} 与源码 ${method.path} 不一致`);
    }
  }

  const globalMax = manifest.maxPageSize;
  if (!Number.isInteger(globalMax) || globalMax <= 0) {
    errors.push(`maxPageSize 必须是正整数，实际为 ${globalMax}`);
  }

  for (const entry of manifest.endpoints) {
    const key = `${entry.controller}#${entry.method}`;
    if (!PAGINATION_MODES.has(entry.pagination)) {
      errors.push(`${key}: 未知分页模式 ${entry.pagination}`);
      continue;
    }

    if (entry.pagination === 'none') {
      if (typeof entry.reason !== 'string' || entry.reason.trim().length < 20) {
        errors.push(`${key}: 未分页端点必须写明 reason（为什么天然有界）`);
      }
      continue;
    }

    if (!Array.isArray(entry.params) || entry.params.length === 0) {
      errors.push(`${key}: 分页端点必须声明 params`);
      continue;
    }

    const method = controllerMethods.find(item => item.controller === entry.controller && item.method === entry.method);
    if (method) {
      for (const paramName of entry.params) {
        if (!method.params.some(param => param.name === paramName)) {
          errors.push(`${key}: controller 参数里找不到 "${paramName}"`);
          continue;
        }
        const expected = entry.defaults && entry.defaults[paramName];
        if (expected !== undefined) {
          const declared = method.params.find(param => param.name === paramName);
          if (declared.defaultValue !== String(expected)) {
            errors.push(`${key}: 参数 ${paramName} 默认值应为 ${expected}，实际为 ${declared.defaultValue}`);
          }
        }
      }
    }

    if (!Number.isInteger(entry.max) || entry.max <= 0) {
      errors.push(`${key}: 分页端点必须声明正整数上限 max`);
    } else if (entry.max > globalMax && !entry.maxException) {
      errors.push(`${key}: 上限 ${entry.max} 超过全局阈值 ${globalMax}，如确有必要请写 maxException`);
    }

    ensureEvidence(entry, 'capEvidence', errors);
    ensureEvidence(entry, 'sortEvidence', errors);
  }

  if (options.alsoCheckPaginationSupport !== false) {
    const support = readEvidence('spring-ai-rag-core/src/main/java/com/springairag/core/util/PaginationSupport.java');
    if (support === null) {
      errors.push('缺少统一的 PaginationSupport 工具类');
    } else {
      if (!/DEFAULT_MAX_PAGE_SIZE\s*=\s*(\d+)/.test(support)) {
        errors.push('PaginationSupport 缺少 DEFAULT_MAX_PAGE_SIZE 常量');
      } else {
        const value = Number(support.match(/DEFAULT_MAX_PAGE_SIZE\s*=\s*(\d+)/)[1]);
        if (value > globalMax) {
          errors.push(`PaginationSupport.DEFAULT_MAX_PAGE_SIZE=${value} 超过全局阈值 ${globalMax}`);
        }
      }
    }
  }

  return errors;
}

function loadActual() {
  const manifest = JSON.parse(fs.readFileSync(MANIFEST_FILE, 'utf8'));
  const methods = [];
  for (const file of readControllerSources(CONTROLLER_ROOT)) {
    const source = fs.readFileSync(file, 'utf8');
    methods.push(...parseController(source, path.basename(file, '.java')));
  }
  return { manifest, methods };
}

function runSelfTest() {
  const fixtureController = `
@RestController
@RequestMapping("/api/v1/fixture")
public class FixtureController {
    @GetMapping("/items")
    public ResponseEntity<List<String>> listItems(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return null;
    }
}
`;
  const methods = parseController(fixtureController, 'FixtureController');
  const baseEntry = {
    controller: 'FixtureController',
    method: 'listItems',
    path: '/api/v1/fixture/items',
    pagination: 'offset-limit',
    params: ['offset', 'limit'],
    defaults: { offset: 0, limit: 50 },
    max: 1000,
    capEvidence: [{ file: 'spring-ai-rag-core/src/main/java/com/springairag/core/util/PaginationSupport.java', token: 'offsetLimit' }],
    sortEvidence: [{ file: 'spring-ai-rag-core/src/main/java/com/springairag/core/util/PaginationSupport.java', token: 'NEWEST_FIRST' }],
  };

  const empty = validateManifest({ maxPageSize: 1000, endpoints: [] }, methods, { alsoCheckPaginationSupport: false });
  if (empty.length === 0) fail('self-test: 未登记的列表端点应被门禁发现');

  const tooLarge = validateManifest({ maxPageSize: 1000, endpoints: [{ ...baseEntry, max: 10000 }] }, methods, { alsoCheckPaginationSupport: false });
  if (!tooLarge.some(error => error.includes('超过全局阈值'))) fail('self-test: 超上限应被门禁发现');

  const missingTieBreak = validateManifest(
    { maxPageSize: 1000, endpoints: [{ ...baseEntry, sortEvidence: [{ file: 'nope.java', token: 'x' }] }] },
    methods,
    { alsoCheckPaginationSupport: false },
  );
  if (!missingTieBreak.some(error => error.includes('证据文件不存在'))) fail('self-test: 排序证据缺失应被门禁发现');

  const valid = validateManifest({ maxPageSize: 1000, endpoints: [baseEntry] }, methods, { alsoCheckPaginationSupport: false });
  if (valid.length !== 0) fail(`self-test: 合法契约不应报错，实际：${valid.join('; ')}`);

  console.log('列表端点分页门禁 self-test 通过。');
}

function main() {
  const { manifest, methods } = loadActual();
  const errors = validateManifest(manifest, methods);
  if (errors.length > 0) {
    console.error('列表端点分页一致性检查失败：');
    for (const error of errors) console.error(`  - ${error}`);
    process.exit(1);
  }
  console.log(`列表端点分页一致性检查通过：${manifest.endpoints.length} 个端点均已声明分页与稳定排序契约。`);
}

if (process.argv.includes('--self-test')) {
  try {
    runSelfTest();
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
} else {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
