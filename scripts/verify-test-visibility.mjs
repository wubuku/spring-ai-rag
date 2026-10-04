#!/usr/bin/env node
/**
 * Test-visibility gate.
 *
 * A test class that neither runs nor reports itself as skipped is invisible:
 * surefire records `tests="0" skipped="0"`, which is the exact same pair of
 * numbers a genuinely empty class produces. Nothing in the run summary tells
 * you the difference.
 *
 * That is not hypothetical. Before this gate, 21 PostgreSQL/Testcontainers
 * integration classes — about 145 test methods, including the *only* coverage
 * of the API-key rotation security guards — were gated by `assumeTrue` inside
 * `@BeforeAll`. JUnit aborts the container, and the classes vanished from the
 * summary entirely: the run reported "Skipped: 9", contributed by two other
 * classes, which reads as "everything is accounted for".
 *
 * Those classes now carry a class-level `@EnabledIfSystemProperty`, so a
 * disabled container is reported as skipped with its real test count. This gate
 * is what stops that from regressing: any class that reports neither a run nor
 * a skip fails the build.
 *
 * The check above only reads what surefire *wrote*. It never asks whether that
 * matches the source tree, which leaves two holes — both of them false
 * assurance, which is the one thing this gate exists to prevent:
 *
 *   1. **Ghost reports.** Deleting a test class does not remove its
 *      `TEST-*.xml`, and `mvn test` without `clean` does not remove its
 *      `target/test-classes` entry either. Surefire then keeps *executing* the
 *      orphan and counting it. Measured in this repository: 3 classes
 *      (`retrieval.AsyncTimeoutFallbackTests`, `retrieval.FulltextStrategyConfigTests`,
 *      `retrieval.fulltext.NoOpFulltextSearchProviderTest`) had been deleted
 *      from source but still ran 18 test methods on every build, inflating both
 *      the reported suite size and the coverage figures derived from it.
 *   2. **Silent non-execution.** A test class present in the source tree that
 *      produced no report at all is the exact failure this gate was built to
 *      catch, and the old check could not see it either.
 *
 * So the gate is bidirectional: every non-abstract `*Test`/`*Tests`/`*TestCase`
 * in the source tree must have produced a report, and every report must map back
 * to a source file.
 *
 * Run after `mvn test` (the full suite for the module, not a `-Dtest=` subset):
 *   node scripts/verify-test-visibility.mjs [surefire-reports-dir]
 *
 * ── 一次运行只覆盖一个模块（Batch 885 实测）────────────────────────────────
 *
 * 门禁从报告目录**反推**源码根目录，所以它只看得到自己被指向的那一个模块。
 * `scripts/verify-project-tests.sh:39` 不带参数调用，因此对账的永远是
 * `spring-ai-rag-core`；另外三个模块（api / starter / documents）的测试类
 * 从不进入任何一次对账。实测把它们逐个指过去，三个都是干净的——所以这是
 * **有实测支撑的限制**，不是活缺陷；但一次绿色运行原本印的是
 * "Source tree reconciled both ways"，读起来像仓库级���论。现在成功信息点明
 * 是哪个模块，并说明其余模块不在本次范围内。
 *
 * 为什么不把聚合入口改成四模块循环：一个只跑了 `-pl spring-ai-rag-core test`
 * 的开发者会因此看到另外三个模块"No surefire reports"而**门禁变红**——
 * 那是同一族的假指控（把"没跑"说成"消失了"）。要按模块对账，前提是先跑整个
 * reactor；这属于聚合入口的契约变更，不该由一道门禁的审查顺带决定。
 */

import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join, resolve, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));

/**
 * Surefire's default `includes` for test sources: a class whose simple name is
 * `Test*`, `*Test`, `*Tests` or `*TestCase`.
 *
 * Batch 885. This comment listed all four from the beginning; the pattern
 * implemented three. `Test*` was simply absent, so a test class named
 * `TestFoo.java` would be run by surefire and invisible here — and if it ever
 * stopped running, this gate could not say so, which is the one thing it is
 * for. The gap is narrow but not empty: `logging/TestMaskDebug.java` sits in it
 * today. It happens to be a `main()` scratchpad with no test method, so nothing
 * breaks yet; the criterion was simply narrower than the contract it documents.
 *
 * Adding the pattern is not sufficient on its own. `unreported` requires every
 * collected source's primary class to have produced a report, and surefire
 * reports nothing at all for a class with no test method — measured across all
 * four modules: 0 of 1006 reports carry `tests="0"`. So a file in the include
 * set that contains no tests would be reported as "a test class that produced
 * no report", which is the wrong accusation. `collectSourceTestClasses`
 * therefore also skips sources that declare no test method.
 */
