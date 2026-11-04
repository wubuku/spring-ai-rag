// verify-architecture-boundaries 的自测：每个断言都注入一类违规，证明门禁能拦住。
//
// 覆盖：模块依赖反向、源码 import 反向、controller 被非 controller 依赖、
// controller→repository、repository→service、entity→repository，以及三类“不该误报”的写法
// （注释里的 import、test scope 依赖、同层内部 import）。
// 包级循环棘轮单独覆盖：新边被拒、删边要求下调基线、基线只允许降不允许升。
// 最后用真实 CLI 跑一次“干净通过 / 违规退出 1”，证明退出码生效。

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  collectViolations,
  collectPackageGraph,
  comparePackageGraph,
  packageBaselineOf,
} from '../verify-architecture-boundaries.mjs';

const SCRIPT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'verify-architecture-boundaries.mjs');

function write(root, rel, content) {
  const file = path.join(root, rel);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content);
}

function pom(artifactId, deps = []) {
  const blocks = deps
    .map(
      ([group, artifact, scope]) =>
        `  <dependency><groupId>${group}</groupId><artifactId>${artifact}</artifactId>` +
        `${scope ? `<scope>${scope}</scope>` : ''}</dependency>`,
    )
    .join('\n');
  return [
    '<project><modelVersion>4.0.0</modelVersion>',
    `<artifactId>${artifactId}</artifactId>`,
    '<dependencies>',
    blocks,
    '</dependencies></project>',
  ].join('\n');
}

function java(pkg, body = '') {
  return `package ${pkg};\n${body}\npublic class Fixture {}\n`;
}

function baseRoot() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'arch-boundary-'));
  write(root, 'spring-ai-rag-api/pom.xml', pom('spring-ai-rag-api'));
  write(root, 'spring-ai-rag-api/src/main/java/com/springairag/api/Api.java', java('com.springairag.api'));
  write(
    root,
    'spring-ai-rag-documents/pom.xml',
    pom('spring-ai-rag-documents', [['com.springairag', 'spring-ai-rag-api']]),
  );
  write(root, 'spring-ai-rag-documents/src/main/java/com/springairag/documents/Doc.java', java('com.springairag.documents'));
  write(
    root,
    'spring-ai-rag-core/pom.xml',
    pom('spring-ai-rag-core', [
      ['com.springairag', 'spring-ai-rag-api'],
      ['com.springairag', 'spring-ai-rag-documents'],
    ]),
  );
  write(root, 'spring-ai-rag-core/src/main/java/com/springairag/core/controller/Ctl.java', java('com.springairag.core.controller'));
  write(root, 'spring-ai-rag-core/src/main/java/com/springairag/core/service/Svc.java', java('com.springairag.core.service'));
  write(root, 'spring-ai-rag-core/src/main/java/com/springairag/core/repository/Repo.java', java('com.springairag.core.repository'));
  write(root, 'spring-ai-rag-core/src/main/java/com/springairag/core/entity/Ent.java', java('com.springairag.core.entity'));
  write(
    root,
    'spring-ai-rag-starter/pom.xml',
    pom('spring-ai-rag-starter', [['com.springairag', 'spring-ai-rag-core']]),
  );
  write(root, 'spring-ai-rag-starter/src/main/java/com/springairag/starter/Starter.java', java('com.springairag.starter'));
  return root;
}

function rulesOf(root) {
  return collectViolations(root).violations.map((v) => v.rule);
}

function assertOnly(root, rule, message) {
  const rules = rulesOf(root);
  assert.ok(rules.includes(rule), `${message}：期望命中 ${rule}，实际 ${JSON.stringify(rules)}`);
}

const CORE_SRC = 'spring-ai-rag-core/src/main/java/com/springairag/core';

/** 在 fixture 里造一个 service ↔ chat 的双边环；它不触碰任何已有分层规则。 */
function addCycle(root) {
  write(
    root,
    `${CORE_SRC}/chat/Chat.java`,
    java('com.springairag.core.chat', 'import com.springairag.core.service.Svc;'),
  );
  write(
    root,
    `${CORE_SRC}/service/Svc.java`,
    java('com.springairag.core.service', 'import com.springairag.core.chat.Chat;'),
  );
}

function writePackageBaseline(root, graph = collectPackageGraph(root)) {
  const file = path.join(root, 'package-baseline.json');
  fs.writeFileSync(file, JSON.stringify(packageBaselineOf(graph), null, 2));
  return file;
}

function packageRulesOf(root, baseline) {
  return comparePackageGraph(collectPackageGraph(root), baseline).map((v) => v.rule);
}

