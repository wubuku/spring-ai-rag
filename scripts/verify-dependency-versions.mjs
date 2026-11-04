#!/usr/bin/env node
/**
 * Java 依赖版本收敛门禁。
 *
 * 背景：第三方版本此前散落在各模块 pom 里。2026-11 的供应链清债是逐个模块
 * 改的，也正因为散着改，`hibernate-vector` 在 core 里写死 `6.6.5.Final`，
 * 而同一 classpath 上的 `hibernate-core` 由 Spring Boot 管到 `6.6.53.Final`——
 * 两个版本并存，构建不会报错，测试也多半照绿。这类漂移只有把"版本只有一个
 * 声明位置"变成可执行规则才拦得住。
 *
 * 规则（全部针对模块 pom；根 pom 是唯一允许写第三方版本的地方）：
 *   1. 已被 Spring Boot BOM、Spring AI BOM 或根 `dependencyManagement` 管住的
 *      依赖，模块里不得再写 `<version>`——写了就是第二份可漂移的真相。
 *   2. 未被上述来源管住的第三方依赖，模块里只允许写 `${property}` 引用，
 *      且该 property 必须定义在根 pom；字面量版本直接失败（`6.6.5.Final`
 *      就是这种形状）。
 *   3. 同一 `groupId:artifactId` 在不同模块里解析出不同版本时失败。
 *   4. 既没有版本、也不被任何来源管理的依赖失败（Maven 本来就会报，但那样
 *      报的是构建错误，不是"版本该放哪"的规则）。
 *
 * 项目自身的 reactor 模块（`com.springairag:*`）是例外：它们在各模块里用
 * `${project.version}` 表达"跟父工程同版本"，这正是 Maven 多模块的常规写法，
 * 不属于第三方版本漂移。
 *
 * 为什么用 `mvn help:effective-pom` 而不是自己解析 BOM：BOM 可以嵌套 import，
 * 手写解析迟早会漏一层。effective-pom 给的就是 Maven 实际会用的管理表
 * （本地实测约 2 秒），门禁读它，而不是读一份自己维护的清单。
 *
 * Run: node scripts/verify-dependency-versions.mjs
 */

