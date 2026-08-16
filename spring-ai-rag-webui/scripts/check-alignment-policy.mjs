import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');
const violations = [];
let intentionalCenterCount = 0;

function walk(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return walk(path);
    return /\.(css|tsx|ts)$/.test(entry.name) ? [path] : [];
  });
}

function addViolation(path, lineNumber, message) {
  violations.push(`${relative(projectRoot, path)}:${lineNumber} ${message}`);
}

for (const path of walk(sourceRoot)) {
  const lines = readFileSync(path, 'utf8').split(/\r?\n/);

  lines.forEach((line, index) => {
    const lineNumber = index + 1;
    const textAlignment = line.match(/text-align\s*:\s*(center|left|right)\b/);
    if (textAlignment) {
      const value = textAlignment[1];
      if (value === 'center') {
        const previousLine = lines[index - 1]?.trim() ?? '';
        if (/^\/\*\s*alignment-policy:\s*allow-center\s+--\s+\S.+\*\/$/.test(previousLine)) {
          intentionalCenterCount += 1;
        } else {
          addViolation(
            path,
            lineNumber,
            'text-align:center requires an immediately preceding alignment-policy allow-center comment',
          );
        }
      } else {
        addViolation(path, lineNumber, `text-align:${value} is not allowed; use logical start/end`);
      }
    }

    const inlineTextAlignment = line.match(/textAlign\s*:\s*['"](center|left|right)['"]/);
    if (inlineTextAlignment) {
      addViolation(
        path,
        lineNumber,
        `inline textAlign:${inlineTextAlignment[1]} is not allowed; use a CSS Module class`,
      );
    }
  });
}

const mainSource = readFileSync(join(sourceRoot, 'main.tsx'), 'utf8');
const appSource = readFileSync(join(sourceRoot, 'App.tsx'), 'utf8');
const globalSource = readFileSync(join(sourceRoot, 'styles/global.css'), 'utf8');

if (!mainSource.includes("import './styles/global.css';")) {
  violations.push('src/main.tsx must import ./styles/global.css as the canonical global stylesheet');
}
if (mainSource.includes("import './index.css';")) {
  violations.push('src/main.tsx must not import the removed Vite template stylesheet');
}
if (appSource.includes("import './styles/global.css';")) {
  violations.push('src/App.tsx must not import the global stylesheet a second time');
}
if (!/#root\s*\{[^}]*text-align\s*:\s*start\b/s.test(globalSource)) {
  violations.push('src/styles/global.css must define #root { text-align: start; }');
}
if (existsSync(join(sourceRoot, 'index.css'))) {
  violations.push('src/index.css is a removed Vite template stylesheet and must not return');
}
if (existsSync(join(sourceRoot, 'App.css'))) {
  violations.push('src/App.css is an unused Vite template stylesheet and must not return');
}

if (violations.length > 0) {
  console.error('Alignment policy violations:');
  for (const violation of violations) console.error(`- ${violation}`);
  process.exitCode = 1;
} else {
  console.log(`Alignment policy passed; intentional text centers: ${intentionalCenterCount}`);
}
