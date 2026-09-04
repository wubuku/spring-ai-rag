import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');
const globalPath = join(sourceRoot, 'styles/global.css');
const baselinePath = join(projectRoot, 'scripts/design-token-color-baseline.json');

// global.css is the token definition source; test files do not ship.
const COLOR_SCAN_EXEMPT = new Set([relative(projectRoot, globalPath)]);
const isTestFile = path => /\.(test|spec)\.[cm]?[jt]sx?$/i.test(path);

const globalSource = readFileSync(globalPath, 'utf8');
const definitions = new Set(
  [...globalSource.matchAll(/(--[a-z0-9-]+)\s*:/gi)].map(match => match[1]),
);

// Hex (#rgb/#rgba/#rrggbb/#rrggbbaa) and rgb()/hsl() function literals, including
// those used as var() fallbacks and inside CSS comments. Named colors are out of
// scope for this gate.
const COLOR_LITERAL_PATTERNS = [
  /#[0-9a-f]{3,4}\b/gi,
  /#[0-9a-f]{6}\b/gi,
  /#[0-9a-f]{8}\b/gi,
  /\brgba?\(/gi,
  /\bhsla?\(/gi,
];

function countColorLiterals(line) {
  let count = 0;
  for (const pattern of COLOR_LITERAL_PATTERNS) {
    count += line.split(pattern).length - 1;
  }
  return count;
}

function walk(directory, extensions) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return walk(path, extensions);
    return extensions.some(extension => entry.name.endsWith(extension)) ? [path] : [];
  });
}

const violations = [];

for (const path of walk(sourceRoot, ['.css'])) {
  const source = readFileSync(path, 'utf8');
  const lines = source.split(/\r?\n/);
  lines.forEach((line, index) => {
    for (const match of line.matchAll(/var\((--[a-z0-9-]+)/gi)) {
      if (!definitions.has(match[1])) {
        violations.push(
          `${relative(projectRoot, path)}:${index + 1} uses undefined ${match[1]}`,
        );
      }
    }
    if (path.endsWith('.module.css') && /z-index\s*:\s*-?\d+\s*;/.test(line)) {
      violations.push(
        `${relative(projectRoot, path)}:${index + 1} must use a global z-index token`,
      );
    }
  });
}

// Literal color policy: src files must reference tokens instead of hard-coded
// colors. Files recorded in scripts/design-token-color-baseline.json are allowed
// exactly their baseline count while they migrate; unlisted files must be at zero.
const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'));
const actualCounts = new Map();

for (const path of walk(sourceRoot, ['.css', '.ts', '.tsx'])) {
  const relativePath = relative(projectRoot, path);
  if (COLOR_SCAN_EXEMPT.has(relativePath) || isTestFile(relativePath)) continue;
  const source = readFileSync(path, 'utf8');
  const count = source
    .split(/\r?\n/)
    .reduce((total, line) => total + countColorLiterals(line), 0);
  if (count > 0) actualCounts.set(relativePath, count);
}

for (const [file, count] of actualCounts) {
  const allowed = baseline[file] ?? 0;
  if (count > allowed) {
    violations.push(
      `${file} has ${count} literal color(s); baseline allows ${allowed}. ` +
        'Use design tokens from src/styles/global.css.',
    );
  }
}

for (const [file, allowed] of Object.entries(baseline)) {
  const actual = actualCounts.get(file) ?? 0;
  if (actual < allowed) {
    violations.push(
      `${file} baseline is stale: allows ${allowed} literal color(s) but file has ${actual}. ` +
        'Lower the baseline entry (or remove it at zero) so new colors stay blocked.',
    );
  }
}

if (violations.length > 0) {
  console.error('Design token violations:');
  for (const violation of violations) console.error(`- ${violation}`);
  process.exitCode = 1;
} else {
  const baselineFiles = Object.keys(baseline).length;
  console.log(
    `Design token policy passed; ${definitions.size} global tokens available, ` +
      `${baselineFiles} file(s) with grandfathered literal colors`,
  );
}