export const TEST_FILE_PATTERN = /(?:^Test.*|(?:Test|Tests|TestCase))\.java$/;

/**
 * A source that declares none of these cannot produce a report, so requiring one
 * would be asking for something impossible.
 *
 * The direction of a mistake here is not symmetric, so it is worth being precise
 * about which way is safe. Classifying a real test class as "contains no tests"
 * means a vanished one goes unreported — the gate goes quiet, which is the
 * failure it exists to prevent. So the pattern is deliberately broad, and it was
 * cross-checked against the real tree rather than trusted: of the 1006 classes
 * surefire reported across the four modules, 1003 carry one of these annotations
 * in their source. The three that do not are the three ghosts the header already
 * documents — sources deleted from the tree whose reports survived — which is
 * exactly the case where "declares no test method" is the right answer.
 */
const TEST_METHOD_ANNOTATION =
  /@(?:[\w$]+\.)*(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|Parameterized|Suite)\b/;

export function parseReport(xml) {
  const open = /<testsuite\b[^>]*>/.exec(xml);
  if (!open) return null;
  const tag = open[0];
  const read = key => {
    const found = new RegExp(`\\b${key}="(\\d+)"`).exec(tag);
    return found ? Number.parseInt(found[1], 10) : 0;
  };
  const name = /name="([^"]+)"/.exec(tag);
  return {
    name: name ? name[1] : '(unnamed)',
    tests: read('tests'),
    skipped: read('skipped'),
    failures: read('failures'),
    errors: read('errors'),
  };
}

/**
 * @param {{file: string, xml: string}[]} reports
 * @returns {{invisible: object[], totals: object}}
 */
export function audit(reports) {
  // Filter *before* spreading: `{ ...null }` is `{}`, which is truthy, so a
  // `map(parse).filter(Boolean)` chain would keep malformed reports and then
  // add their `undefined` counters into the totals.
  const parsed = reports
    .map(r => ({ parsed: parseReport(r.xml), file: r.file }))
    .filter(entry => entry.parsed !== null)
    .map(entry => ({ ...entry.parsed, file: entry.file }));
  const totals = parsed.reduce(
    (acc, r) => ({
      classes: acc.classes + 1,
      tests: acc.tests + r.tests,
      skipped: acc.skipped + r.skipped,
      failures: acc.failures + r.failures,
      errors: acc.errors + r.errors,
    }),
    { classes: 0, tests: 0, skipped: 0, failures: 0, errors: 0 },
  );
  // Invisible == ran nothing AND did not say it was skipping. An empty class is
  // legitimately zero; a class that *chose* to skip says so.
  const invisible = parsed.filter(r => r.tests === 0 && r.skipped === 0);
  return { invisible, totals };
}

/**
 * Removes comments and string/char literals so a class name mentioned in prose
 * cannot be mistaken for a declaration.
 */
function stripNonCode(text) {
  let out = '';
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    const next = text[i + 1];
    if (c === '/' && next === '/') {
      while (i < text.length && text[i] !== '\n') i++;
      out += '\n';
      continue;
    }
    if (c === '/' && next === '*') {
      i += 2;
      while (i < text.length && !(text[i] === '*' && text[i + 1] === '/')) i++;
      i++;
      out += ' ';
      continue;
    }
    if (c === '"' || c === "'") {
      const quote = c;
      i++;
      while (i < text.length && text[i] !== quote) {
        if (text[i] === '\\') i++;
        i++;
      }
      out += ' ';
      continue;
    }
    out += c;
  }
  return out;
}

/**
 * Is the type this file is named after an abstract class or record?
 *
 * Scoped to the top-level declaration on purpose — see the call site. A package
 * declaration before it cannot contain braces, so walking the stripped text up
 * to the first top-level `class`/`record` keyword is enough; anything nested
 * inside a method body is at brace depth 1 or deeper and is not reached.
 */
