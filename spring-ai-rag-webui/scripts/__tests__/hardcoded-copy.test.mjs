import { describe, it, expect } from 'vitest';
import {
  ALLOWED,
  checkFile,
  collectSources,
  findExpressionContainerCopy,
  findHardcodedCopy,
  maskTranslationCalls,
  stripComments,
  usesI18n,
  VIOLATION_KINDS,
} from '../check-hardcoded-copy.mjs';

const kinds = (relPath, source) => checkFile(relPath, source).map(v => v.kind);

const WITH_I18N = `
import { useTranslation } from 'react-i18next';
export function Widget() {
  const { t } = useTranslation();
  return <p>{t('common.loading')}</p>;
}
`;

describe('check-hardcoded-copy', () => {
  it('accepts a component whose visible text all goes through t()', () => {
    expect(kinds('components/Widget.tsx', WITH_I18N)).toEqual([]);
  });

  it('rejects a component that never reaches for i18n', () => {
    const source = `
export function Widget() {
  return <h3>Call Volume</h3>;
}
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([
      VIOLATION_KINDS.NO_I18N_AT_ALL,
    ]);
  });

  it('rejects a literal in a component that does use i18n, as a different kind', () => {
    const source = `${WITH_I18N}
export function Other() { return <h3>Call Volume</h3>; }
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('catches chart labels, not just JSX text', () => {
    const source = `${WITH_I18N}
const data = [{ name: 'Retrievals', value: 1 }];
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('catches title and placeholder attributes', () => {
    // The first sweep of this gate's own detection missed both of these; they
    // are announced and shown, so they must be part of the surface.
    const source = `${WITH_I18N}
export function Other() {
  return <button title="Generate UUID" placeholder="UUID or business key" />;
}
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('catches a hardcoded accessible name', () => {
    const source = `${WITH_I18N}
export function Other() {
  return <div role="region" aria-label="Notifications" />;
}
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('does not read a literal out of a comment', () => {
    const source = `${WITH_I18N}
// <h3>Call Volume</h3> — the old markup
export function Other() { return <p>{t('a.b')}</p>; }
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([]);
    expect(stripComments(source)).not.toContain('Call Volume');
  });

  it('accepts an allowlisted enum whose label is its own value', () => {
    const entry = Object.entries(ALLOWED)[0];
    const [relPath, copy] = entry[0].split(':');
    const source = `${WITH_I18N}
export function Other() { return <option value="${copy}">${copy}</option>; }
`;
    expect(kinds(relPath, source)).toEqual([]);
  });

  it('does not extend an allowlist entry to a different file', () => {
    // A path-scoped exemption must not become a free pass for the same word
    // somewhere else.
    const [, copy] = Object.entries(ALLOWED)[0][0].split(':');
    const source = `${WITH_I18N}
export function Other() { return <option value="${copy}">${copy}</option>; }
`;
    expect(kinds('pages/SomeOtherFile.tsx', source)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('requires every allowlist entry to carry a reason', () => {
    for (const [key, reason] of Object.entries(ALLOWED)) {
      expect(typeof reason).toBe('string');
      expect(reason.length).toBeGreaterThan(20);
      expect(key).toMatch(/^.+\.tsx:.+$/);
    }
  });

  it('detects withTranslation and <Trans> as i18n use too', () => {
    expect(usesI18n('export default withTranslation(C)')).toBe(true);
    expect(usesI18n('export const A = () => <Trans i18nKey="x" />')).toBe(true);
    expect(usesI18n('export const A = () => <p>plain</p>')).toBe(false);
  });

  it('reports the line the literal sits on', () => {
    const source = `${WITH_I18N}\nexport function Other() {\n  return <h3>Call Volume</h3>;\n}\n`;
    const [violation] = checkFile('components/Widget.tsx', source);
    expect(violation.detail).toContain('components/Widget.tsx:9');
  });

  it('finds nothing in the real tree beyond the allowlist', () => {
    const files = collectSources();
    expect(files.length).toBeGreaterThan(20);
    expect(files.flatMap(f => checkFile(f.relPath, f.source))).toEqual([]);
  });

  it('collects real sources and skips test files', () => {
    const files = collectSources();
    expect(files.some(f => f.relPath.endsWith('.test.tsx'))).toBe(false);
    expect(findHardcodedCopy('x.tsx', '<p>Hi there friend</p>').length).toBe(1);
  });
});

// Batch 812 widened this gate to JSX expression containers, and the first draft
// of that rule reported 70 strings of which 59 were not rendered text. Each
// exclusion below corresponds to one of the false-positive classes that produced
// that number, because a gate that cries wolf 59 times out of 60 is worse than
// no gate: it gets allowlisted, and then it protects nothing.
describe('the JSX expression-container surface', () => {
  it('reports a rendered ternary', () => {
    const source = `${WITH_I18N}\n  const v = <span>{on ? 'Yes' : 'No'}</span>;\n`;
    const copies = findExpressionContainerCopy(source).map(h => h.copy);
    expect(copies).toContain('Yes');
    expect(copies).toContain('No');
  });

  it('reports the two branches separately rather than merging them', () => {
    // A character class that allowed an apostrophe inside the literal merged
    // 'Yes' : 'No into one nonsense string in the first draft.
    const source = `${WITH_I18N}\n  const v = <span>{on ? 'Yes' : 'No'}</span>;\n`;
    const copies = findExpressionContainerCopy(source).map(h => h.copy);
    expect(copies).toEqual(expect.arrayContaining(['Yes', 'No']));
    expect(copies.some(c => c.includes("'"))).toBe(false);
  });

  it('does not report ARIA role tokens', () => {
    // Translating 'alert' and 'status' would break the accessibility tree.
    const source = `${WITH_I18N}\n  const v = <div role={k === 'x' ? 'alert' : 'status'} />;\n`;
    expect(findExpressionContainerCopy(source)).toEqual([]);
  });

  it('does not report a t() fallback string', () => {
    const source = `${WITH_I18N}\n  const v = <IconButton label={t('nav.closeSidebar', 'Close sidebar')} />;\n`;
    expect(findExpressionContainerCopy(source)).toEqual([]);
  });

  it('does not report a t() defaultValue option', () => {
    // This class alone produced eight phantom strings on Settings.tsx, because
    // the argument-list pattern only understood quoted arguments.
    const source = `${WITH_I18N}\n  const v = <p>{t('k', { defaultValue: 'Configure the thing.' })}</p>;\n`;
    expect(findExpressionContainerCopy(source)).toEqual([]);
  });

  it('does not report a key segment inside a template interpolation', () => {
    const source = `${WITH_I18N}\n  showToast(t(` + "`documents.relocationErrors.${code || 'DEFAULT'}`" + `), 'error');\n`;
    expect(findExpressionContainerCopy(source)).toEqual([]);
  });

  it('does not report a plain function building a class name', () => {
    // lifecycleClass() branches on 'DISABLED' : 'READY' : 'NOT_REQUESTED' to
    // index styles[]; none of it is rendered.
    const source = `
function lifecycleClass() {
  const value = enabled === false
    ? 'DISABLED'
    : searchability || (fresh ? 'READY' : 'NOT_REQUESTED');
  return styles['lifecycle' + value];
}
`;
    expect(findExpressionContainerCopy(source)).toEqual([]);
  });

  it('does not report an enum comparison', () => {
    const source = `${WITH_I18N}\n  const v = <button disabled={principal.status !== 'ACTIVE'}>x</button>;\n`;
    expect(findExpressionContainerCopy(source)).toEqual([]);
  });

  it('does not report a JavaScript object literal', () => {
    const source = `
await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' } });
`;
    expect(findExpressionContainerCopy(source)).toEqual([]);
  });

  it('masks an entire t() call including object options', () => {
    const masked = maskTranslationCalls("x = t('a', { defaultValue: 'B' }) + 'kept';");
    expect(masked).not.toContain('defaultValue');
    expect(masked).not.toContain("'a'");
    expect(masked).toContain("'kept'");
  });

  it('does not mistake a longer identifier ending in t for a translation call', () => {
    expect(maskTranslationCalls("format(v, 'kept')")).toContain("'kept'");
  });
});
