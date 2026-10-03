#!/usr/bin/env node
// Self-test for scripts/verify-false-optional-wiring.mjs.
//
// A rule that only ever says "yes" is an allowlist wearing a gate's clothes, so
// most of what follows pins *non*-findings: a genuinely conditional bean, a
// required injection, a null check with no Spring assignment behind it, and a
// claim that carries a written reason. The end-to-end cases run the real script
// as a child process, because the exit code is the only thing CI observes and
// four "gates that cannot fail" have already had to be deleted in this repo.

import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import {
  collectBeans,
  findFalseOptionalClaims,
} from '../verify-false-optional-wiring.mjs';

const here = fileURLToPath(import.meta.url);
const GATE = join(here, '..', '..', 'verify-false-optional-wiring.mjs');

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

const beansFrom = (...sources) => collectBeans(sources.map((source, i) => ({
  path: `/f${i}.java`, source,
})));

const UNCONDITIONAL = beansFrom('@Service\npublic class SomeService {\n}');
const CONDITIONAL = beansFrom(
  '@Service\n@ConditionalOnProperty(name = "x")\npublic class SomeService {\n}',
);
const NOT_A_BEAN = new Map();

// ── the load-bearing rule ─────────────────────────────────────────────────

test('flags a guarded collaborator whose bean is unconditional', () => {
  const src = `
class Demo {
    private SomeService someService;
    @Autowired(required = false)
    public void setSomeService(SomeService s) { this.someService = s; }
    void go() {
        if (someService == null) { throw new IllegalStateException("unavailable"); }
    }
}`;
  const hits = findFalseOptionalClaims(src, UNCONDITIONAL);
  assert.equal(hits.length, 1);
  assert.equal(hits[0].field, 'someService');
  assert.equal(hits[0].bean, 'SomeService');
});

test('flags a != null guard as readily as a == null one', () => {
  const src = `
class Demo {
    private SomeService someService;
    @Autowired(required = false)
    public void setSomeService(SomeService s) { this.someService = s; }
    void go() { if (someService != null) { someService.run(); } }
}`;
  assert.equal(findFalseOptionalClaims(src, UNCONDITIONAL).length, 1);
});

test('flags a setter that takes more than the field', () => {
  // Batch 820's own census script missed `setCollectionProvisioningService`
  // because that setter also takes a ProvisioningOwnerResolver. Every fixture
  // here had a one-argument setter, so the self-test agreed with the bug. A
  // self-test whose fixtures are all simpler than the real code is a self-test
  // that cannot find the mistake it was written to find.
  const src = `
class Demo {
    private SomeService someService;
    @Autowired(required = false)
    public void setSomeService(SomeService s, OtherResolver r) {
        this.someService = s;
    }
    void go() { if (someService == null) { return; } }
}`;
  assert.equal(findFalseOptionalClaims(src, UNCONDITIONAL).length, 1);
});

test('flags a claim made through the constructor rather than a setter', () => {
  // Four controllers take `@Autowired(required = false) AuditLogService
  // auditLogService` as their last constructor argument — the same unverifiable
  // promise, reached by a route a setter-only rule cannot follow.
  const src = `
class Demo {
    private SomeService someService;
    public Demo(RagDocumentRepository repo,
                @Autowired(required = false) SomeService someService) {
        this.someService = someService;
    }
    void go() { if (someService == null) { return; } }
}`;
  assert.equal(findFalseOptionalClaims(src, UNCONDITIONAL).length, 1);
});

test('flags a package-private setter', () => {
  // The fourth miss. Requiring `public void` hid EvaluationController's real
  // claim: its setter has no access modifier at all, and promises exactly what
  // a public one does. A gate that constrains the shape of the code it reads
  // keeps passing while the code it should have caught moves out of reach.
  const src = `
class Demo {
    private SomeService someService;
    @Autowired(required = false)
    void setSomeService(SomeService s) { this.someService = s; }
    void go() { if (someService == null) { return; } }
}`;
  assert.equal(findFalseOptionalClaims(src, UNCONDITIONAL).length, 1);
});

// ── non-findings: the shapes that would make this rule noise ─────────────