const cases = {
  '干净树零违规': () => {
    assert.deepEqual(rulesOf(baseRoot()), []);
  },
  'api 反向依赖 core（pom）': () => {
    const root = baseRoot();
    write(root, 'spring-ai-rag-api/pom.xml', pom('spring-ai-rag-api', [['com.springairag', 'spring-ai-rag-core']]));
    assertOnly(root, 'module-dependency-direction', 'api→core');
  },
  'api 反向 import core（源码）': () => {
    const root = baseRoot();
    write(
      root,
      'spring-ai-rag-api/src/main/java/com/springairag/api/Api.java',
      java('com.springairag.api', 'import com.springairag.core.service.Svc;'),
    );
    assertOnly(root, 'module-import-direction', 'api import core');
  },
  'core 反向依赖 starter（pom）': () => {
    const root = baseRoot();
    write(
      root,
      'spring-ai-rag-core/pom.xml',
      pom('spring-ai-rag-core', [
        ['com.springairag', 'spring-ai-rag-api'],
        ['com.springairag', 'spring-ai-rag-documents'],
        ['com.springairag', 'spring-ai-rag-starter'],
      ]),
    );
    assertOnly(root, 'module-dependency-direction', 'core→starter');
  },
  'service 依赖 controller': () => {
    const root = baseRoot();
    write(
      root,
      'spring-ai-rag-core/src/main/java/com/springairag/core/service/Svc.java',
      java('com.springairag.core.service', 'import com.springairag.core.controller.Ctl;'),
    );
    assertOnly(root, 'controller-imported-by-non-controller', 'service→controller');
  },
  'controller 直连 repository': () => {
    const root = baseRoot();
    write(
      root,
      'spring-ai-rag-core/src/main/java/com/springairag/core/controller/Ctl.java',
      java('com.springairag.core.controller', 'import com.springairag.core.repository.Repo;'),
    );
    assertOnly(root, 'controller-repository-dependency', 'controller→repository');
  },
  'repository 依赖 service': () => {
    const root = baseRoot();
    write(
      root,
      'spring-ai-rag-core/src/main/java/com/springairag/core/repository/Repo.java',
      java('com.springairag.core.repository', 'import com.springairag.core.service.Svc;'),
    );
    assertOnly(root, 'repository-layer-direction', 'repository→service');
  },
  'entity 依赖 repository': () => {
    const root = baseRoot();
    write(
      root,
      'spring-ai-rag-core/src/main/java/com/springairag/core/entity/Ent.java',
      java('com.springairag.core.entity', 'import com.springairag.core.repository.Repo;'),
    );
    assertOnly(root, 'entity-layer-direction', 'entity→repository');
  },
  '通配 import 同样被拦': () => {
    const root = baseRoot();
    write(
      root,
      'spring-ai-rag-api/src/main/java/com/springairag/api/Api.java',
      java('com.springairag.api', 'import com.springairag.core.service.*;'),
    );
    assertOnly(root, 'module-import-direction', 'wildcard');
  },
  '注释里的 import 不误报': () => {
    const root = baseRoot();
    write(
      root,
      'spring-ai-rag-api/src/main/java/com/springairag/api/Api.java',
      java(
        'com.springairag.api',
        '// import com.springairag.core.service.Svc;\n/* import com.springairag.starter.Starter; */',
      ),
    );
    assert.deepEqual(rulesOf(root), []);
  },
  'test scope 的项目依赖不误报': () => {
    const root = baseRoot();
    write(
      root,
      'spring-ai-rag-api/pom.xml',
      pom('spring-ai-rag-api', [['com.springairag', 'spring-ai-rag-core', 'test']]),
    );
    assert.deepEqual(rulesOf(root), []);
  },
  'controller 与 repository 同层 import 不误报': () => {
    const root = baseRoot();
    write(
      root,
      'spring-ai-rag-core/src/main/java/com/springairag/core/controller/Ctl.java',
      java('com.springairag.core.controller', 'import com.springairag.core.controller.Other;'),
    );
    write(
      root,
      'spring-ai-rag-core/src/main/java/com/springairag/core/repository/Repo.java',
      java('com.springairag.core.repository', 'import com.springairag.core.repository.Other;'),
    );
    assert.deepEqual(rulesOf(root), []);
  },
  '包级循环：基线一致时通过': () => {
    const root = baseRoot();
    addCycle(root);
    const baseline = packageBaselineOf(collectPackageGraph(root));
    assert.deepEqual(comparePackageGraph(collectPackageGraph(root), baseline), []);
  },
  '包级循环：新增跨包边被拦': () => {
    const root = baseRoot();
    addCycle(root);
    const baseline = packageBaselineOf(collectPackageGraph(root));
    write(root, `${CORE_SRC}/config/Conf.java`, java('com.springairag.core.config'));
    write(
      root,
      `${CORE_SRC}/chat/Chat.java`,
      java('com.springairag.core.chat', 'import com.springairag.core.service.Svc;\nimport com.springairag.core.config.Conf;'),
    );
    assert.ok(
      packageRulesOf(root, baseline).includes('package-edge-added'),
      `新增边应命中 package-edge-added，实际 ${JSON.stringify(packageRulesOf(root, baseline))}`,
    );
  },
  '包级循环：删除基线里的边被拦': () => {
    const root = baseRoot();
    addCycle(root);
    const baseline = packageBaselineOf(collectPackageGraph(root));
    write(
      root,
      `${CORE_SRC}/service/Svc.java`,
      java('com.springairag.core.service'),
    );
    assert.ok(
      packageRulesOf(root, baseline).includes('package-edge-removed'),
      `删边应命中 package-edge-removed，实际 ${JSON.stringify(packageRulesOf(root, baseline))}`,
    );
  },
  '包级循环：SCC 扩大被拦': () => {
    const root = baseRoot();
    addCycle(root);
    const baseline = packageBaselineOf(collectPackageGraph(root));
    write(root, `${CORE_SRC}/config/Conf.java`, java('com.springairag.core.config', 'import com.springairag.core.chat.Chat;'));
    write(
      root,
      `${CORE_SRC}/chat/Chat.java`,
      java('com.springairag.core.chat', 'import com.springairag.core.service.Svc;\nimport com.springairag.core.config.Conf;'),
    );
    assert.ok(
      packageRulesOf(root, baseline).includes('package-cycle-grown'),
      `SCC 扩大应命中 package-cycle-grown，实际 ${JSON.stringify(packageRulesOf(root, baseline))}`,
    );
  },
};

