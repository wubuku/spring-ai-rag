import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, cpSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));
const gateSource = join(projectRoot, 'scripts', 'check-alignment-policy.mjs');

const MAIN_TSX = `import './styles/global.css';
import App from './App';
createRoot(document.getElementById('root')!).render(<App />);
`;
const APP_TSX = `export default function App() {
  return <div className="app" />;
}
`;
const GLOBAL_CSS = `#root {
  text-align: start;
}
`;

/**
 * `check-alignment-policy.mjs` is a script, not a module of pure functions: it
 * resolves its project root from `import.meta.url` and, on failure, assigns to
 * `process.exitCode` at import time. Importing it from a test would run it
 * against the real WebUI and, if the real tree ever had a violation, fail the
 * whole suite through a side effect. So the gate is copied into a throwaway tree
 * and run as a child process — which is also the only way to assert the property
 * that actually matters for a gate: the exit status.
 *
 * A copy rather than a fixture argument means the production script needs no
 * test hook.
 */
function withTree(files, fn) {
  const root = mkdtempSync(join(tmpdir(), 'alignment-policy-'));
  try {
    mkdirSync(join(root, 'scripts'), { recursive: true });
    cpSync(gateSource, join(root, 'scripts', 'check-alignment-policy.mjs'));
    mkdirSync(join(root, 'src', 'styles'), { recursive: true });
    writeFileSync(join(root, 'src', 'main.tsx'), MAIN_TSX);
    writeFileSync(join(root, 'src', 'App.tsx'), APP_TSX);
    writeFileSync(join(root, 'src', 'styles', 'global.css'), GLOBAL_CSS);
    for (const [path, content] of Object.entries(files)) {
      const full = join(root, path);
      mkdirSync(dirname(full), { recursive: true });
      writeFileSync(full, content);
    }
    const result = spawnSync(process.execPath, [join(root, 'scripts', 'check-alignment-policy.mjs')], {
      encoding: 'utf8',
    });
    return fn({ status: result.status, stdout: result.stdout ?? '', stderr: result.stderr ?? '' });
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

const run = (files = {}) => withTree(files, r => r);

// A gate that cannot fail is worse than no gate, and this repository has a
// documented habit of producing one — the census in Batch 809 deleted a fourth
// instance (`check-entity-migration-sync.sh`) that compared nothing at all, and
// the reason it survived was that nobody had ever asked this gate to reject
// anything. So every case below asserts a rejection, and the real tree is checked
// last so a rule that stopped matching would surface as a clean run here rather
// than as a silent pass in `npm run lint`.
describe('a tree that respects the alignment policy', () => {
  it('passes and reports the intentional-centre count', () => {
    const { status, stdout } = run();
    expect(status).toBe(0);
    expect(stdout).toContain('Alignment policy passed');
  });

  it('accepts a centre that carries the allow-center comment', () => {
    const { status, stdout } = run({
      'src/components/Badge.module.css': `.badge {
  /* alignment-policy: allow-center -- numeric labels read better centred */
  text-align: center;
}
`,
    });
    expect(status).toBe(0);
    expect(stdout).toContain('intentional text centers: 1');
  });
});

describe('text alignment', () => {
  it('rejects a centre with no allow-center comment and names the line', () => {
    const { status, stderr } = run({
      'src/components/Badge.module.css': `.badge {
  text-align: center;
}
`,
    });
    expect(status).toBe(1);
    expect(stderr).toContain('src/components/Badge.module.css:2');
    expect(stderr).toContain('allow-center comment');
  });

  it('rejects a centre whose allow-center comment is not immediately above', () => {
    // The rule is "immediately preceding", so an intervening blank line is a
    // different code path from the accepted case above.
    const { status, stderr } = run({
      'src/components/Badge.module.css': `.badge {
  /* alignment-policy: allow-center -- centred on purpose */

  text-align: center;
}
`,
    });
    expect(status).toBe(1);
    expect(stderr).toContain('src/components/Badge.module.css:4');
  });

  it('rejects physical left/right in favour of logical start/end', () => {
    const { status, stderr } = run({
      'src/components/Badge.module.css': `.badge {
  text-align: left;
}
`,
    });
    expect(status).toBe(1);
    expect(stderr).toContain('use logical start/end');
  });

  it('rejects an inline textAlign even inside a component', () => {
    const { status, stderr } = run({
      'src/components/Legacy.tsx': `export function Legacy() {
  return <span style={{ textAlign: 'center' }}>x</span>;
}
`,
    });
    expect(status).toBe(1);
    expect(stderr).toContain('inline textAlign:center is not allowed');
  });

  it('walks nested directories rather than only src top level', () => {
    const { status, stderr } = run({
      'src/components/deep/nested/Thing.module.css': `.thing {
  text-align: center;
}
`,
    });
    expect(status).toBe(1);
    expect(stderr).toContain('src/components/deep/nested/Thing.module.css:2');
  });
});

describe('the global stylesheet contract', () => {
  it('rejects a main.tsx that stops importing global.css', () => {
    const { status, stderr } = run({
      'src/main.tsx': "import App from './App';\ncreateRoot(document.getElementById('root')!).render(<App />);\n",
    });
    expect(status).toBe(1);
    expect(stderr).toContain("src/main.tsx must import ./styles/global.css");
  });

  it('rejects a main.tsx that reverts to the removed index.css', () => {
    const { status, stderr } = run({
      'src/main.tsx': `${MAIN_TSX}import './index.css';\n`,
    });
    expect(status).toBe(1);
    expect(stderr).toContain('must not import the removed Vite template stylesheet');
  });

  it('rejects a re-import of global.css in App.tsx', () => {
    const { status, stderr } = run({
      'src/App.tsx': `import './styles/global.css';\n${APP_TSX}`,
    });
    expect(status).toBe(1);
    expect(stderr).toContain('must not import the global stylesheet a second time');
  });

  it('rejects the return of the removed Vite template stylesheets', () => {
    const { status, stderr } = run({ 'src/index.css': 'body { margin: 0; }\n' });
    expect(status).toBe(1);
    expect(stderr).toContain('src/index.css is a removed Vite template stylesheet');
  });

  it('rejects a #root rule that drops text-align: start', () => {
    const { status, stderr } = run({ 'src/styles/global.css': '#root {\n  color: red;\n}\n' });
    expect(status).toBe(1);
    expect(stderr).toContain('must define #root { text-align: start; }');
  });
});

describe('the real WebUI tree', () => {
  // Last on purpose: if a rule silently stopped matching, the cases above would
  // still pass while `npm run lint` reported a clean run.
  it('passes today', () => {
    const result = spawnSync(process.execPath, [gateSource], { encoding: 'utf8' });
    expect(result.status).toBe(0);
    expect(result.stdout).toContain('Alignment policy passed');
  });
});
