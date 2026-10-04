import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, cpSync } from 'node:fs';
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

/**
 * Batch 862. Every case below is the *same violation written differently*, or
 * prose about a violation reported as one. The scan used to be line-by-line
 * with two literal regexes, so the shape of the declaration decided whether
 * the policy applied at all.
 */
describe('a violation the old scan could not see', () => {
  it('rejects a centre written in upper case', () => {
    // CSS keywords are ASCII case-insensitive. A pattern without the `i` flag
    // is a policy that can be left by typing one more letter.
    const { status, stderr } = run({
      'src/components/Badge.module.css': `.badge {
  text-align: CENTER;
}
`,
    });
    expect(status).toBe(1);
    expect(stderr).toContain('allow-center comment');
  });

  it('rejects physical left written in upper case', () => {
    const { status, stderr } = run({
      'src/components/Badge.module.css': `.badge {
  text-align: LEFT;
}
`,
    });
    expect(status).toBe(1);
    expect(stderr).toContain('use logical start/end');
  });

  it('rejects a centre whose value sits on the next line', () => {
    // The old scan matched one line at a time, so the value being one line down
    // made it not a value. The report is anchored on the *property* line, which
    // is where an `allow-center` comment would have to sit.
    const { status, stderr } = run({
      'src/components/Badge.module.css': `.badge {
  text-align:
    center;
}
`,
    });
    expect(status).toBe(1);
    expect(stderr).toContain('src/components/Badge.module.css:2');
  });

  it('still honours an allow-center comment above a split declaration', () => {
    // The exemption is read from the raw source. An earlier version of this fix
    // ran the scan on `stripComments` output, which erases the very comment the
    // rule is asking for — that turned all eleven intentional centres in the
    // real tree into violations, and is why the rule reads raw text and skips
    // matches that fall *inside* a comment instead.
    const { status, stdout } = run({
      'src/components/Badge.module.css': `.badge {
  /* alignment-policy: allow-center -- numeric labels read better centred */
  text-align:
    center;
}
`,
    });
    expect(status).toBe(0);
    expect(stdout).toContain('intentional text centers: 1');
  });

  it('rejects an inline textAlign written in upper case', () => {
    const { status, stderr } = run({
      'src/components/Legacy.tsx': `export function Legacy() {
  return <span style={{ textAlign: 'Center' }}>x</span>;
}
`,
    });
    expect(status).toBe(1);
    expect(stderr).toContain('inline textAlign:center is not allowed');
  });
});

