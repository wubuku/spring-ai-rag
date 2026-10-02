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
 * Run after `mvn test`:
 *   node scripts/verify-test-visibility.mjs [surefire-reports-dir]
 */

import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));

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

  console.log(
    `Test visibility passed; ${totals.classes} class(es), ${totals.tests} test(s), ` +
      `${totals.skipped} reported skip(s), and no class vanished silently.`,
  );
}

if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  main();
}