test('releases a collaborator whose bean is genuinely conditional', () => {
  // The release condition, and the reason the rule is not a blanket objection to
  // `required = false`. Seven beans in this repository look like this.
  const src = `
class Demo {
    private SomeService someService;
    @Autowired(required = false)
    public void setSomeService(SomeService s) { this.someService = s; }
    void go() { if (someService == null) { return; } }
}`;
  assert.deepEqual(findFalseOptionalClaims(src, CONDITIONAL), []);
});

test('releases a required injection', () => {
  const src = `
class Demo {
    private SomeService someService;
    @Autowired
    public void setSomeService(SomeService s) { this.someService = s; }
    void go() { if (someService == null) { return; } }
}`;
  assert.deepEqual(findFalseOptionalClaims(src, UNCONDITIONAL), []);
});

test('releases a claim that records a reason on the field', () => {
  const src = `
class Demo {
    private SomeService someService; // optional-claim: the guard turns an NPE into a stated error
    @Autowired(required = false)
    public void setSomeService(SomeService s) { this.someService = s; }
    void go() { if (someService == null) { return; } }
}`;
  assert.deepEqual(findFalseOptionalClaims(src, UNCONDITIONAL), []);
});

test('releases a null guard with no Spring assignment behind it', () => {
  // A plain final field assigned by the constructor is not an optional claim.
  const src = `
class Demo {
    private final SomeService someService;
    Demo(SomeService s) { this.someService = s; }
    void go() { if (someService == null) { return; } }
}`;
  assert.deepEqual(findFalseOptionalClaims(src, UNCONDITIONAL), []);
});

test('releases a type that is not a bean at all', () => {
  const src = `
class Demo {
    private SomeValue someValue;
    @Autowired(required = false)
    public void setSomeValue(SomeValue v) { this.someValue = v; }
    void go() { if (someValue == null) { return; } }
}`;
  assert.deepEqual(findFalseOptionalClaims(src, NOT_A_BEAN), []);
});

test('releases a setter whose parameter is a different collaborator', () => {
  // The annotation, the field and the guard must be about the same type, or a
  // class could satisfy the rule by owning an unrelated optional setter.
  const src = `
class Demo {
    private SomeService someService;
    private OtherService otherService;
    @Autowired(required = false)
    public void setOtherService(OtherService s) { this.otherService = s; }
    void go() { if (someService == null) { return; } }
}`;
  assert.deepEqual(findFalseOptionalClaims(src, UNCONDITIONAL), []);
});

// ── bean discovery ────────────────────────────────────────────────────────

test('separates conditional beans from unconditional ones', () => {
  const beans = beansFrom(
    '@Service\npublic class Plain {\n}',
    '@Service\n@ConditionalOnMissingBean\npublic class Guarded {\n}',
    'public class NotABean {\n}',
  );
  assert.equal(beans.has('Plain'), true);
  assert.equal(beans.get('Plain').conditional, false);
  assert.equal(beans.get('Guarded').conditional, true);
  assert.equal(beans.has('NotABean'), false);
});

// ── the gate end to end ───────────────────────────────────────────────────

function runGate(files) {
  const dir = mkdtempSync(join(tmpdir(), 'false-optional-gate-'));
  for (const [name, body] of Object.entries(files)) {
    writeFileSync(join(dir, name), body, 'utf8');
  }
  const result = spawnSync(process.execPath, [GATE], {
    env: { ...process.env, FALSE_OPTIONAL_WIRING_ROOT: dir },
    encoding: 'utf8',
  });
  rmSync(dir, { recursive: true, force: true });
  return result;
}

const CONTROLLER = (fieldLine) => `class DemoController {\n`
  + fieldLine
  + '    @Autowired(required = false)\n'
  + '    public void setSomeService(SomeService s) { this.someService = s; }\n'
  + '    void go() { if (someService == null) { throw new IllegalStateException("x"); } }\n}\n';

