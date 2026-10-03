#!/usr/bin/env node
// Self-test for scripts/verify-controller-constructor-count.mjs.
//
// Two things are pinned here, and the second is the one that matters most.
//
// 1. The rule. A controller with two constructors is a finding; a controller with
//    one is not.
//
// 2. That the fixtures are at least as awkward as the real code. Batch 823's own
//    census script counted only `public ` constructors and therefore reported
//    "0 controllers with more than one constructor" while RagSearchController
//    had three (1 public + 2 package-private). A self-test whose fixtures are
//    all `public Demo(` would have agreed with that bug, which is the trap
//    Batch 820 hit three times in a row. So the negatives below include a
//    package-private constructor (must be a finding), plus comments, string
//    literals, URLs, `new Demo(`, annotations, and nested classes (must not be).
//
// The end-to-end cases spawn the real script and assert its **exit code and
// report text**, because the exit code is the only thing CI observes, and four
// gates in this repo that could not fail have had to be deleted.

import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import {
  neutralize,
  findControllerTypes,
  findConstructorDeclarations,
  isController,
} from '../verify-controller-constructor-count.mjs';

const here = fileURLToPath(import.meta.url);
const GATE = join(here, '..', '..', 'verify-controller-constructor-count.mjs');

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

// ── the load-bearing rule ─────────────────────────────────────────────────

test('flags a second public constructor', () => {
  const src = `
@RestController
class Demo {
    @Autowired
    public Demo(SomeService a, OtherService b) { }
    public Demo(SomeService a) { this(a, null); }
}`;
  const ctors = findConstructorDeclarations(src);
  assert.equal(ctors.length, 2);
  assert.deepEqual(ctors.map((c) => c.visibility), ['public', 'public']);
  assert.equal(ctors[0].annotated, true);
  assert.equal(ctors[1].annotated, false);
});

test('flags a second package-private constructor — the miss Batch 823 measured', () => {
  // The census script required the literal `public ` keyword, so these two went
  // unreported and the gate would have looked perfectly healthy.
  const src = `
@RestController
class Demo {
    @Autowired
    public Demo(SomeService a) { }
    Demo(SomeService a, LegacyResolver b) { this(a); }
    Demo(SomeService a, LegacyResolver b, ReRankingService c) { this(a, b); }
}`;
  const ctors = findConstructorDeclarations(src);
  assert.equal(ctors.length, 3);
  assert.deepEqual(
    ctors.map((c) => c.visibility),
    ['public', 'package', 'package'],
  );
});

test('flags a protected or private second constructor too', () => {
  for (const visibility of ['protected', 'private']) {
    const src = `
@RestController
class Demo {
    public Demo(SomeService a) { }
    ${visibility} Demo(SomeService a, Other b) { this(a); }
}`;
    assert.equal(findConstructorDeclarations(src).length, 2, visibility);
  }
});

test('a single constructor is not a finding', () => {
  const src = `
@RestController
class Demo {
    @Autowired
    public Demo(SomeService a, @Autowired(required = false) AuditLogService b) { }
}`;
  assert.equal(findConstructorDeclarations(src).length, 1);
});

test('a controller with no declared constructor at all is not a finding', () => {
  const src = `
@RestController
class Demo {
    void go() { }
}`;
  assert.equal(findConstructorDeclarations(src).length, 0);
});

// ── what must NOT be mistaken for a constructor ──────────────────────────
// Real controllers contain all of these; RagSearchController alone has a Javadoc
// block above its constructor and an ApiVersion annotation on the class.

test('ignores a Javadoc block that mentions a constructor call', () => {
  const src = `
@RestController
class Demo {
    /**
     * Use Demo(a, b) for the short form.
     * @see Demo
     */
    public Demo(SomeService a, OtherService b) { }
}`;
  assert.equal(findConstructorDeclarations(src).length, 1);
});

test('ignores a line comment mentioning a constructor', () => {
  const src = `
@RestController
class Demo {
    // replaced by Demo(a) in Batch 823
    public Demo(SomeService a, OtherService b) { }
}`;
  assert.equal(findConstructorDeclarations(src).length, 1);
});

