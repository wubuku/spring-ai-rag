// verify-architecture-boundaries 的自测：每个断言都注入一类违规，证明门禁能拦住。
//
// 覆盖：模块依赖反向、源码 import 反向、controller 被非 controller 依赖、
// repository→service、entity→repository，以及三类“不该误报”的写法
// （注释里的 import、test scope 依赖、同层内部 import）。
// 最后用真实 CLI 跑一次“干净通过 / 违规退出 1”，证明退出码生效。

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { collectViolations } from '../verify-architecture-boundaries.mjs';

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
};

function runCli(root) {
  return spawnSync(process.execPath, [SCRIPT], {
    env: { ...process.env, ARCH_BOUNDARY_ROOT: root },
    encoding: 'utf8',
  });
}

let passed = 0;
for (const [name, fn] of Object.entries(cases)) {
  fn();
  passed += 1;
}

const clean = runCli(baseRoot());
assert.equal(clean.status, 0, `干净树应退出 0，实际 ${clean.status}\n${clean.stderr}`);
assert.match(clean.stdout, /架构边界通过/);

const broken = baseRoot();
write(
  broken,
  'spring-ai-rag-core/src/main/java/com/springairag/core/repository/Repo.java',
  java('com.springairag.core.repository', 'import com.springairag.core.service.Svc;'),
);
const bad = runCli(broken);
assert.equal(bad.status, 1, `违规应退出 1，实际 ${bad.status}`);
assert.match(bad.stderr, /repository-layer-direction/);

console.log(`架构边界自测通过：${passed} 个 fixture 场景 + CLI 退出码 2 例。`);