import { readFileSync, existsSync, mkdtempSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { isMainModule } from './lib/is-main-module.mjs';

// 根目录与 effective-pom 可用环境变量替换，供自测在临时目录里跑完整退出码，
// 不必真的执行 Maven（真实运行时两者都不设置）。
const ROOT = process.env.DEP_VERSION_ROOT ?? new URL('..', import.meta.url).pathname;
const MODULES = [
  'spring-ai-rag-api/pom.xml',
  'spring-ai-rag-core/pom.xml',
  'spring-ai-rag-documents/pom.xml',
  'spring-ai-rag-starter/pom.xml',
];
const INTERNAL_GROUP = 'com.springairag';

/**
 * 去掉 XML 注释，避免注释里的示例依赖被当成声明。
 *
 * 不叫 `stripComments`、也不复用 `lib/java-source.mjs`：那套 helper 处理的是
 * Java / 配置文件的 `//`、`/* *\/` 与字符串字面量，XML 注释是 `<!-- -->`，
 * 两者规则不同（XML 里没有行注释，也没有会被误判的定界符）。
 */
export function stripXmlComments(xml) {
  return xml.replace(/<!--[\s\S]*?-->/g, '');
}

/** 读取根 pom 的 `<properties>`（只取根工程自己的块）。 */
export function parseProperties(xml) {
  const flat = stripXmlComments(xml);
  const block = /<properties>([\s\S]*?)<\/properties>/.exec(flat);
  const props = {};
  if (!block) return props;
  for (const m of block[1].matchAll(/<([A-Za-z0-9_.-]+)>([^<]*)<\/\1>/g)) {
    props[m[1]] = m[2].trim();
  }
  return props;
}

/**
 * 读取某段 XML 里的 `<dependency>` 声明。
 *
 * 先切掉 `<dependencyManagement>`：根 pom 的管理表由 effective-pom 负责，
 * 模块 pom 即使以后加了管理表，也不该把管理条目算成"模块声明"。
 */
export function parseDeclaredDependencies(xml, file = '<fixture>') {
  // 管理表由 effective-pom 负责；`<build>` 里的插件依赖（如 pitest 的
  // junit5 plugin）不是项目依赖，不属于这条规则的范围。
  const flat = stripXmlComments(xml)
    .replace(/<build>[\s\S]*?<\/build>/g, '')
    .replace(/<dependencyManagement>[\s\S]*?<\/dependencyManagement>/g, '');
  const deps = [];
  for (const block of flat.match(/<dependency>[\s\S]*?<\/dependency>/g) ?? []) {
    const groupId = /<groupId>([^<]+)<\/groupId>/.exec(block)?.[1]?.trim();
    const artifactId = /<artifactId>([^<]+)<\/artifactId>/.exec(block)?.[1]?.trim();
    const version = /<version>([^<]+)<\/version>/.exec(block)?.[1]?.trim();
    if (!groupId || !artifactId) continue;
    deps.push({ file, groupId, artifactId, version });
  }
  return deps;
}

/** 从 effective-pom 的第一个工程（根工程）读取管理表。 */
export function parseManagedVersions(effectivePom) {
  // effective-pom 把整个 reactor 的每个工程依次写进一个文件，不是合法 XML；
  // 用 `<?xml` 分片后，第 1 片（下标 1）就是根工程，第 2 片起是各模块。
  const parts = effectivePom.split('<?xml version');
  const rootProject = parts[1] ?? effectivePom;
  const start = rootProject.indexOf('<dependencyManagement>');
  if (start < 0) return new Map();
  const end = rootProject.indexOf('</dependencyManagement>', start);
  const section = rootProject.slice(start, end < 0 ? undefined : end);
  const managed = new Map();
  for (const dep of parseDeclaredDependencies(section)) {
    if (dep.version) managed.set(`${dep.groupId}:${dep.artifactId}`, dep.version);
  }
  return managed;
}

function recordVersion(map, key, file, version) {
  const bucket = map.get(key) ?? [];
  bucket.push({ file, version });
  map.set(key, bucket);
}

/**
 * 纯分析函数：输入模块声明与管理表，返回 findings。
 * 自测直接调用它，不需要跑 Maven；{file, message} 让失败信息能指回 pom。
 */
export function analyze({ declarations, managed, properties }) {
  const findings = [];
  const resolvedByArtifact = new Map();

  for (const dep of declarations) {
    const key = `${dep.groupId}:${dep.artifactId}`;
    const managedVersion = managed.get(key);
    const internal = dep.groupId === INTERNAL_GROUP;

    if (dep.version) {
      if (managedVersion !== undefined && !internal) {
        findings.push({
          file: dep.file,
          message:
            `${key} 已由 BOM / 根 dependencyManagement 管理（${managedVersion}），` +
            '模块里不要再写 <version>；要改版本请改根 pom。',
        });
      }
      // 已管理的依赖只报"不要写版本"这一条：再报字面量会让同一个原因
      // 出现两次，失败信息变长而信息量不变。
      if (managedVersion === undefined && !internal && !/^\$\{[^}]+\}$/.test(dep.version)) {
        findings.push({
          file: dep.file,
          message: `${key} 写的是字面量版本 ${dep.version}；第三方版本只能声明在根 pom 的 dependencyManagement。`,
        });
        recordVersion(resolvedByArtifact, key, dep.file, dep.version);
      }
      if (managedVersion === undefined && !internal && /^\$\{[^}]+\}$/.test(dep.version)) {
        const prop = dep.version.slice(2, -1);
        if (!(prop in properties)) {
          findings.push({
            file: dep.file,
            message: `${key} 引用了根 pom 中不存在的 property \`${prop}\`。`,
          });
        } else {
          recordVersion(resolvedByArtifact, key, dep.file, properties[prop]);
        }
      }
    } else if (managedVersion === undefined) {
      findings.push({
        file: dep.file,
        message: `${key} 没有 <version>，也不在任何 BOM / 根 dependencyManagement 里。`,
      });
    }
  }

  for (const [key, entries] of resolvedByArtifact) {
    const versions = new Set(entries.map((e) => e.version));
    if (versions.size > 1) {
      findings.push({
        file: entries.map((e) => e.file).join(', '),
        message: `${key} 在不同模块解析出不同版本：${[...versions].join(' / ')}。`,
      });
    }
  }
  return findings;
}

/** 跑 `mvn help:effective-pom`，返回文本；失败时抛出带命令的错误。 */
export function readEffectivePom(root = ROOT) {
  if (process.env.DEP_VERSION_EFFECTIVE_POM) {
    return readFileSync(process.env.DEP_VERSION_EFFECTIVE_POM, 'utf8');
  }
  const dir = mkdtempSync(join(tmpdir(), 'dep-versions-'));
  const output = join(dir, 'effective-pom.xml');
  try {
    const result = spawnSync(
      'mvn',
      ['-B', '-q', 'help:effective-pom', `-Doutput=${output}`],
      { cwd: root, encoding: 'utf8' },
    );
    if (result.error) throw result.error;
    if (result.status !== 0) {
      throw new Error(
        `mvn help:effective-pom 退出码 ${result.status}：${(result.stderr || '').slice(-400)}`,
      );
    }
    if (!existsSync(output)) throw new Error('mvn 没有生成 effective-pom 文件');
    return readFileSync(output, 'utf8');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

function main() {
  const rootPom = readFileSync(join(ROOT, 'pom.xml'), 'utf8');
  const properties = parseProperties(rootPom);
  const declarations = MODULES.flatMap((file) =>
    parseDeclaredDependencies(readFileSync(join(ROOT, file), 'utf8'), file),
  );
  const managed = parseManagedVersions(readEffectivePom());

  const findings = analyze({ declarations, managed, properties });
  if (findings.length > 0) {
    for (const finding of findings) {
      console.error(`${finding.file}: ${finding.message}`);
    }
    console.error(
      `\n${findings.length} 处版本声明问题；第三方版本只能写在根 pom 的 ` +
        'dependencyManagement（或交给 BOM）。',
    );
    process.exit(1);
  }
  console.log(
    `依赖版本收敛检查通过：${declarations.length} 条模块依赖、` +
      `${managed.size} 条管理版本，无手写第三方版本、无跨模块版本分叉。`,
  );
}

if (isMainModule(import.meta.url)) main();
