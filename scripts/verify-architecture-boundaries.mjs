// 架构边界门禁：只检查“硬规则、当前零违规、零误报”的四类约束。
//
// 为什么是这四类
// --------------
// 1. Maven 模块依赖方向：api ← documents ← core ← starter。当前 pom 与源码均无反向依赖。
// 2. 源码跨模块 import 方向：与 pom 同向，防止“能编译但绕过模块边界”的静态 import。
// 3. core 包分层：controller 不被非 controller 包 import；repository 不 import
//    service/controller；entity 不 import repository/service/controller。
// 4. 以上规则都已在真实源码树验证为零违规，并可被自测注入的违规拦住。
//
// 明确不落地的规则（测量后判定高噪声，见 docs/developer-reference-zh-CN.md）：
// - 包级循环依赖：2026-11-04 实测 core 30 个包落在同一个强连通分量，落地即全红，
//   需要先做跨包重构，不是一条门禁能解决的。
// - “web 层不得直接访问 repository”：当前 5 个 controller 直接 import repository，
//   属于既有架构债，需先抽 service；直接落地会把存量债务变成永久红灯。
//
// 环境变量 ARCH_BOUNDARY_ROOT 指向被测根目录，自测用它注入 fixture。

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripJavaComments } from './lib/java-source.mjs';
import { isMainModule } from './lib/is-main-module.mjs';

const DEFAULT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const MODULES = [
  { name: 'api', dir: 'spring-ai-rag-api', imports: ['com.springairag.api'], deps: [] },
  {
    name: 'documents',
    dir: 'spring-ai-rag-documents',
    imports: ['com.springairag.api', 'com.springairag.documents'],
    deps: ['spring-ai-rag-api'],
  },
  {
    name: 'core',
    dir: 'spring-ai-rag-core',
    imports: ['com.springairag.api', 'com.springairag.documents', 'com.springairag.core'],
    deps: ['spring-ai-rag-api', 'spring-ai-rag-documents'],
  },
  {
    name: 'starter',
    dir: 'spring-ai-rag-starter',
    imports: [
      'com.springairag.api',
      'com.springairag.documents',
      'com.springairag.core',
      'com.springairag.starter',
    ],
    deps: ['spring-ai-rag-api', 'spring-ai-rag-documents', 'spring-ai-rag-core'],
  },
];

const CORE_CONTROLLER = 'com.springairag.core.controller';
const CORE_REPOSITORY = 'com.springairag.core.repository';
const CORE_SERVICE = 'com.springairag.core.service';
const CORE_ENTITY = 'com.springairag.core.entity';

function listJavaFiles(dir) {
  if (!fs.existsSync(dir)) return [];
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...listJavaFiles(full));
    else if (entry.name.endsWith('.java')) out.push(full);
  }
  return out;
}

/** 解析 POM 中 groupId=com.springairag 的编译期依赖；注释与 test scope 不算。 */
export function parseProjectDependencies(pomXml) {
  const xml = pomXml.replace(/<!--[\s\S]*?-->/g, '');
  const deps = [];
  for (const block of xml.match(/<dependency>[\s\S]*?<\/dependency>/g) || []) {
    const group = block.match(/<groupId>\s*([^<]+?)\s*<\/groupId>/);
    const artifact = block.match(/<artifactId>\s*([^<]+?)\s*<\/artifactId>/);
    const scope = block.match(/<scope>\s*([^<]+?)\s*<\/scope>/);
    if (!group || !artifact || group[1] !== 'com.springairag') continue;
    if (scope && scope[1] === 'test') continue;
    deps.push(artifact[1]);
  }
  return deps;
}

/** 读取一个 Java 文件：包名 + 去掉注释后的 import（含行号）。 */
export function readJavaFile(file) {
  const source = stripJavaComments(fs.readFileSync(file, 'utf8'));
  const pkg = source.match(/^package\s+([^;]+);/m);
  const imports = [];
  for (const m of source.matchAll(/^import\s+(?:static\s+)?([\w.*]+);/gm)) {
    imports.push({ target: m[1], line: source.slice(0, m.index).split('\n').length });
  }
  return { pkg: pkg ? pkg[1] : '', imports };
}

