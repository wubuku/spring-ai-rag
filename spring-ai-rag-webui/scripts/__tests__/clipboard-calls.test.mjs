import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
import {
  findDirectClipboardAccess,
  isSourceFile,
  OWNER,
  VIOLATION_KINDS,
} from '../check-clipboard-calls.mjs';

const webuiRoot = fileURLToPath(new URL('../..', import.meta.url));

// A gate that cannot fail is worse than no gate. The defect behind this one was invisible
// in review: `await navigator.clipboard.writeText(rawKey); showToast(...copied)` reads
// correctly, and the two ways it fails — a rejected promise, and a `TypeError` from an
// absent `navigator.clipboard` outside a secure context — both produce no visible output.
//
// So most of what follows pins *non*-findings, because the two ways this rule can be
// wrong are both silent: a pattern that matches nothing (a green sweep over a tree it
// cannot see) and a rule that fires on the explanation of itself.

describe('findDirectClipboardAccess', () => {
  it('reports the two shapes the real code had', () => {
    const files = [{
      path: 'src/pages/ApiKeys.tsx',
      text: [
        'const copyRawKey = async () => {',
        '  if (!createdKey) return;',
        '  await navigator.clipboard.writeText(createdKey.rawKey);',
        "  showToast(t('apiKeys.copied'), 'success');",
        '};',
      ].join('\n'),
    }];

    const findings = findDirectClipboardAccess(files);

    expect(findings).toHaveLength(1);
    expect(findings[0].kind).toBe(VIOLATION_KINDS.DIRECT_CLIPBOARD_WRITE);
    expect(findings[0].path).toBe('src/pages/ApiKeys.tsx');
    expect(findings[0].line).toBe(3);
  });

  it('reports a destructured read, not only a call', () => {
    // The rule is about reaching the object at all. `const { writeText } =
    // navigator.clipboard` loses the `this` binding and fails at runtime in some
    // browsers, which is a second reason to want it in one place.
    const files = [{
      path: 'src/pages/Chat.tsx',
      text: 'const { writeText } = navigator.clipboard;',
    }];

    expect(findDirectClipboardAccess(files)).toHaveLength(1);
  });

  it('reports window.navigator.clipboard and odd spacing', () => {
    const files = [{ path: 'src/a.ts', text: 'await window.navigator . clipboard.writeText(x);' }];
    expect(findDirectClipboardAccess(files)).toHaveLength(1);
  });

  it('does not report the one file allowed to do it', () => {
    // The owner's own text names `navigator.clipboard` in a comment, a guard and a call.
    const real = readFileSync(join(webuiRoot, OWNER), 'utf8');
    expect(findDirectClipboardAccess([{ path: OWNER, text: real }])).toEqual([]);
  });

  it('does not report a comment that explains the rule', () => {
    // This is the failure that took the Batch 935 and Batch 938 gates down before they
    // were ever run on real code: the explanation of a rule trips the rule.
    const files = [{
      path: 'src/pages/Documents.tsx',
      text: [
        '// Batch 939: this used to call navigator.clipboard.writeText with no catch,',
        '// so a denied write looked identical to a successful one.',
        'const safe = await copyText(value);',
      ].join('\n'),
    }];

    expect(findDirectClipboardAccess(files)).toEqual([]);
  });

  it('does not report an unrelated property that merely contains the word', () => {
    const files = [{
      path: 'src/a.ts',
      text: 'const label = "clipboard";\nconst x = myclipboard.value;\n// navigator',
    }];
    expect(findDirectClipboardAccess(files)).toEqual([]);
  });

  it('leaves test files out of scope', () => {
    // A fixture has to be able to install a fake clipboard and say so in code.
    expect(isSourceFile('clipboard.test.ts')).toBe(false);
    expect(isSourceFile('ApiKeys.clipboard.test.tsx')).toBe(false);
    expect(isSourceFile('clipboard.ts')).toBe(true);
    expect(isSourceFile('clipboard.tsx')).toBe(true);
    expect(isSourceFile('clipboard.module.css')).toBe(false);
  });

  it('finds the violation in a real file with one line appended', () => {
    // Proof the scanner can read what is actually on disk, rather than only fixtures
    // shaped to suit it. A green sweep is the outcome this whole file exists to make
    // suspicious, so the sweep has to be shown to be capable of not being green.
    const real = readFileSync(join(webuiRoot, 'src/pages/Chat.tsx'), 'utf8');
    const withViolation = `${real}\nconst leak = navigator.clipboard.writeText;\n`;
    const findings = findDirectClipboardAccess([{
      path: 'src/pages/Chat.tsx',
      text: withViolation,
    }]);

    expect(findings).toHaveLength(1);
    expect(findings[0].kind).toBe(VIOLATION_KINDS.DIRECT_CLIPBOARD_WRITE);
  });

  it('finds nothing in the tree as it stands', () => {
    // The end state, stated as an assertion so a future change that reintroduces a raw
    // clipboard write shows up here with a name rather than only in CI.
    const real = readFileSync(join(webuiRoot, 'src/utils/clipboard.ts'), 'utf8');
    expect(findDirectClipboardAccess([{ path: OWNER, text: real }])).toEqual([]);
  });
});