function runCli(root, baseline, extraArgs = []) {
  return spawnSync(process.execPath, [SCRIPT, ...extraArgs], {
    env: {
      ...process.env,
      ARCH_BOUNDARY_ROOT: root,
      ARCH_PACKAGE_BASELINE: baseline,
    },
    encoding: 'utf8',
  });
}

let passed = 0;
for (const [name, fn] of Object.entries(cases)) {
  fn();
  passed += 1;
}

const cleanRoot = baseRoot();
addCycle(cleanRoot);
const clean = runCli(cleanRoot, writePackageBaseline(cleanRoot));
assert.equal(clean.status, 0, `干净树应退出 0，实际 ${clean.status}\n${clean.stderr}`);
assert.match(clean.stdout, /架构边界通过/);
assert.match(clean.stdout, /包级循环棘轮/);

const brokenRoot = baseRoot();
const brokenBaseline = writePackageBaseline(brokenRoot);
write(
  brokenRoot,
  'spring-ai-rag-core/src/main/java/com/springairag/core/repository/Repo.java',
  java('com.springairag.core.repository', 'import com.springairag.core.service.Svc;'),
);
const bad = runCli(brokenRoot, brokenBaseline);
assert.equal(bad.status, 1, `违规应退出 1，实际 ${bad.status}`);
assert.match(bad.stderr, /repository-layer-direction/);

const addedRoot = baseRoot();
addCycle(addedRoot);
const addedBaseline = writePackageBaseline(addedRoot);
write(addedRoot, `${CORE_SRC}/config/Conf.java`, java('com.springairag.core.config'));
write(
  addedRoot,
  `${CORE_SRC}/chat/Chat.java`,
  java('com.springairag.core.chat', 'import com.springairag.core.service.Svc;\nimport com.springairag.core.config.Conf;'),
);
const added = runCli(addedRoot, addedBaseline);
assert.equal(added.status, 1, `新增跨包边应退出 1，实际 ${added.status}`);
assert.match(added.stderr, /package-edge-added/);

// 删边先被拦，下调基线后才通过；这个顺序证明基线只会往下走。
const removedRoot = baseRoot();
addCycle(removedRoot);
const removedBaseline = writePackageBaseline(removedRoot);
write(removedRoot, `${CORE_SRC}/service/Svc.java`, java('com.springairag.core.service'));
const stale = runCli(removedRoot, removedBaseline);
assert.equal(stale.status, 1, `删边后旧基线应退出 1，实际 ${stale.status}`);
assert.match(stale.stderr, /package-edge-removed/);

const lowered = runCli(removedRoot, removedBaseline, ['--update-package-baseline']);
assert.equal(lowered.status, 0, `下调基线应退出 0，实际 ${lowered.status}\n${lowered.stderr}`);
assert.match(lowered.stdout, /包图基线已下调/);

const afterLower = runCli(removedRoot, removedBaseline);
assert.equal(afterLower.status, 0, `下调基线后应退出 0，实际 ${afterLower.status}\n${afterLower.stderr}`);

// 反方向：新增边时 --update 必须拒绝，不能“跑一下就绿”。
const grownRoot = baseRoot();
addCycle(grownRoot);
const grownBaseline = writePackageBaseline(grownRoot);
write(grownRoot, `${CORE_SRC}/config/Conf.java`, java('com.springairag.core.config'));
write(
  grownRoot,
  `${CORE_SRC}/chat/Chat.java`,
  java('com.springairag.core.chat', 'import com.springairag.core.service.Svc;\nimport com.springairag.core.config.Conf;'),
);
const refused = runCli(grownRoot, grownBaseline, ['--update-package-baseline']);
assert.equal(refused.status, 1, `新增边不应被基线更新接受，实际 ${refused.status}\n${refused.stdout}`);
assert.match(refused.stderr, /拒绝更新包图基线/);

console.log(`架构边界自测通过：${passed} 个 fixture 场景 + CLI 退出码与基线更新场景。`);
