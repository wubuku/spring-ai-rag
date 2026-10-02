import { describe, it, expect } from 'vitest';
import {
  ALLOWED,
  checkFile,
  collectSources,
  findHardcodedCopy,
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
