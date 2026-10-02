#!/usr/bin/env node
/**
 * SLO threshold entries must name an endpoint that can actually be measured.
 *
 * `ApiSloTrackerService.getCompliance()` iterates the threshold table from
 * `ApiSloProperties` and nothing else. An endpoint that is not in that table is
 * measured by `ApiSloHandlerInterceptor` and then never surfaced anywhere, and an
 * endpoint that *is* in the table but has been renamed stops receiving traffic
 * and reports a permanent 100% compliance — a green light indistinguishable
 * from a healthy one. Neither failure announces itself.
 *
 * Batch 806 hit the first version of this live: `POST /api/v1/rag/chat` carried
 * its own `@Timed("rag.chat.non-stream")`, so every call routed through that
 * alias was timed, aggregated, and then dropped by `getCompliance()`. The two
 * bodies were byte-identical duplicates, which is how the two timer names drifted
 * apart in the first place. Both URLs now publish `rag.chat.ask`.
 *
 * The second rule covers the other silent merge: two *different* handlers
 * publishing the same timer name fold into one set of statistics, so a fast
 * endpoint can mask a slow one and nothing in the report says so.
 *
 * Scope and known boundary:
 *   - Only the default threshold table in `ApiSloProperties`' constructor is
 *     checked. An `application.yml` override is runtime configuration and is not
 *     visible here.
 *   - An endpoint is considered measurable when its name is the value of some
 *     `@Timed`. `ApiSloHandlerInterceptor` also derives names for handlers with
 *     no `@Timed` at all (bean name + method name, or `rag.<method>.<path>`); a
 *     threshold pointed at one of those is reported as stale. That is deliberate
 *     and false-positive-free today — no such threshold exists — but if one is
 *     ever added it has to be registered here rather than worked around.
 *   - A name may legitimately repeat *within one controller*: that is how the
 *     `/ask` and `/chat` alias pair says "these are the same operation".
 *
 * What this gate does not catch, established by mutation rather than assumed:
 * restoring `@Timed("rag.chat.non-stream")` on the `/chat` alias — the exact
 * Batch 806 defect — leaves this gate green. The check runs threshold -> endpoint
 * and that edge still holds: `rag.chat.ask` is still published by `/ask`. The
 * missing edge is endpoint -> threshold, and it cannot be added here: 76 of the
 * 81 timed endpoints are outside the threshold table by design, so "measured but
 * not reported" is the normal case, not a defect. A gate that flagged it would
 * cry wolf on every ordinary endpoint. The duplication is prevented structurally
 * instead — `ask` and `chat` now delegate to one `executeNonStreamingJson` and
 * publish one timer, so there is no second copy left to drift. The second
 * mutation below (one timer name claimed by a second controller) is caught.
 *
 * Run:
 *   node scripts/verify-slo-endpoint-coverage.mjs
 */

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, sep, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const MAIN_ROOT = join(projectRoot, 'spring-ai-rag-core', 'src', 'main', 'java');
const PROPERTIES_FILE = join(
  projectRoot, 'spring-ai-rag-core', 'src', 'main', 'java', 'com', 'springairag',
  'core', 'config', 'ApiSloProperties.java',
);

export const VIOLATION_KINDS = Object.freeze({
  STALE_THRESHOLD: 'stale-slo-threshold',
  DUPLICATE_METRIC: 'duplicate-timed-metric',
});

/** Strips comments so prose cannot register an endpoint or a threshold. */
export function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ');
}

/** Every `@Timed(value = "...")` in the source, in order. */
export function collectTimedValues(source) {
  const code = stripComments(source);
  const out = [];
  const re = /@Timed\s*\(\s*(?:value\s*=\s*)?"([^"]+)"/g;
  let m;
  while ((m = re.exec(code)) !== null) out.push(m[1]);
  return out;
}

/** The `thresholds.put("...", N)` calls that build the default table. */
export function collectDefaultThresholds(source) {
  const code = stripComments(source);
  const out = [];
  const re = /thresholds\.put\(\s*"([^"]+)"\s*,/g;
  let m;
  while ((m = re.exec(code)) !== null) out.push(m[1]);
  return out;
}

/**
 * @param {string[]} thresholds keys from the default table
 * @param {Array<{name: string, source: string}>} files controller sources
 * @returns {{kind: string, detail: string}[]} empty when coverage holds
 */
export function checkCoverage(thresholds, files) {
  const byName = new Map();
  for (const { name, source } of files) {
    for (const value of collectTimedValues(source)) {
      if (!byName.has(value)) byName.set(value, new Set());
      // A Set, not a list: the same controller publishing one name twice is the
      // documented alias form, and counting occurrences would flag it.
      byName.get(value).add(name);
    }
  }

  const violations = [];

  for (const key of thresholds) {
    const owners = byName.get(key);
    if (!owners) {
      violations.push({
        kind: VIOLATION_KINDS.STALE_THRESHOLD,
        detail:
          `${key} is in the default SLO threshold table but no @Timed publishes that name. ` +
          'getCompliance() reports 100% compliance for it forever, which reads as healthy.',
      });
    }
  }

  for (const [value, ownerSet] of byName) {
    const owners = [...ownerSet];
    if (owners.length > 1) {
      violations.push({
        kind: VIOLATION_KINDS.DUPLICATE_METRIC,
        detail:
          `${value} is published by more than one controller (${owners.join(', ')}). ` +
          'Their latencies fold into a single set of statistics and the report cannot tell them apart.',
      });
    }
  }

  return violations;
}

function javaFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) out.push(...javaFiles(path));
    else if (entry.endsWith('.java')) out.push(path);
  }
  return out;
}

export function collectControllers(root = MAIN_ROOT) {
  if (!existsSync(root)) return [];
  return javaFiles(root).map((path) => ({
    name: relative(root, path).split(sep).join('/'),
    source: readFileSync(path, 'utf8'),
  }));
}

function main() {
  const controllers = collectControllers();
  const thresholds = collectDefaultThresholds(readFileSync(PROPERTIES_FILE, 'utf8'));
  const violations = checkCoverage(thresholds, controllers);

  if (violations.length > 0) {
    console.error('SLO endpoint coverage violations:');
    for (const v of violations) console.error(`- [${v.kind}] ${v.detail}`);
    console.error(
      '\nEvery default SLO threshold must name an endpoint that some @Timed actually\n' +
        'publishes, and one timer name must not be published by two controllers.',
    );
    process.exitCode = 1;
    return;
  }

  const timedCount = controllers.reduce(
    (n, f) => n + collectTimedValues(f.source).length, 0,
  );
  console.log(
    `SLO endpoint coverage passed; ${controllers.length} java source(s) scanned, ` +
      `${timedCount} @Timed endpoint(s), all ${thresholds.length} default threshold(s) ` +
      'resolvable and no timer name shared across controllers.',
  );
}

if (process.argv[1] && import.meta.url === `file://${resolve(process.argv[1])}`) {
  main();
}
