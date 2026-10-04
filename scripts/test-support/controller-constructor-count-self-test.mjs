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
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import {
  neutralize,
  findControllerTypes,
  findConstructorDeclarations,
  countAutowiredMethods,
} from '../verify-controller-constructor-count.mjs';

const here = fileURLToPath(import.meta.url);
const GATE = join(here, '..', '..', 'verify-controller-constructor-count.mjs');
const CORE = join(here, '..', '..', '..', 'spring-ai-rag-core/src/main/java/com/springairag/core');

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
//
// `isController` used to be exported here and asserted by eight cases below.
// It had no production reader: `main()` walks `findControllerTypes` directly,
// so the predicate existed only to be tested. Batch 884 removed it rather than
// leaving an export whose sole consumer is its own test — the shape where a
// self-test grows to cover something that was never wired to anything.

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

// ── Batch 884: what the gate does NOT count ──────────────────────────────
//
// The success line used to claim that exactly one constructor means every
// dependency a test injects is one the production wiring has too. That is false
// on this tree: six controllers inject thirteen collaborators through an
// `@Autowired` method, and a constructor count cannot see any of them.
//
// These cases pin the boundary rather than trying to close it. Closing it here
// would mean a second enforcement point for a debt `verify-false-optional-wiring`
// already owns, and two valves for one exemption is how an exemption rots.

test('an @Autowired setter is not a constructor, in either direction', () => {
  // The two mistakes are symmetric and both were made while measuring this:
  // counting a setter as a constructor, and counting a constructor as a setter.
  const src = `@RestController
class Demo {
    @Autowired
    public Demo(A a) { }
    @Autowired(required = false)
    void configureB(B b) { }
}`;
  const type = findControllerTypes(src)[0];
  assert.equal(findConstructorDeclarations(src, type).length, 1,
    'the constructor must be counted once and the setter not at all');
  assert.deepEqual(countAutowiredMethods(src, type),
    { sites: 1, collaborators: 1, optionalCollaborators: 1, requiredCollaborators: 0 });
});

test('a package-private setter counts, and a fully-qualified annotation too', () => {
  // Both halves of one real controller. The first run of this measurement read
  // `required = false` from a 60-character window, which a fully-qualified
  // annotation overflows, and reported four optional sites as required. The
  // window has to be the whole annotation, argument list included.
  const src = `@RestController
class Demo {
    @Autowired
    public Demo(A a) { }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureB(B b) { }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureC(C c) { }
}`;
  const type = findControllerTypes(src)[0];
  assert.deepEqual(countAutowiredMethods(src, type),
    { sites: 2, collaborators: 2, optionalCollaborators: 2, requiredCollaborators: 0 });
});

test('a required setter is counted separately from an optional one', () => {
  // RagCollectionController.setCollectionPurgeService is a plain `@Autowired`:
  // production always injects it, and its field is dereferenced unguarded at two
  // call sites. No gate judges it — verify-false-optional-wiring only reads
  // `required = false` — so the success line has to name it.
  const src = `@RestController
class Demo {
    @Autowired
    public Demo(A a) { }
    @Autowired
    public void setPurge(PurgeService p) { }
}`;
  const type = findControllerTypes(src)[0];
  assert.deepEqual(countAutowiredMethods(src, type),
    { sites: 1, collaborators: 1, optionalCollaborators: 0, requiredCollaborators: 1 });
});

test('one setter carrying two parameters injects two collaborators', () => {
  // RagChatController.configureModeAwareExecution takes both a command mapper
  // and an execution service. Counting methods instead of collaborators would
  // understate the surface by one and make the number disagree with the field
  // count a reader can verify by hand.
  const src = `@RestController
class Demo {
    @Autowired(required = false)
    void configureBoth(ChatCommandMapper mapper,
                       ChatExecutionService execution) { }
}`;
  const type = findControllerTypes(src)[0];
  assert.deepEqual(countAutowiredMethods(src, type),
    { sites: 1, collaborators: 2, optionalCollaborators: 2, requiredCollaborators: 0 });
});

