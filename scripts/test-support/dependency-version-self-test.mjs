#!/usr/bin/env node
// Self-test for scripts/verify-dependency-versions.mjs.
//
// The gate exists because a version declared in two places drifts: this tree had
// `hibernate-vector` pinned to 6.6.5.Final in the core module while Spring Boot
// managed `hibernate-core` to 6.6.53.Final — two versions of the same ORM on one
// classpath, and neither the build nor the tests said a word.
//
// What is pinned here:
//   1. every rule actually rejects (managed + written version, literal version,
//      undefined property, cross-module divergence, unmanaged without version);
//   2. things that must NOT be findings (reactor modules with ${project.version},
//      plugin dependencies under <build>, the root <dependencyManagement> block);
//   3. the exit code end to end, because the exit code is all CI observes — four
//      gates in this repo have had to be deleted for being unable to fail.

import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import {
  analyze,
  parseDeclaredDependencies,
  parseManagedVersions,
  parseProperties,
} from '../verify-dependency-versions.mjs';

const here = fileURLToPath(import.meta.url);
const GATE = join(here, '..', '..', 'verify-dependency-versions.mjs');

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

const dep = (file, groupId, artifactId, version) => ({
  file,
  groupId,
  artifactId,
  ...(version ? { version } : {}),
});

const run = (declarations, managedEntries = [], properties = {}) =>
  analyze({
    declarations,
    managed: new Map(managedEntries),
    properties,
  });

// ── the rules reject ─────────────────────────────────────────────────────

test('flags a version on an artifact the BOM already manages', () => {
  const findings = run(
    [dep('core/pom.xml', 'org.springframework.boot', 'spring-boot-starter-web', '3.5.16')],
    [['org.springframework.boot:spring-boot-starter-web', '3.5.16']],
  );
  assert.equal(findings.length, 1);
  assert.match(findings[0].message, /已由 BOM/);
  assert.match(findings[0].message, /3\.5\.16/);
});

test('flags a literal third-party version even when nothing manages it', () => {
  // This is the exact shape that drifted: <version>6.6.5.Final</version>.
  const findings = run([dep('core/pom.xml', 'org.hibernate.orm', 'hibernate-vector', '6.6.5.Final')]);
  assert.equal(findings.length, 1);
  assert.match(findings[0].message, /字面量版本 6\.6\.5\.Final/);
});

test('flags a property reference the root pom does not define', () => {
  const findings = run([dep('core/pom.xml', 'com.example', 'lib', '${lib.version}')], [], {});
  assert.equal(findings.length, 1);
  assert.match(findings[0].message, /lib\.version/);
});

test('flags the same artifact resolving to different versions across modules', () => {
  // 一个模块引用根 property，另一个写死 2.0.0：既是字面量版本，也构成跨模块
  // 版本分叉，两条规则都要报。
  const divergent = run(
    [
      dep('api/pom.xml', 'com.example', 'lib', '${lib.version}'),
      dep('core/pom.xml', 'com.example', 'lib', '2.0.0'),
    ],
    [],
    { 'lib.version': '1.0.0' },
  );
  assert.equal(divergent.length, 2, 'literal in one module + divergence');
  assert.ok(divergent.some((f) => /不同版本/.test(f.message)));
  assert.ok(divergent.some((f) => /字面量版本/.test(f.message)));
});

test('flags a dependency with neither a version nor any management', () => {
  const findings = run([dep('core/pom.xml', 'com.example', 'lib')]);
  assert.equal(findings.length, 1);
  assert.match(findings[0].message, /没有 <version>/);
});

// ── what must not be a finding ──────────────────────────────────────────

test('accepts a managed dependency with no version, plus a reactor module', () => {
  const findings = run(
    [
      dep('core/pom.xml', 'org.springframework.boot', 'spring-boot-starter-web'),
      dep('core/pom.xml', 'com.springairag', 'spring-ai-rag-api', '${project.version}'),
      dep('core/pom.xml', 'com.example', 'lib', '${lib.version}'),
    ],
    [['org.springframework.boot:spring-boot-starter-web', '3.5.16']],
    { 'lib.version': '1.0.0' },
  );
  assert.deepEqual(findings, []);
});

test('accepts the same property in several modules', () => {
  const findings = run(
    [
      dep('api/pom.xml', 'com.example', 'lib', '${lib.version}'),
      dep('core/pom.xml', 'com.example', 'lib', '${lib.version}'),
    ],
    [],
    { 'lib.version': '1.0.0' },
  );
  assert.deepEqual(findings, []);
});

// ── parsers: the shapes that must stay invisible ────────────────────────