describe('prose that is not a violation', () => {
  it('does not report a text-align mentioned inside a CSS comment', () => {
    // The old scan reported this at the line of the comment. A stylesheet that
    // explains its own history is not a stylesheet that breaks the policy.
    const { status } = run({
      'src/components/Badge.module.css': `/* this used to say text-align: center */
.badge {
  color: red;
}
`,
    });
    expect(status).toBe(0);
  });

  it('does not report a declaration written across a multi-line comment', () => {
    const { status } = run({
      'src/components/Badge.module.css': `/* historic
  text-align: left;
*/
.badge {
  color: red;
}
`,
    });
    expect(status).toBe(0);
  });

  it('does not report an inline style quoted inside a JSDoc block', () => {
    const { status } = run({
      'src/components/Legacy.tsx': `/**
 * Previously: <span style={{ textAlign: 'center' }}>x</span>
 */
export function Legacy() {
  return null;
}
`,
    });
    expect(status).toBe(0);
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

describe('Batch 881: three things this gate was matching that it should not', () => {
  // Each case below is a shape the gate reported, or would have reported, on a
  // tree where nothing is wrong. All three are the same family: the rule is
  // about a declaration, and something that merely looks like one was answering
  // for it. Two of them cry wolf; the third let a justification outlive the
  // thing it justified.
  it('leaves test files alone', () => {
    // A test that quotes a violation in order to assert on it is the normal way
    // to document one, and this was the only one of the six frontend gates that
    // walked them. Failing here also meant the only way out was an allow-center
    // comment justifying a test string as a centring decision.
    const { status, stderr } = run({
      'src/components/Badge.test.tsx':
        "export const sample = `.badge {\n  text-align: center;\n}\n`;\n",
    });
    expect(status).toBe(0);
    expect(stderr).not.toContain('text-align:center');
  });

  it('still rejects the same violation in a product file', () => {
    // Without this, "tests are skipped" could just mean the rule stopped.
    const { status, stderr } = run({
      'src/components/Badge.tsx':
        "export const sample = `.badge {\n  text-align: center;\n}\n`;\n",
    });
    expect(status).toBe(1);
    expect(stderr).toContain('text-align:center requires an immediately preceding');
  });

  it('does not read a custom property as a declaration', () => {
    // `--text-align: center` defines a token. The regex has no way to tell, and
    // in a project built around a token layer that is the obvious next name to
    // use. The allowance-comment variant is the sharper case: it used to pass
    // only because the comment vouched for something that was never a
    // declaration, which is exactly the rot the next rule now reports.
    const { status, stderr } = run({
      'src/components/Badge.module.css': '.badge {\n  --text-align: center;\n}\n',
    });
    expect(status).toBe(0);
    expect(stderr).not.toContain('text-align:center');
  });

  it('still rejects a real declaration next to a similarly named token', () => {
    const { status, stderr } = run({
      'src/components/Badge.module.css':
        '.badge {\n  --text-align: center;\n  text-align: center;\n}\n',
    });
    expect(status).toBe(1);
    expect(stderr).toContain('text-align:center requires an immediately preceding');
  });

  it('rejects an exemption that no declaration claims any more', () => {
    // The rot check-hardcoded-copy had in its allowlist, same shape: the centre
    // is gone, the justification is not, and the next centre written under it
    // inherits a reason written for a declaration that no longer exists.
    const { status, stderr } = run({
      'src/components/Badge.module.css':
        '.badge {\n  /* alignment-policy: allow-center -- numeric labels read better centred */\n  color: red;\n}\n',
    });
    expect(status).toBe(1);
    expect(stderr).toContain('this allow-center comment exempts nothing');
  });

  it('accepts an exemption that its declaration still claims', () => {
    // The other half of the same change: a good reason must keep working, or
    // the fix would have cost more than it bought.
    const { status, stdout } = run({
      'src/components/Badge.module.css':
        '.badge {\n  /* alignment-policy: allow-center -- numeric labels read better centred */\n  text-align: center;\n}\n',
    });
    expect(status).toBe(0);
    expect(stdout).toContain('intentional text centers: 1');
  });

  it('leaves no unused exemption in the real tree', () => {
    // The invariant against the real sources: every allow-center comment there
    // is currently claimed. Asserted so the next rot cannot arrive quietly.
    const result = spawnSync(process.execPath, [gateSource], { encoding: 'utf8' });
    expect(result.status).toBe(0);
    expect(result.stderr).not.toContain('exempts nothing');
  });
});

describe('the documented exemption count', () => {
  // "there are currently N such exemptions" is a hand-maintained copy of a
  // machine-maintained number, which is the shape that rots. It read 11 for
  // several batches after the eleventh centre was converted to `start` — the
  // gate was reporting 10 the whole time and nobody was comparing the two.
  // The same treatment the design gate's rule list already gets.
  const documented = relativePath => {
    const text = readFileSync(join(projectRoot, '..', relativePath), 'utf8');
    const section = text.match(/## 11\.[^\n]*\n[\s\S]*?(?=\n## )/);
    if (!section) throw new Error(`${relativePath} has no section 11 on alignment`);
    const match = section[0].match(/currently (\d+) such exemptions|目前有 (\d+) 处这样的豁免/);
    if (!match) throw new Error(`${relativePath} section 11 states no exemption count`);
    return Number(match[1] ?? match[2]);
  };

  it('states the number the gate actually reports, in both languages', () => {
    const result = spawnSync(process.execPath, [gateSource], { encoding: 'utf8' });
    expect(result.status).toBe(0);
    const reported = Number(result.stdout.match(/intentional text centers: (\d+)/)[1]);
    expect(reported).toBeGreaterThan(0);
    expect(documented('docs/webui-design-language.md')).toBe(reported);
    expect(documented('docs/webui-design-language-zh-CN.md')).toBe(reported);
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