test('a constructor parameter annotation is not a setter', () => {
  // `@Autowired(required = false) AuditLogService auditLogService` appears on a
  // parameter inside a constructor's own parameter list. It sits at depth 1 in
  // the flattened text, so a rule that only looked for the annotation would
  // count it.
  const src = `@RestController
class Demo {
    @Autowired
    public Demo(A a,
                @Autowired(required = false) AuditLogService log) { }
}`;
  const type = findControllerTypes(src)[0];
  assert.deepEqual(countAutowiredMethods(src, type),
    { sites: 0, collaborators: 0, optionalCollaborators: 0, requiredCollaborators: 0 });
  assert.equal(findConstructorDeclarations(src, type).length, 1);
});

test('reports no constructors rather than null when there is no type to read', () => {
  // It used to return null on one path and [] on another, which forced a `?? []`
  // at the one production call site. A function that answers a question about a
  // list should answer it with a list.
  assert.deepEqual(findConstructorDeclarations('class { not a type }'), []);
});

// The measured surface, pinned as a ratchet.
//
// This is a number a reader can check by hand: twelve `@Autowired` methods on
// six controllers, injecting thirteen collaborators, twelve of them optional.
// If it moves, the tree changed — re-read the gate's header before updating it,
// because the header is where the reasoning about the gap lives.
test('the real tree still has the injection surface the header describes', () => {
  const files = [
    'controller/RagChatController.java',
    'controller/RagDocumentController.java',
    'controller/RagSearchController.java',
    'controller/RagCollectionController.java',
    'controller/OpenAiCompatibilityController.java',
    'controller/CollectionEmbeddingReadinessController.java',
  ];
  const totals = { sites: 0, collaborators: 0, optionalCollaborators: 0, requiredCollaborators: 0 };
  let controllers = 0;
  for (const rel of files) {
    const source = readFileSync(join(CORE, rel), 'utf8');
    const found = findControllerTypes(source);
    assert.equal(found.length, 1, `${rel} should declare one controller type`);
    controllers += 1;
    const counts = countAutowiredMethods(source, found[0]);
    for (const k of Object.keys(totals)) totals[k] += counts[k];
  }
  assert.equal(controllers, 6, 'the header says six controllers use setter injection');
  assert.deepEqual(totals, {
    sites: 12, collaborators: 13, optionalCollaborators: 12, requiredCollaborators: 1,
  });
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

test('end to end: a passing run says what it did not check', () => {
  // The regression guard for the sentence itself. The old success line read
  // "each declares exactly one constructor, so every dependency a test injects
  // is one the production wiring has too" — a claim this tree violates, on the
  // fixture below and on the real one. A reader who sees "passed" has to be
  // able to find out what passing did not cover, without reading the source.
  const res = runGate({
    'SetterController.java': '@RestController\nclass SetterController {\n'
      + '    private B b;\n'
      + '    @Autowired\n'
      + '    public SetterController(A a) { }\n'
      + '    @Autowired(required = false)\n'
      + '    void configureB(B b) { this.b = b; }\n}',
  });
  assert.equal(res.status, 0);
  assert.match(res.stdout, /none declares more than one constructor/,
    'the run must state the invariant it actually checked');
  assert.match(res.stdout, /counts constructors only/,
    'and must say out loud that constructors are all it counts');
  assert.match(res.stdout, /1 collaborator\(s\) across 1 controller\(s\)/,
    'the uncovered surface must be measured, not described in prose');
  assert.match(res.stdout, /1 of them through @Autowired\(required = false\)/,
    'and the optional share must be attributed to the gate that judges it');
  assert.doesNotMatch(res.stdout, /every dependency a test injects/,
    'the old sentence overstated what a constructor count can establish');
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