test('ignores a string literal that looks like a constructor call', () => {
  const src = `
@RestController
class Demo {
    public Demo(SomeService a) {
        this.url = "https://example.com/api/Demo(";
        this.other = "see Demo(a, b) for details";
    }
}`;
  assert.equal(findConstructorDeclarations(src).length, 1);
});

test('ignores a character literal and an escaped quote', () => {
  const src = `
@RestController
class Demo {
    public Demo(SomeService a) {
        this.sep = '/';
        this.msg = "a \\" Demo(b) c";
    }
}`;
  assert.equal(findConstructorDeclarations(src).length, 1);
});

test('ignores new Demo(...) inside a method body', () => {
  const src = `
@RestController
class Demo {
    public Demo(SomeService a) { }
    void go() {
        Demo other = new Demo(a);
        use(other);
    }
}`;
  const ctors = findConstructorDeclarations(src);
  assert.equal(ctors.length, 1);
});

test('ignores a nested class constructor', () => {
  const src = `
@RestController
class Demo {
    public Demo(SomeService a) { }
    static class Helper {
        Helper(int x) { }
    }
}`;
  assert.equal(findConstructorDeclarations(src).length, 1);
});

test('ignores an anonymous class body with its own initializer', () => {
  const src = `
@RestController
class Demo {
    public Demo(SomeService a) {
        Runnable r = new Runnable() {
            public void run() { }
        };
        r.run();
    }
}`;
  assert.equal(findConstructorDeclarations(src).length, 1);
});

test('ignores an annotation argument that repeats the class name', () => {
  const src = `
@RestController
@Timed(value = "Demo(x)")
class Demo {
    public Demo(SomeService a) { }
}`;
  assert.equal(findConstructorDeclarations(src).length, 1);
});

test('a record header paren is not a constructor, and an enum has none', () => {
  // 记录类的紧凑构造器 `Demo { }` 没有参数列表，本规则数的是显式参数列表，
  // 所以是 0 而不是 1。record/enum 也当不了 @RestController，这条只是守住
  // "记录类头部的那对括号不会被当成构造器"。
  const compact = `record Demo(SomeService a) { Demo { } }`;
  assert.equal(findConstructorDeclarations(compact).length, 0);
  const en = `enum Demo { A, B }`;
  assert.equal(findConstructorDeclarations(en).length, 0);
});

test('finds an annotation written on the line above the constructor', () => {
  // @Autowired 写在上一行是仓库里的常见写法（RagSearchController 就是）。
  // 只看当前行会把每个这样的构造器报成"未标注"。
  const src = `
@RestController
class Demo {
    @Autowired
    public Demo(SomeService a) { }
    public Demo(SomeService a, Other b) { this(a); }
}`;
  const ctors = findConstructorDeclarations(src);
  assert.deepEqual(ctors.map((c) => c.annotated), [true, false]);
});

// ── 嵌套的 controller（真实存在：WebUiConfig 里的 WebUiController）──────

test('a controller nested in a non-controller class is examined as itself', () => {
  // 按文件判定 controller 会把 WebUiConfig 当成 controller，然后去数**外层**
  // 类的构造器——数错了对象。必须按"注解 + 类型声明"定位。
  const src = `
@Configuration
public class WebUiConfig {
    private int x;

    @RestController
    public static class WebUiController {
        WebUiController(ResourceLoader r) { }
        WebUiController(ResourceLoader r, CacheManager c) { this(r); }
    }
}`;
  const types = findControllerTypes(src);
  assert.equal(types.length, 1);
  assert.equal(types[0].name, 'WebUiController');
  assert.equal(findConstructorDeclarations(src, types[0]).length, 2);
});

test('the outer class of a nested controller is not itself examined', () => {
  const src = `
@Configuration
public class WebUiConfig {
    public WebUiConfig(A a) { }
    public WebUiConfig(A a, B b) { this(a); }

    @RestController
    public static class WebUiController {
    }
}`;
  const types = findControllerTypes(src);
  assert.equal(types.length, 1);
  assert.equal(findConstructorDeclarations(src, types[0]).length, 0);
});

