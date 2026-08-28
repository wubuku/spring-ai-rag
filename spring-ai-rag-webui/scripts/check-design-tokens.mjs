import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');
const globalPath = join(sourceRoot, 'styles/global.css');
const globalSource = readFileSync(globalPath, 'utf8');
const definitions = new Set(
  [...globalSource.matchAll(/(--[a-z0-9-]+)\s*:/gi)].map(match => match[1]),
);
const violations = [];

function walk(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return walk(path);
    return entry.name.endsWith('.css') ? [path] : [];
  });
}

for (const path of walk(sourceRoot)) {
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

if (violations.length > 0) {
  console.error('Design token violations:');
  for (const violation of violations) console.error(`- ${violation}`);
  process.exitCode = 1;
} else {
  console.log(`Design token policy passed; ${definitions.size} global tokens available`);
}