function isTopLevelAbstract(code) {
  const head = code.replace(/^\s*package\b[^;]*;/, '');
  const typeStart = /\b(?:class|record|enum|interface)\s+/.exec(head);
  if (!typeStart) return false;
  const modifiers = head.slice(0, typeStart.index);
  return /\babstract\b/.test(modifiers);
}

/**
 * Lists the test sources under a `src/test/java` root together with the class
 * names each file can actually produce a surefire report for.
 *
 * A report is named after a *class*, but a Java file may declare several: the
 * primary class, package-private siblings, and `@Nested` classes. Surefire scans
 * compiled `.class` files, not file names, so it reports each of them, and it
 * names a nested report `<package>.<NestedSimpleName>` with **no outer-class
 * prefix**. Reconciling by file name alone therefore flagged 18 healthy tests as
 * ghosts; collecting every `class` token instead flagged ~200, because the token
 * also appears in fixtures and in prose.
 *
 * So the set is built from only the two shapes that genuinely earn a report:
 * declarations at brace depth 0 (the primary class and its package-private
 * siblings) and classes annotated `@Nested`.
 *
 * Even then the set is a deliberate **superset**. Surefire does not report every
 * `@Nested` class in its own file — in this repository
 * `RetrievalEvaluationServiceImplTest` contributes six nested classes that are
 * counted only inside the parent's report, while
 * `HybridRetrieverServiceTest` contributes three that each get their own file.
 * The grouping is not predictable from the source, so `classNames` may list
 * names that have no report of their own. That is safe for one direction only,
 * which is why `reconcile` uses `classNames` to spot ghosts and `primary` to
 * spot sources that never ran.
 *
 * Abstract classes are excluded: surefire never instantiates them, so expecting
 * a report for one would keep the gate permanently red.
 *
 * @param {string} sourceRoot
 * @returns {{file: string, packageName: string, primary: string, classNames: string[]}[]}
 */
export function collectSourceTestClasses(sourceRoot) {
  if (!existsSync(sourceRoot)) return [];
  const found = [];
  const walk = dir => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(path);
        continue;
      }
      if (!TEST_FILE_PATTERN.test(entry.name)) continue;
      const raw = readFileSync(path, 'utf8');
      if (!TEST_METHOD_ANNOTATION.test(raw)) continue;
      const code = stripNonCode(raw);
      // Batch 885. This used to read `abstract class` **anywhere** in the file
      // and drop the whole file. Surefire never instantiates an abstract class,
      // so excluding one is right — but a real test class with an abstract
      // helper nested inside it was being dropped too, and then a test class
      // that vanished could not be reported, because it was not in the inventory
      // to begin with. The check belongs on the top-level type, which is the one
      // whose report the gate requires.
      if (isTopLevelAbstract(code)) continue;

      const rel = relative(sourceRoot, path).split(sep);
      const primary = rel.pop().replace(/\.java$/, '');
      const names = new Set([primary]);

      // `@Nested` sits directly above the class it annotates; a bounded window
      // keeps this from pairing an annotation with a distant class.
      for (const m of code.matchAll(/@Nested\b[^A-Za-z$]{0,200}?\b(?:class|record)\s+([A-Za-z_$][\w$]*)/g)) {
        names.add(m[1]);
      }

      // Package-private siblings declared next to the primary class. Tracking
      // brace depth is what separates them from fixture classes inside methods.
      let depth = 0;
      const tokens = /[{}]|\b(?:class|record|interface|enum)\s+([A-Za-z_$][\w$]*)/g;
      let m;
      while ((m = tokens.exec(code)) !== null) {
        const token = m[0];
        if (token === '{') {
          depth++;
        } else if (token === '}') {
          depth--;
        } else if (depth === 0) {
          names.add(m[1]);
        }
      }

      found.push({
        file: path,
        packageName: rel.join('.'),
        primary,
        classNames: [...names].sort(),
      });
    }
  };
  walk(sourceRoot);
  return found;
}

/**
 * Two-way reconciliation between the source tree and what surefire produced.
 *
 * `unreported` == a test source whose tests never ran (the failure this gate was
 * created for). Only the file's primary class is required to have a report,
 * because that is the one name surefire always reports when a source runs.
 *
 * `ghosts` == a report for a class no source declares any more, which means its
 * numbers are inflating every total the gate prints. Detection uses the full
 * declared superset, so it can over-accept and never falsely accuses a live
 * class.
 *
 * @param {{file: string, packageName: string, primary: string, classNames: string[]}[]} sourceFiles
 * @param {{name: string, file: string}[]} reportedClasses
 * @returns {{unreported: string[], ghosts: string[]}}
 */