test('a class-level annotation stack still resolves to the right type', () => {
  const src = `
@RestController
@RequestMapping("/x")
public class Demo {
    public Demo(A a) { }
    Demo(A a, B b) { this(a); }
}`;
  const types = findControllerTypes(src);
  assert.deepEqual(types.map((t) => t.name), ['Demo']);
  assert.equal(findConstructorDeclarations(src, types[0]).length, 2);
});

// ── controller detection ─────────────────────────────────────────────────

test('recognises both controller stereotypes and rejects everything else', () => {
  assert.equal(isController('@RestController\nclass Demo { }'), true);
  assert.equal(isController('@org.springframework.web.bind.annotation.RestController\nclass Demo { }'), true);
  assert.equal(isController('@Controller\nclass Demo { }'), true);
  assert.equal(isController('@Service\nclass DemoService { }'), false);
  assert.equal(isController('@Repository\nclass DemoRepository { }'), false);
  // The stereotype inside a comment must not count.
  assert.equal(isController('// @RestController\nclass Demo { }'), false);
  assert.equal(isController('class DemoController { }'), false);
});

test('reports the line each constructor sits on', () => {
  const src = [
    '@RestController',
    'class Demo {',
    '    @Autowired',
    '    public Demo(SomeService a) { }',
    '    Demo(SomeService a, Legacy b) { }',
    '}',
  ].join('\n');
  const ctors = findConstructorDeclarations(src);
  assert.deepEqual(ctors.map((c) => c.line), [4, 5]);
});

// ── end to end: the exit code is the only thing CI sees ──────────────────

function runGate(files) {
  const dir = mkdtempSync(join(tmpdir(), 'ctor-gate-'));
  for (const [name, body] of Object.entries(files)) {
    const path = join(dir, name);
    mkdirSync(join(path, '..'), { recursive: true });
    writeFileSync(path, body);
  }
  const res = spawnSync(process.execPath, [GATE], {
    env: { ...process.env, CONTROLLER_CTOR_ROOT: dir },
    encoding: 'utf8',
  });
  rmSync(dir, { recursive: true, force: true });
  return res;
}

const CLEAN = 'DemoController.java';
const DIRTY = 'DirtyController.java';

test('end to end: exits 1 and names the file when a controller has two constructors', () => {
  const res = runGate({
    [CLEAN]: '@RestController\nclass DemoController {\n    public DemoController(A a) { }\n}',
    [DIRTY]: '@RestController\nclass DirtyController {\n    public DirtyController(A a) { }\n    DirtyController(A a, B b) { this(a); }\n}',
  });
  assert.equal(res.status, 1);
  assert.match(res.stdout, /controller-constructor-count/);
  assert.match(res.stdout, /DirtyController\.java: DirtyController declares 2 constructors/);
  assert.match(res.stdout, /line 3 \(public\) \+ line 4 \(package\)/);
  assert.match(res.stderr, /1 controller\(s\) with more than one constructor out of 2 examined/);
});

test('end to end: exits 0 when every controller declares one constructor', () => {
  const res = runGate({
    [CLEAN]: '@RestController\nclass DemoController {\n    @Autowired\n    public DemoController(A a) { }\n}',
  });
  assert.equal(res.status, 0);
  assert.match(res.stdout, /1 controller\(s\) examined/);
});

test('end to end: a non-controller with two constructors is not reported', () => {
  const res = runGate({
    'Helper.java': '@Service\nclass Helper {\n    public Helper(A a) { }\n    public Helper(A a, B b) { this(a); }\n}',
  });
  assert.equal(res.status, 0);
  assert.match(res.stdout, /0 controller\(s\) examined/);
});

// ── run ──────────────────────────────────────────────────────────────────

let failed = 0;
for (const { title, fn } of cases) {
  try {
    fn();
    console.log(`  ok  ${title}`);
  } catch (error) {
    failed += 1;
    console.error(`FAIL  ${title}`);
    console.error(`      ${error.message}`);
  }
}
console.log(`\ncontroller-constructor-count self-test: ${cases.length - failed}/${cases.length} passed`);
if (failed > 0) process.exit(1);