function isUnder(target, root) {
  return target === root || target.startsWith(`${root}.`);
}

/** 返回违规列表；纯函数，便于自测直接喂 fixture。 */
export function collectViolations(root) {
  const violations = [];
  const stats = { javaFiles: 0, projectDeps: 0 };

  for (const mod of MODULES) {
    const pomPath = path.join(root, mod.dir, 'pom.xml');
    if (fs.existsSync(pomPath)) {
      const deps = parseProjectDependencies(fs.readFileSync(pomPath, 'utf8'));
      stats.projectDeps += deps.length;
      for (const dep of deps) {
        if (!mod.deps.includes(dep)) {
          violations.push({
            rule: 'module-dependency-direction',
            module: mod.name,
            file: path.relative(root, pomPath),
            detail: `禁止依赖 ${dep}；允许：${mod.deps.join(', ') || '无项目内依赖'}`,
          });
        }
      }
    }

    const sourceRoot = path.join(root, mod.dir, 'src', 'main', 'java');
    for (const file of listJavaFiles(sourceRoot)) {
      stats.javaFiles += 1;
      const { pkg, imports } = readJavaFile(file);
      for (const imp of imports) {
        if (!imp.target.startsWith('com.springairag.')) continue;
        const top = imp.target.split('.').slice(0, 3).join('.');
        if (!mod.imports.includes(top)) {
          violations.push({
            rule: 'module-import-direction',
            module: mod.name,
            file: `${path.relative(root, file)}:${imp.line}`,
            detail: `${pkg} 不得 import ${imp.target}；允许：${mod.imports.join(', ')}`,
          });
        }

        if (
          !isUnder(pkg, CORE_CONTROLLER) &&
          isUnder(imp.target, CORE_CONTROLLER)
        ) {
          violations.push({
            rule: 'controller-imported-by-non-controller',
            module: mod.name,
            file: `${path.relative(root, file)}:${imp.line}`,
            detail: `${pkg} 不得依赖 controller 层 ${imp.target}`,
          });
        }

        if (
          isUnder(pkg, CORE_REPOSITORY) &&
          (isUnder(imp.target, CORE_SERVICE) || isUnder(imp.target, CORE_CONTROLLER))
        ) {
          violations.push({
            rule: 'repository-layer-direction',
            module: mod.name,
            file: `${path.relative(root, file)}:${imp.line}`,
            detail: `repository 包不得依赖 service/controller ${imp.target}`,
          });
        }

        if (
          isUnder(pkg, CORE_ENTITY) &&
          (isUnder(imp.target, CORE_REPOSITORY) ||
            isUnder(imp.target, CORE_SERVICE) ||
            isUnder(imp.target, CORE_CONTROLLER))
        ) {
          violations.push({
            rule: 'entity-layer-direction',
            module: mod.name,
            file: `${path.relative(root, file)}:${imp.line}`,
            detail: `entity 包不得依赖 repository/service/controller ${imp.target}`,
          });
        }
      }
    }
  }

  return { violations, stats };
}

function main() {
  const root = process.env.ARCH_BOUNDARY_ROOT
    ? path.resolve(process.env.ARCH_BOUNDARY_ROOT)
    : DEFAULT_ROOT;
  const { violations, stats } = collectViolations(root);

  if (violations.length > 0) {
    for (const v of violations) {
      console.error(`- [${v.rule}] ${v.module} ${v.file}: ${v.detail}`);
    }
    console.error(`\n架构边界违规 ${violations.length} 条（扫描 ${stats.javaFiles} 个 Java 文件）。`);
    process.exitCode = 1;
    return;
  }

  console.log(
    `架构边界通过：${stats.javaFiles} 个 Java 文件、${stats.projectDeps} 条项目内 Maven 依赖，` +
      '模块方向 / import 方向 / core 包分层均无违规。',
  );
  console.log(
    '未覆盖（测量后判定高噪声）：包级循环依赖（core 30 个包同一 SCC）、' +
      'controller 直连 repository（存量 5 个 controller，需先抽 service）。',
  );
}

if (isMainModule(import.meta.url)) {
  main();
}