export function reconcile(sourceFiles, reportedClasses) {
  const reported = new Set(reportedClasses.map(c => c.name));
  const qualify = (f, name) => (f.packageName ? `${f.packageName}.${name}` : name);
  const declared = new Set();
  for (const f of sourceFiles) {
    for (const name of f.classNames) declared.add(qualify(f, name));
  }
  return {
    unreported: sourceFiles
      .map(f => qualify(f, f.primary))
      .filter(fqcn => !reported.has(fqcn))
      .sort(),
    ghosts: [...reported].filter(name => !declared.has(name)).sort(),
  };
}

/** `<module>/target/surefire-reports` -> `<module>/src/test/java`. */
export function sourceRootFor(reportsDir) {
  return resolve(reportsDir, '..', '..', 'src', 'test', 'java');
}

function main() {
  const reportsDir = resolve(
    process.argv[2] ?? join(projectRoot, 'spring-ai-rag-core/target/surefire-reports'),
  );

  if (!existsSync(reportsDir)) {
    console.error(
      `No surefire reports at ${reportsDir}. Run the test suite first — ` +
        'this gate reads what the suite actually produced.',
    );
    process.exitCode = 1;
    return;
  }

  const reports = readdirSync(reportsDir)
    .filter(name => name.startsWith('TEST-') && name.endsWith('.xml'))
    .map(name => ({
      file: name,
      xml: readFileSync(join(reportsDir, name), 'utf8'),
    }));

  const { invisible, totals } = audit(reports);

  if (invisible.length > 0) {
    console.error('Test classes that neither ran nor reported a skip:');
    for (const r of invisible) {
      console.error(`- ${r.name}`);
    }
    console.error(
      '\nA class gated by `assumeTrue` inside `@BeforeAll` is aborted, not skipped, ' +
        'so it disappears from the run summary. Move the gate to a class-level ' +
        '`@EnabledIfSystemProperty` so the skip is counted.',
    );
    process.exitCode = 1;
    return;
  }

  const sourceRoot = sourceRootFor(reportsDir);
  const sourceClasses = collectSourceTestClasses(sourceRoot);
  const { unreported, ghosts } = reconcile(
    sourceClasses,
    reports.map(({ file, xml }) => ({ ...parseReport(xml), file })).filter(Boolean),
  );

  if (unreported.length > 0 || ghosts.length > 0) {
    if (unreported.length > 0) {
      console.error(
        `Test classes in ${relative(projectRoot, sourceRoot) || sourceRoot} that produced no report:`,
      );
      for (const fqcn of unreported) console.error(`- ${fqcn}`);
      console.error(
        '\nThe suite summary cannot see a class that never ran. This gate runs ' +
          'after the module\'s FULL `mvn test`, not a `-Dtest=` subset.',
      );
    }
    if (ghosts.length > 0) {
      console.error('\nSurefire reports with no matching source file:');
      for (const name of ghosts) console.error(`- ${name}`);
      console.error(
        '\nThese numbers are inflating the totals above, and there are two causes. ' +
          'Either the class was deleted from source and only its report survived, in ' +
          'which case deleting the stale `TEST-*.xml` is enough; or the class was ' +
          'deleted but its `target/test-classes` entry survived, in which case ' +
          '`mvn test` without `clean` keeps *executing* it and the source file must ' +
          'be restored or the class removed. Run `mvn clean test` and re-check.',
      );
    }
    process.exitCode = 1;
    return;
  }

  const module = relative(projectRoot, reportsDir).split(sep)[0] || '(repo root)';
  console.log(
    `Test visibility passed for ${module}; ${totals.classes} class(es), ${totals.tests} test(s), `
      + `${totals.skipped} reported skip(s), and no class vanished silently. `
      + `The source tree of that one module was reconciled both ways against `
      + `${sourceClasses.length} declared test class(es). Other modules are outside this run — the `
      + 'aggregate entry point points the gate at spring-ai-rag-core, and pointing it at a sibling '
      + 'module needs a full-reactor run first, or the missing reports read as vanished tests.',
  );
}

if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  main();
}
