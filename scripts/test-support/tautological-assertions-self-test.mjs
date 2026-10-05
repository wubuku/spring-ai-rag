// Batch 908. Self-test for scripts/verify-tautological-assertions.mjs.
//
// What a self-test has to prove here is narrow and easy to get wrong: that the
// scanner still *rejects* the two shapes it was written for, and that it does
// not start rejecting the three shapes it deliberately leaves alone. A scanner
// that finds nothing passes vacuously, and Batch 901 named that as the worst
// failure available in this repository.
//
// The fixtures are written to a temporary tree and scanned through the public
// `scan()` entry point, so this file depends on nothing but the scanner itself.
// Batch 904 learned that the hard way: a self-test that copies the gate next to
// its fixtures stops being a fixture the moment the scanner grows a dependency
// of its own, and then 26 of 29 cases fail for a reason that has nothing to do
// with the rule.
//
// The negatives are the load-bearing half. Eight `assertEquals(x, x)` and three
// `x == false` hits came out of the census that produced this gate, and none of
// them is the defect — JUnit resolves them through `equals`, and `x == false` is
// an ordinary assertion. Cases 8 through 15 below are the reason this gate needs
// no allowlist: without them a future widening of the rules would look fine.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { scan } from '../lib/tautological-assertion-check.mjs';

const FIXTURE = `package fixture;

class SampleTest {
    void literals() {
        assertTrue(true);
        assertFalse(false);
        assertNull(null);
        assertNotNull("ready");
        assertNotNull(42);
    }

    void topLevelConnectives() {
        assertTrue(!hasAssistantRole || true, "assistant 角色不应残留");
        assertTrue((boolean) allowed.invoke(x) == false || true);
        assertTrue((!hasAssistantRole || true));
        assertFalse(isReady && false, "永远不该成立");
    }

    void realAssertions() {
        assertTrue(matched == false);
        assertTrue(healthy == false || degraded.search("q").isEmpty());
        assertTrue(hasRole || false);
        assertTrue(hasRole && true);
        assertFalse(missing == true);
        assertEquals(a, a);
        assertEquals(r.hashCode(), r.hashCode());
        assertNotNull(service);
        assertThrows(RagException.class, () -> run());
        assertTrue(label.equals("a || true"));
        assertTrue(nested("x", true) && deeper() == false);
    }

    void commentedOut() {
        // assertTrue(true);
        /* assertNull(null); */
    }
}
`;

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'tautological-assertions-'));
try {
  fs.mkdirSync(path.join(tmp, 'src/test/java'), { recursive: true });
  fs.writeFileSync(path.join(tmp, 'src/test/java/SampleTest.java'), FIXTURE);

  const { findings, files, assertions } = scan(tmp);
  const rules = findings.map((f) => f.rule).sort();
  const expected = [
    'assertFalse(false)',
    'assertNotNull(<non-null literal>)',
    'assertNotNull(<non-null literal>)',
    'assertNull(null)',
    'assertTrue(true)',
    'top-level && false',
    'top-level || true',
    'top-level || true',
    'top-level || true',
  ];

  const failures = [];
  if (files !== 1) failures.push(`扫描到 ${files} 个文件，应为 1`);
  // 20 assertion call sites in the fixture: 5 literals + 4 connectives + 11 real
  // assertions, plus 2 more that only exist inside comments.
  if (assertions !== 20) failures.push(`数到 ${assertions} 处断言调用，应为 20`);
  if (rules.length !== expected.length) {
    failures.push(`命中 ${findings.length} 处，应为 ${expected.length}：${JSON.stringify(rules)}`);
  } else {
    for (let i = 0; i < expected.length; i++) {
      if (rules[i] !== expected[i]) {
        failures.push(`第 ${i + 1} 条应为 ${expected[i]}，实为 ${rules[i]}`);
      }
    }
  }

  // The two shapes that must never be reported, asserted by name so that a
  // regression names the case instead of just changing a count.
  const texts = findings.map((f) => f.text).join('\n');
  for (const forbidden of ['assertEquals(a, a)', 'matched == false',
    'assertTrue(hasRole || false)', 'assertTrue(label.equals("a || true"))']) {
    if (texts.includes(forbidden)) {
      failures.push(`不应被报告：${forbidden}`);
    }
  }

  if (failures.length > 0) {
    for (const f of failures) console.error(`FAIL: ${f}`);
    process.exit(1);
  }

  console.log(`Tautological-assertion self-test: ${findings.length} finding(s) rejected and`
    + ` ${assertions - findings.length} assertion(s) accepted from one fixture — all as expected.`);
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}