test('ignores plugin dependencies under <build> and profile builds', () => {
  // core/pom.xml really carries pitest-junit5-plugin 1.2.3 inside a profile
  // build; a naive scan over all <dependency> blocks reports it as a project
  // dependency and fails the gate on a clean tree.
  const xml = `
<project>
  <build>
    <plugins>
      <plugin>
        <artifactId>pitest-maven</artifactId>
        <dependencies>
          <dependency>
            <groupId>org.pitest</groupId>
            <artifactId>pitest-junit5-plugin</artifactId>
            <version>1.2.3</version>
          </dependency>
        </dependencies>
      </plugin>
    </plugins>
  </build>
  <profiles>
    <profile>
      <build>
        <plugins>
          <plugin>
            <dependencies>
              <dependency>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.2.0</version>
              </dependency>
            </dependencies>
          </plugin>
        </plugins>
      </build>
    </profile>
  </profiles>
  <dependencies>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>lib</artifactId>
    </dependency>
  </dependencies>
</project>`;
  const deps = parseDeclaredDependencies(xml, 'core/pom.xml');
  assert.equal(deps.length, 1);
  assert.equal(deps[0].artifactId, 'lib');
});

test('does not treat the root dependencyManagement block as a module declaration', () => {
  const xml = `
<project>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>com.example</groupId>
        <artifactId>lib</artifactId>
        <version>1.0.0</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>lib</artifactId>
    </dependency>
  </dependencies>
</project>`;
  const deps = parseDeclaredDependencies(xml);
  assert.deepEqual(deps.map((d) => [d.artifactId, d.version]), [['lib', undefined]]);
});

test('reads managed versions from the first project of a concatenated effective-pom', () => {
  // `mvn help:effective-pom` writes every reactor project into one file, so the
  // file is not valid XML. Only the root project's management table is relevant.
  const effective = `<?xml version="1.0" encoding="UTF-8"?>
<project>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
        <version>3.5.16</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <dependencies>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>lib</artifactId>
      <version>1.0.0</version>
    </dependency>
  </dependencies>
</project>`;
  const managed = parseManagedVersions(effective);
  assert.deepEqual([...managed], [['org.springframework.boot:spring-boot-starter-web', '3.5.16']]);
});

test('reads root properties and ignores commented-out ones', () => {
  const pom = `
<project>
  <properties>
    <lib.version>1.0.0</lib.version>
    <!-- <old.version>0.9</old.version> -->
  </properties>
</project>`;
  assert.deepEqual(parseProperties(pom), { 'lib.version': '1.0.0' });
});

// ── end to end: the exit code is the only thing CI observes ─────────────

const MODULE_POM = (deps) => `<project>
  <dependencies>
${deps}
  </dependencies>
</project>`;

const ROOT_POM = `<project>
  <properties>
    <lib.version>1.0.0</lib.version>
  </properties>
</project>`;

const EFFECTIVE = `<?xml version="1.0" encoding="UTF-8"?>
<project>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
        <version>3.5.16</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>`;

function runGate(dirty) {
  const dir = mkdtempSync(join(tmpdir(), 'dep-gate-'));
  writeFileSync(join(dir, 'pom.xml'), ROOT_POM);
  const effective = join(dir, 'effective-pom.xml');
  writeFileSync(effective, EFFECTIVE);
  for (const module of ['api', 'core', 'documents', 'starter']) {
    const dirPath = join(dir, `spring-ai-rag-${module}`);
    mkdirSync(dirPath, { recursive: true });
    const deps = module === 'core' && dirty
      ? dirty
      : `<dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>`;
    writeFileSync(join(dirPath, 'pom.xml'), MODULE_POM(deps));
  }
  const res = spawnSync(process.execPath, [GATE], {
    env: {
      ...process.env,
      DEP_VERSION_ROOT: dir,
      DEP_VERSION_EFFECTIVE_POM: effective,
    },
    encoding: 'utf8',
  });
  rmSync(dir, { recursive: true, force: true });
  return res;
}

test('end to end: exits 1 and names the pom when a managed artifact has a version', () => {
  const res = runGate(`<dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
      <version>3.5.16</version>
    </dependency>`);
  assert.equal(res.status, 1);
  assert.match(res.stderr, /spring-ai-rag-core\/pom\.xml: org\.springframework\.boot:spring-boot-starter-web 已由 BOM/);
  assert.match(res.stderr, /1 处版本声明问题/);
});

test('end to end: exits 0 on a converged tree', () => {
  const res = runGate(null);
  assert.equal(res.status, 0);
  assert.match(res.stdout, /依赖版本收敛检查通过/);
});

// ── runner ───────────────────────────────────────────────────────────────

let failed = 0;
for (const { title, fn } of cases) {
  try {
    fn();
    console.log(`PASS: ${title}`);
  } catch (error) {
    failed += 1;
    console.error(`FAIL: ${title}`);
    console.error(`  ${error.message}`);
  }
}
console.log(`\n${cases.length - failed}/${cases.length} passed`);
if (failed > 0) process.exit(1);
