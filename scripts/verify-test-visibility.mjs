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
 */

import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join, resolve, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));

/**
 * Surefire's default `includes` for test sources: a class whose simple name is
 * `Test*`, `*Test`, `*Tests` or `*TestCase`.
 */
export const TEST_FILE_PATTERN = /(?:Test|Tests|TestCase)\.java$/;

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
      const code = stripNonCode(readFileSync(path, 'utf8'));
      if (/\babstract\s+(?:class|record)\b/.test(code)) continue;

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

  console.log(
    `Test visibility passed; ${totals.classes} class(es), ${totals.tests} test(s), ` +
      `${totals.skipped} reported skip(s), and no class vanished silently. ` +
      `Source tree reconciled both ways against ${sourceClasses.length} declared test class(es).`,
  );
}

if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  main();
}
