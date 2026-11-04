// 架构边界门禁：只检查“硬规则、当前零违规、零误报”的约束。
//
// 为什么是这些规则
// ----------------
// 1. Maven 模块依赖方向：api ← documents ← core ← starter。当前 pom 与源码均无反向依赖。
// 2. 源码跨模块 import 方向：与 pom 同向，防止“能编译但绕过模块边界”的静态 import。
// 3. core 包分层：controller 不被非 controller 包 import；controller 不 import
//    repository；repository 不 import service/controller；entity 不 import
//    repository/service/controller。
// 4. 包级循环用棘轮而不是“必须无环”，基线见下方说明。
// 5. 以上规则都已在真实源码树验证为零违规/与基线一致，并可被自测注入的违规拦住。
//
// 包级循环依赖：2026-11-04 实测 core 30 个包落在同一个强连通分量，直接要求“无环”
// 会让门禁永久红。因此这里用棘轮而不是开关：基线快照记录每个模块的全部跨包边与
// SCC（testdata/architecture/package-cycles-baseline.json）。
//   - 出现基线里没有的边（自然也包括任何新环）→ 失败；
//   - 基线里的边已不存在 → 失败，要求显式下调基线（否则拆掉的边可以被无声加回）；
//   - 只有 --update-package-baseline 允许改基线，且该命令拒绝新增边或扩大 SCC。
// 纯只读配置属性包（com.springairag.core.properties）是数据 sink：它只有字段与
// getter/setter，不出边，也不可能参与任何环。因此“引用属性包”不计入耦合棘轮
// （统计时跳过指向它的边），这样把属性类从 config 下沉后，消费方对 config 的
// 依赖会真实消失并体现在基线下调上；反过来，属性包一旦出现出边，仍会被判为
// 新增边而失败。
// 拆不动的环不硬拆：config/service/chat 等核心包互相依赖是现状，等真正重构再降基线。
// 2026-11 已拆掉 5 条最容易的边（util→config/entity/repository、config→util、
// metrics→resilience），util 退出 SCC；剩余难点是 config：它既装 properties，又装
// 多个基础服务，被几乎所有包 import，同时反向 import 19 个包。拆它需要先把只读
// 属性类按领域下沉、再拆服务，属于跨包重构而非门禁能完成的小改；计划分两步走，
// 每完成一步用 --update-package-baseline 下调基线。
//
// 环境变量 ARCH_BOUNDARY_ROOT 指向被测根目录，ARCH_PACKAGE_BASELINE 指向基线文件，
// 自测用它们注入 fixture。

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

        if (isUnder(pkg, CORE_CONTROLLER) && isUnder(imp.target, CORE_REPOSITORY)) {
          violations.push({
            rule: 'controller-repository-dependency',
            module: mod.name,
            file: `${path.relative(root, file)}:${imp.line}`,
            detail: `${pkg} 不得依赖 repository 层 ${imp.target}；请把查询搬进 service`,
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

// --------------------------------------------------------------- 包级循环棘轮 --

const DEFAULT_PACKAGE_BASELINE = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  'testdata',
  'architecture',
  'package-cycles-baseline.json',
);

/** 模块内的包名以该前缀开头；跨模块 import 不属于模块内包图。 */
const MODULE_PACKAGE_PREFIX = {
  api: 'com.springairag.api',
  documents: 'com.springairag.documents',
  core: 'com.springairag.core',
  starter: 'com.springairag.starter',
};

/** 纯数据 sink：只有属性字段、没有行为；引用它不计入耦合棘轮。 */
const DATA_SINK_PACKAGES = new Set(['com.springairag.core.properties']);

function packageOfClass(target, classToPackage) {
  if (target.endsWith('.*')) return target.slice(0, -2);
  let name = target;
  while (name.includes('.')) {
    const pkg = classToPackage.get(name);
    if (pkg) return pkg;
    name = name.slice(0, name.lastIndexOf('.'));
  }
  return null;
}

/** Tarjan 强连通分量；只返回规模 > 1 的分量，节点与边都是包名。 */
function strongComponents(packages, edges) {
  const successors = new Map();
  for (const pkg of packages) successors.set(pkg, []);
  for (const edge of edges) {
    const list = successors.get(edge.from);
    if (list) list.push(edge.to);
  }
  const index = new Map();
  const low = new Map();
  const onStack = new Set();
  const stack = [];
  const components = [];
  let counter = 0;

  const visit = (node) => {
    index.set(node, counter);
    low.set(node, counter);
    counter += 1;
    stack.push(node);
    onStack.add(node);
    for (const next of successors.get(node) ?? []) {
      if (!index.has(next)) {
        visit(next);
        low.set(node, Math.min(low.get(node), low.get(next)));
      } else if (onStack.has(next)) {
        low.set(node, Math.min(low.get(node), index.get(next)));
      }
    }
    if (low.get(node) === index.get(node)) {
      const component = [];
      while (stack.length > 0) {
        const member = stack.pop();
        onStack.delete(member);
        component.push(member);
        if (member === node) break;
      }
      if (component.length > 1) components.push(component.sort());
    }
  };

  for (const pkg of [...packages].sort()) {
    if (!index.has(pkg)) visit(pkg);
  }
  return components.sort((a, b) => a[0].localeCompare(b[0]));
}

/**
 * 解析每个模块内的跨包 import 图与 SCC。纯函数，便于自测直接喂 fixture。
 * 只统计模块内部边；跨模块 import 由上面的模块方向规则负责。
 */
export function collectPackageGraph(root) {
  const modules = {};
  for (const mod of MODULES) {
    const prefix = MODULE_PACKAGE_PREFIX[mod.name];
    const sourceRoot = path.join(root, mod.dir, 'src', 'main', 'java');
    const files = listJavaFiles(sourceRoot);
    const classToPackage = new Map();
    const parsed = [];
    const packages = new Set();

    for (const file of files) {
      const { pkg, imports } = readJavaFile(file);
      if (!pkg.startsWith(prefix)) continue;
      packages.add(pkg);
      classToPackage.set(`${pkg}.${path.basename(file, '.java')}`, pkg);
      parsed.push({ pkg, imports, file });
    }

    const edgeMap = new Map();
    for (const { pkg, imports, file } of parsed) {
      for (const imp of imports) {
        if (!imp.target.startsWith(`${prefix}.`)) continue;
        const to = packageOfClass(imp.target, classToPackage);
        if (!to || to === pkg || !to.startsWith(prefix)) continue;
        if (DATA_SINK_PACKAGES.has(to)) continue;
        const key = `${pkg}\u0000${to}`;
        if (!edgeMap.has(key)) {
          edgeMap.set(key, {
            from: pkg,
            to,
            locations: [],
          });
        }
        edgeMap.get(key).locations.push(
          `${path.relative(root, file)}:${imp.line}`,
        );
      }
    }

    const edges = [...edgeMap.values()].sort((a, b) =>
      a.from === b.from ? a.to.localeCompare(b.to) : a.from.localeCompare(b.from),
    );
    modules[mod.name] = {
      packages: [...packages].sort(),
      edges,
      sccs: strongComponents(packages, edges),
    };
  }
  return modules;
}

function baselineEdgeKey(edge) {
  return `${edge[0]}\u0000${edge[1]}`;
}

/** 从当前包图生成可提交的基线形状（只存包名，不存行号，避免无关改动让快照漂移）。 */
export function packageBaselineOf(graph) {
  const modules = {};
  for (const [module, value] of Object.entries(graph)) {
    modules[module] = {
      edges: value.edges.map((edge) => [edge.from, edge.to]),
      sccs: value.sccs,
    };
  }
  return {
    generatedBy: 'scripts/verify-architecture-boundaries.mjs',
    note: '跨包 import 边与强连通分量的棘轮基线：只允许删除边后下调；新增边必须重构。',
    modules,
  };
}

/**
 * 比较当前包图与基线。返回违规列表：
 * - package-edge-added：基线里没有的边（含任何新环）；
 * - package-edge-removed：基线的边已经消失，必须显式下调基线；
 * - package-cycle-grown：某个 SCC 超出了基线里与它重叠最多的 SCC。
 */
export function comparePackageGraph(graph, baseline) {
  const violations = [];
  const baseModules = (baseline && baseline.modules) || {};

  for (const [module, current] of Object.entries(graph)) {
    const base = baseModules[module] || { edges: [], sccs: [] };
    const baseEdges = new Set((base.edges || []).map(baselineEdgeKey));
    const currentEdges = new Set(
      current.edges.map((edge) => baselineEdgeKey([edge.from, edge.to])),
    );

    for (const edge of current.edges) {
      if (!baseEdges.has(baselineEdgeKey([edge.from, edge.to]))) {
        violations.push({
          rule: 'package-edge-added',
          module,
          file: edge.locations[0],
          detail: `${edge.from} → ${edge.to} 不在包图基线；新增跨包边必须重构或显式改基线`,
        });
      }
    }
    for (const [from, to] of base.edges || []) {
      if (!currentEdges.has(baselineEdgeKey([from, to]))) {
        violations.push({
          rule: 'package-edge-removed',
          module,
          file: 'testdata/architecture/package-cycles-baseline.json',
          detail: `${from} → ${to} 已不存在；请用 --update-package-baseline 下调基线`,
        });
      }
    }

    const baseSccs = base.sccs || [];
    for (const component of current.sccs) {
      let best = null;
      let bestOverlap = 0;
      for (const candidate of baseSccs) {
        const overlap = component.filter((pkg) => candidate.includes(pkg)).length;
        if (overlap > bestOverlap) {
          best = candidate;
          bestOverlap = overlap;
        }
      }
      if (component.length > (best ? best.length : 0)) {
        violations.push({
          rule: 'package-cycle-grown',
          module,
          file: 'testdata/architecture/package-cycles-baseline.json',
          detail: `SCC 规模 ${component.length} 超过基线 ${best ? best.length : 0}：`
            + component.join(', '),
        });
      }
    }
  }
  return violations;
}

function readPackageBaseline(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function packageBaselinePath() {
  return process.env.ARCH_PACKAGE_BASELINE
    ? path.resolve(process.env.ARCH_PACKAGE_BASELINE)
    : DEFAULT_PACKAGE_BASELINE;
}

/** 基线更新只允许下调：新增边或扩大的 SCC 一律拒绝，避免“跑一下就绿”。 */
function updatePackageBaseline(root, baselinePath) {
  const graph = collectPackageGraph(root);
  const current = packageBaselineOf(graph);
  const hasExisting = fs.existsSync(baselinePath);
  let removed = 0;
  if (hasExisting) {
    const existing = readPackageBaseline(baselinePath);
    const growth = comparePackageGraph(graph, existing).filter(
      (violation) =>
        violation.rule === 'package-edge-added'
        || violation.rule === 'package-cycle-grown',
    );
    if (growth.length > 0) {
      for (const violation of growth) {
        console.error(`- [${violation.rule}] ${violation.detail}`);
      }
      console.error('\n拒绝更新包图基线：只允许删除边后下调，新增边/扩大环必须先重构。');
      return 1;
    }
    removed = comparePackageGraph(graph, existing).filter(
      (violation) => violation.rule === 'package-edge-removed',
    ).length;
  }
  fs.mkdirSync(path.dirname(baselinePath), { recursive: true });
  fs.writeFileSync(`${baselinePath}`, `${JSON.stringify(current, null, 2)}\n`);
  console.log(
    `${hasExisting ? `包图基线已下调：删除 ${removed} 条边` : '包图基线已创建'}，`
      + `当前 ${Object.values(current.modules)
        .reduce((sum, mod) => sum + mod.edges.length, 0)} 条跨包边。`,
  );
  return 0;
}

function collectPackageViolations(root) {
  const baselinePath = packageBaselinePath();
  if (!fs.existsSync(baselinePath)) {
    return {
      violations: [{
        rule: 'package-baseline-missing',
        module: '-',
        file: path.relative(root, baselinePath),
        detail: '包图基线缺失；先运行 node scripts/verify-architecture-boundaries.mjs --update-package-baseline',
      }],
      graph: null,
      baselinePath,
    };
  }
  const graph = collectPackageGraph(root);
  return {
    violations: comparePackageGraph(graph, readPackageBaseline(baselinePath)),
    graph,
    baselinePath,
  };
}

function main() {
  const root = process.env.ARCH_BOUNDARY_ROOT
    ? path.resolve(process.env.ARCH_BOUNDARY_ROOT)
    : DEFAULT_ROOT;

  if (process.argv.includes('--update-package-baseline')) {
    process.exitCode = updatePackageBaseline(root, packageBaselinePath());
    return;
  }

  const { violations, stats } = collectViolations(root);
  const packageCheck = collectPackageViolations(root);
  const allViolations = [...violations, ...packageCheck.violations];

  if (allViolations.length > 0) {
    for (const v of allViolations) {
      console.error(`- [${v.rule}] ${v.module} ${v.file}: ${v.detail}`);
    }
    console.error(`\n架构边界违规 ${allViolations.length} 条（扫描 ${stats.javaFiles} 个 Java 文件）。`);
    process.exitCode = 1;
    return;
  }

  const packageEdges = packageCheck.graph
    ? Object.values(packageCheck.graph)
      .reduce((sum, mod) => sum + mod.edges.length, 0)
    : 0;
  const sccs = packageCheck.graph
    ? Object.values(packageCheck.graph)
      .flatMap((mod) => mod.sccs)
      .map((component) => component.length)
    : [];
  console.log(
    `架构边界通过：${stats.javaFiles} 个 Java 文件、${stats.projectDeps} 条项目内 Maven 依赖，` +
      '模块方向 / import 方向 / core 包分层 / controller 不依赖 repository 均无违规。',
  );
  console.log(
    `包级循环棘轮：${packageEdges} 条跨包边与基线一致`
      + `（SCC ${sccs.length > 0 ? sccs.join(', ') : '无'}，只降不升）。`,
  );
}


if (isMainModule(import.meta.url)) {
  main();
}