test('the gate exits non-zero on an unrecorded false claim', () => {
  const result = runGate({
    'SomeService.java': '@Service\npublic class SomeService {\n}\n',
    'DemoController.java': CONTROLLER('    private SomeService someService;'),
  });
  assert.equal(result.status, 1,
    `expected a failing exit, got ${result.status}\n${result.stdout}${result.stderr}`);
  assert.match(result.stdout + result.stderr, /false-optional-wiring/,
    'the violation must be reported, not merely counted');
  assert.match(result.stdout + result.stderr, /ConditionalOn/,
    'the report must say what evidence would make the claim true');
});

test('the gate exits zero when the reason is recorded', () => {
  const result = runGate({
    'SomeService.java': '@Service\npublic class SomeService {\n}\n',
    'DemoController.java': CONTROLLER(
      '    private SomeService someService; // optional-claim: unconditional @Service; the guard turns an NPE into a stated error',
    ),
  });
  assert.equal(result.status, 0, `expected a passing exit, got ${result.status}\n${result.stdout}${result.stderr}`);
  assert.match(result.stdout, /passed/, 'a passing run must say so');
});

test('the gate exits zero when the bean is genuinely conditional', () => {
  const result = runGate({
    'SomeService.java': '@Service\n@ConditionalOnProperty(name = "x")\npublic class SomeService {\n}\n',
    'DemoController.java': CONTROLLER('    private SomeService someService;'),
  });
  assert.equal(result.status, 0, `expected a passing exit, got ${result.status}\n${result.stdout}${result.stderr}`);
});

// ── Batch 825: the three blind spots the real tree was hiding behind ─────
// Each of these is a case where the gate reported 0 findings on a real tree
// while a genuine false claim sat in the code. A self-test that only covers
// the shapes it already handles is what let all three through.

test('sees a private final field — the shape most injected collaborators use', () => {
  const src = `
class Demo {
    private final SomeService someService;
    @Autowired
    public Demo(@Autowired(required = false) SomeService someService) { this.someService = someService; }
    void go() { if (someService == null) { throw new IllegalStateException("x"); } }
}`;
  assert.equal(findFalseOptionalClaims(src, UNCONDITIONAL).length, 1);
});

test('sees the fully-qualified @Autowired(required = false) spelling', () => {
  // ApiKeyController writes it this way, and the census probe in Batch 823 had
  // already missed the fully-qualified `@Autowired` once. Same miss, inherited.
  const src = `
class Demo {
    private final SomeService someService;
    @Autowired
    public Demo(@org.springframework.beans.factory.annotation.Autowired(required = false)
                SomeService someService) { this.someService = someService; }
    void go() { if (someService == null) { throw new IllegalStateException("x"); } }
}`;
  assert.equal(findFalseOptionalClaims(src, UNCONDITIONAL).length, 1);
});

test('a comment quoting the old guard does not make a field look guarded', () => {
  // Batch 825 deleted a guard and left a note explaining the deletion, quoting
  // the old code. The gate then reported the field again — writing an honest
  // comment turned the gate red, which is the worst possible coupling.
  const src = `
class Demo {
    private final SomeService someService;
    @Autowired
    public Demo(@Autowired(required = false) SomeService someService) { this.someService = someService; }
    // this used to be: if (someService == null) throw new IllegalStateException("x")
    void go() { someService.run(); }
}`;
  assert.equal(findFalseOptionalClaims(src, UNCONDITIONAL).length, 0);
});

test('a reason in a trailing comment still counts, even after neutralization', () => {
  // The counterpart to the case above: comments are blanked for *code*, but a
  // recorded reason is deliberately prose, so it has to survive.
  const src = `
class Demo {
    private final SomeService someService;  // optional-claim: audit failures must not fail the request
    @Autowired
    public Demo(@Autowired(required = false) SomeService someService) { this.someService = someService; }
    void go() { if (someService == null) { someService.log("skip"); } }
}`;
  assert.equal(findFalseOptionalClaims(src, UNCONDITIONAL).length, 0);
});

let failed = 0;
for (const { title, fn } of cases) {
  try {
    fn();
    console.log(`ok - ${title}`);
  } catch (err) {
    failed += 1;
    console.error(`not ok - ${title}`);
    console.error(`  ${err.message}`);
  }
}
console.log(`\nFalse-optional-wiring self-test: ${cases.length - failed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
